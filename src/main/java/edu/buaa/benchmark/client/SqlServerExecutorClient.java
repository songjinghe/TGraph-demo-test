package edu.buaa.benchmark.client;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import edu.buaa.benchmark.transaction.*;
import edu.buaa.model.StatusUpdate;
import edu.buaa.utils.TimeMonitor;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

public class SqlServerExecutorClient implements DBProxy {
    private ThreadPoolExecutor exe;
    private BlockingQueue<Connection> connectionPool = new LinkedBlockingQueue<>();
    private ListeningExecutorService service;
    private ReqSeqSender serviceSeq;

    public SqlServerExecutorClient(String serverHost, int parallelCnt, int queueLength) throws IOException, ClassNotFoundException, SQLException {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        String dbURL = "jdbc:sqlserver://" + serverHost + ":1433";//; DatabaseName=sample
        this.exe = new ThreadPoolExecutor(parallelCnt, parallelCnt, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueLength), (r, executor) -> {
            if (!executor.isShutdown()) try {
                executor.getQueue().put(r);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        exe.prestartAllCoreThreads();
        this.serviceSeq = new ReqSeqSender(parallelCnt);
        this.service = MoreExecutors.listeningDecorator(exe);
        for(int i = 0; i< parallelCnt; i++) {
            Connection conn = DriverManager.getConnection(dbURL, "sa", "SQLServer2019@Benchmark");
            connectionPool.offer(conn);
        }
    }

    @Override
    public ListenableFuture<DBProxy.ServerResponse> execute(AbstractTransaction tx) {
        switch (tx.getTxType()){
            case tx_import_static_data:
                return this.service.submit(execute((ImportStaticDataTx) tx));
            case tx_import_temporal_data:
                ImportTemporalDataTx t = (ImportTemporalDataTx) tx;
                return this.serviceSeq.submit(execute(t), t.bucket);
//            case tx_query_reachable_area:
//                return this.service.submit(execute((ReachableAreaQueryTx) tx));
//            case tx_query_road_earliest_arrive_time_aggr:
//                return this.service.submit(execute((EarliestArriveTimeAggrTx)tx));
//            case tx_query_node_neighbor_road:
//                return this.service.submit(execute((NodeNeighborRoadTx) tx));
            default:
                throw new UnsupportedOperationException();
        }
    }

    @Override
    public void createDB() throws IOException {
        try {
            Connection con = connectionPool.take();
            Statement stmt = con.createStatement();
            con.setAutoCommit(true);
            stmt.execute("CREATE DATABASE beijing_traffic");
            stmt.execute("USE beijing_traffic");
            stmt.execute("CREATE TABLE cross_node ( id int PRIMARY KEY, name char(255) )");
            stmt.execute("CREATE TABLE road ( id int PRIMARY KEY, r_name char(16), r_start int, r_end int, r_length int, r_type int)");
            stmt.execute("CREATE TABLE temporal_status (t int, r_name char(16), rid int, status int, travel_t int, seg_cnt int)");
            stmt.execute("create clustered index tr_index on temporal_status(t, rid)");
            stmt.execute("create index rs_index on road(r_start)");
            stmt.close();
            connectionPool.put(con);
        } catch (SQLException | InterruptedException ex) {
            ex.printStackTrace();
            throw new IOException(ex);
        }
    }

    @Override
    public void restartDB() throws IOException {

    }

    @Override
    public void shutdownDB() throws IOException {

    }

    @Override
    public void close() throws IOException, InterruptedException {
        try {
            service.shutdown();
            while(!service.isTerminated()) {
                service.awaitTermination(10, TimeUnit.SECONDS);
                long completeCnt = exe.getCompletedTaskCount();
                int remains = exe.getQueue().size();
                System.out.println( completeCnt+"/"+ (completeCnt+remains)+" query completed.");
            }
            while(!connectionPool.isEmpty()){
                Connection conn = connectionPool.take();
                conn.close();
            }
            System.out.println("Client exit. send "+ exe.getCompletedTaskCount() +" lines.");
        } catch (SQLException e) {
            e.printStackTrace();
            throw new IOException(e);
        }
    }

    @Override
    public String testServerClientCompatibility() {
        try {
            Connection con = connectionPool.take();
            Statement stmt = con.createStatement();
            con.setAutoCommit(true);
            ResultSet rs = stmt.executeQuery("SELECT CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(128))");
            String result;
            if(rs.next()){
                result = rs.getString(1);
            }else{
                result = "2019-GA-ubuntu-16.04";
            }
            stmt.close();
            connectionPool.put(con);
            return result;
        } catch (InterruptedException | SQLException e) {
            e.printStackTrace();
            throw new UnsupportedOperationException(e);
        }
    }

    private Callable<DBProxy.ServerResponse> execute(ImportStaticDataTx tx){
        return new Req(){
            @Override
            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
                conn.setAutoCommit(false);
                PreparedStatement stat1 = conn.prepareStatement("INSERT INTO cross_node VALUES (?,?)");
                for(ImportStaticDataTx.StaticCrossNode p : tx.getCrosses()){
                    stat1.setInt(1, Math.toIntExact(p.getId()));
                    stat1.setString(2, p.getName());
                    stat1.addBatch();
                }
                stat1.executeBatch();
                stat1.close();
                PreparedStatement stat2 = conn.prepareStatement("INSERT INTO road VALUES (?,?,?,?,?,?)");
                for(ImportStaticDataTx.StaticRoadRel r : tx.getRoads()){
                    stat2.setInt(1, Math.toIntExact(r.getRoadId()));
                    stat2.setString(2, r.getId());
                    stat2.setInt(3, Math.toIntExact(r.getStartCrossId()));
                    stat2.setInt(4, Math.toIntExact(r.getEndCrossId()));
                    stat2.setInt(5, r.getLength());
                    stat2.setInt(6, r.getType());
                    stat2.addBatch();
                }
                stat2.executeBatch();
                stat2.close();
                conn.commit();
                conn.setAutoCommit(true);
//                Statement stat = conn.createStatement();
//                stat.execute("");
                return new AbstractTransaction.Result();
            }
        };
    }

    private Callable<DBProxy.ServerResponse> execute(ImportTemporalDataTx tx){
        return new Req(){
            @Override
            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
                conn.setAutoCommit(false);
                PreparedStatement stat = conn.prepareStatement("INSERT INTO temporal_status VALUES (?,?,?,?,?)");
                for(StatusUpdate s : tx.data){
                    stat.setInt(1, s.getTime());
                    stat.setString(2, s.getRoadId());
                    stat.setInt(3, s.getJamStatus());
                    stat.setInt(4, s.getTravelTime());
                    stat.setInt(5, s.getSegmentCount());
                    stat.addBatch();
                }
                stat.executeBatch();
                stat.close();
                conn.commit();
                conn.setAutoCommit(true);
                return new AbstractTransaction.Result();
            }
        };
    }

//    private int maxTimeLE(Connection con, int rid, int t) throws SQLException {
//        PreparedStatement s = con.prepareStatement("SELECT MAX(t) AS t FROM temporal_status WHERE rid=? AND t<=? GROUP BY rid");
//        s.setInt(1, rid);
//        s.setInt(2, t);
//        ResultSet r = s.executeQuery();
//        if(r.next()){
//            return r.getInt("t");
//        }else{
//            return -1;
//        }
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(UpdateTemporalDataTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                conn.setAutoCommit(false);
//                String sql = String.format("SELECT t, travel_t, status, seg_cnt FROM temporal_status X INNER JOIN " +
//                        "(SELECT rid, MAX(t) mt FROM temporal_status WHERE t<=%d AND rid=%d GROUP BY rid) Y " +
//                        "ON X.rid=Y.rid AND X.t=Y.mt", tx.getEndTime()+1, Math.toIntExact(tx.getRoadId()));
//                Statement s = conn.createStatement();
//                ResultSet r = s.executeQuery(sql);
//                if(r.next()){
//                    if(tx.getEndTime()+1>r.getInt("t")){
//                        s.execute(String.format("INSERT temporal_status(rid, t, travel_t, status, seg_cnt) VALUES(%d, %d, %d, %d, %d)",
//                                tx.getRoadId(), tx.getEndTime()+1, r.getInt("travel_t"), r.getInt("status"), r.getInt("seg_cnt")));
//                    }//else: do nothing
//                }else{
//                    s.execute(String.format("INSERT temporal_status(rid, t) VALUES(%d, %d)", tx.getRoadId(), tx.getEndTime()+1));
//                }
//                int maxT = maxTimeLE(conn, (int) tx.getRoadId(), tx.getStartTime());
//                if(maxT==tx.getStartTime()){
//                    s.execute(String.format("UPDATE temporal_status SET travel_t= %d, status=%d, seg_cnt=%d", tx.getTravelTime(), tx.getJamStatus(), tx.getSegmentCount()));
//                }else{
//                    s.execute(String.format("INSERT temporal_status(rid, t, travel_t, status, seg_cnt) VALUES(%d, %d, %d, %d, %d)",
//                            tx.getRoadId(), tx.getStartTime(), tx.getTravelTime(), tx.getJamStatus(), tx.getSegmentCount()));
//                }
//                s.execute(String.format("DELETE FROM temporal_status WHERE rid=%d AND t>%d AND t<=%d", tx.getRoadId(), maxT, tx.getEndTime()));
//                s.close();
//                conn.commit();
//                conn.setAutoCommit(true);
//                return new AbstractTransaction.Result();
//            }
//        };
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(SnapshotAggrMaxTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                PreparedStatement stat = conn.prepareStatement("SELECT rid, MAX(" + tx.getP() + ") AS mv FROM " +
//                        "temporal_status AS X INNER JOIN (SELECT rid, MAX(t) as ts FROM temporal_status WHERE t<=? GROUP BY rid) AS Y" +
//                        "ON X.rid=Y.rid AND Y.ts<=X.t AND X.t<=" + tx.getT1());
//                ResultSet rs = stat.executeQuery();
//                List<Pair<Long, Integer>> r = new ArrayList<>();
//                while(rs.next()){
//                    int rid = rs.getInt("rid");
//                    int maxVal = rs.getInt("mv");
//                    r.add(Pair.of((long)rid, maxVal));
//                }
//                SnapshotAggrMaxTx.Result result = new SnapshotAggrMaxTx.Result();
//                result.setRoadTravelTime(r);
//                return result;
//            }
//        };
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(ReachableAreaQueryTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                conn.setAutoCommit(true);
//                EarliestArriveTime algo = new EarliestArriveTimeSQL(tx.getStartCrossId(), tx.getDepartureTime(), tx.getTravelTime(), conn);
//                List<EarliestArriveTime.NodeCross> answer = new ArrayList<>(algo.run());
//                answer.sort(Comparator.comparingLong(EarliestArriveTime.NodeCross::getId));
//                ReachableAreaQueryTx.Result result = new ReachableAreaQueryTx.Result();
//                result.setNodeArriveTime(answer);
//                metrics.setReturnSize(answer.size());
//                return result;
//            }
//        };
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(SnapshotQueryTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                conn.setAutoCommit(true);
//                int snapshotTime = tx.getTimestamp();
//                Statement st = conn.createStatement();
//                String sql = "SELECT rid, t, status, travel_t, seg_cnt FROM temporal_status AS Y INNER JOIN (" +
//                        "SELECT rid, MAX(t) AS t FROM temporal_status WHERE t <= " + snapshotTime + " GROUP BY rid) AS X ON X.rid=Y.rid AND X.t=Y.t";
//                //SELECT rid, MIN(t-snapshotTime), status, travel_t, seg_cnt FROM temporal_status WHERE rowId IN
//                // (SELECT rowId FROM temporal_status WHERE t < snapshotTime) GROUP BY rid";
//                ResultSet res =  st.executeQuery(sql);
//                List<Pair<String, Integer>> r = new ArrayList<>();
//                while (res.next()){
//                    long rid = res.getInt("rid");
//                    int val = res.getInt(tx.getPropertyName());
////                    r.add(Pair.of(rid, val)); TODO:需要修改name
//                }
//                SnapshotQueryTx.Result result = new SnapshotQueryTx.Result();
//                result.setRoadStatus(r);
//                return result;
//            }
//        };
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(EntityTemporalConditionTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                PreparedStatement stat = conn.prepareStatement("SELECT rid, MAX(t) as ts FROM temporal_status WHERE t<="+tx.getT0()+" GROUP BY rid");
//                PreparedStatement stat0 = conn.prepareStatement("SELECT rid FROM temporal_status WHERE rid=? AND t>=? AND t<=? AND travel_t>=? AND travel_t<=? LIMIT 1");
//                ResultSet rs = stat.executeQuery();
//                List<Long> r = new ArrayList<>();
//                while(rs.next()){
//                    int rid = rs.getInt("rid");
//                    int ts = rs.getInt("ts");
//                    stat0.setInt(1, rid);
//                    stat0.setInt(2, ts);
//                    stat0.setInt(3, tx.getT1());
//                    stat0.setInt(4, tx.getVmin());
//                    stat0.setInt(5, tx.getVmax());
//                    ResultSet dup = stat0.executeQuery();
//                    if(dup.next()){
//                        r.add((long) rid);
//                    }
//                }
//                EntityTemporalConditionTx.Result result = new EntityTemporalConditionTx.Result();
//                result.setRoads(r);
//                return result;
//            }
//        };
//    }
//
//    private Callable<DBProxy.ServerResponse> execute(SnapshotAggrDurationTx tx){
//        return new Req(){
//            @Override
//            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
//                PreparedStatement stat = conn.prepareStatement("SELECT rid, MAX(t) as ts FROM temporal_status WHERE t<="+tx.getT0()+" GROUP BY rid");
//                PreparedStatement stat0 = conn.prepareStatement("SELECT rid, t, status FROM temporal_status WHERE rid=? AND t>? AND t<? ORDER BY t");
//                ResultSet rs = stat.executeQuery();
//                List<Triple<Long, Integer, Integer>> r = new ArrayList<>();
//                while(rs.next()){
//                    int rid = rs.getInt("rid");
//                    int ts = rs.getInt("ts");
//                    stat0.setInt(1, rid);
//                    stat0.setInt(2, ts);
//                    stat0.setInt(3, tx.getT1());
//                    ResultSet dup = stat0.executeQuery();
//                    int lastTime = -1;
//                    int lastStatus = -1;
//                    Map<Integer, Integer> duration = new HashMap<>();
//                    while(dup.next()){
//                        int t = rs.getInt("t");
//                        int status = rs.getInt("status");
//                        if(lastTime!=-1){
//                            int dur = t - lastTime;
//                            duration.merge(lastStatus, dur, Integer::sum);
//                        }
//                        lastTime = t;
//                        lastStatus = status;
//                    }
//                    for(Map.Entry<Integer, Integer> e : duration.entrySet()){
//                        r.add(Triple.of((long)rid, e.getKey(), e.getValue()));
//                    }
//                }
//                SnapshotAggrDurationTx.Result result = new SnapshotAggrDurationTx.Result();
//                result.setRoadStatDuration(r);
//                return result;
//            }
//        };
//    }

    private abstract class Req implements Callable<DBProxy.ServerResponse>{
        private final TimeMonitor timeMonitor = new TimeMonitor();
        final AbstractTransaction.Metrics metrics = new AbstractTransaction.Metrics();
        private Req(){
            timeMonitor.begin("Wait in queue");
        }

        @Override
        public DBProxy.ServerResponse call() throws Exception {
            try {
                Connection conn = connectionPool.take();
                timeMonitor.mark("Wait in queue", "query");
                AbstractTransaction.Result result = executeQuery(conn);
                timeMonitor.end("query");
                if (result == null) throw new RuntimeException("[Got null. Server close connection]");
                connectionPool.put(conn);
                metrics.setWaitTime(Math.toIntExact(timeMonitor.duration("Wait in queue")));
                metrics.setSendTime(timeMonitor.beginT("query"));
                metrics.setExeTime(Math.toIntExact(timeMonitor.duration("query")));
                metrics.setConnId(-1);
                ServerResponse response = new ServerResponse();
                response.setMetrics(metrics);
                response.setResult(result);
                return response;
            }catch (Exception e){
                e.printStackTrace();
                throw e;
            }
        }

        protected abstract AbstractTransaction.Result executeQuery(Connection conn) throws Exception;
    }

}
