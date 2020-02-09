package edu.buaa.benchmark.client;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import edu.buaa.algo.EarliestArriveTime;
import edu.buaa.benchmark.transaction.*;
import edu.buaa.utils.TimeMonitor;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SqlServerExecutorClient implements DBProxy {
    private ThreadPoolExecutor exe;
    private BlockingQueue<Connection> connectionPool = new LinkedBlockingQueue<>();
    private ListeningExecutorService service;
    private Map<Connection, Integer> connIdMap = new HashMap<>();

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
        this.service = MoreExecutors.listeningDecorator(exe);
        for(int i = 0; i< parallelCnt; i++) {
            Connection conn = DriverManager.getConnection(dbURL, "sa", "SQLServer2019@Benchmark");
            this.connectionPool.offer(conn);
            connIdMap.put(conn, i);
        }
    }

    @Override
    public ListenableFuture<DBProxy.ServerResponse> execute(AbstractTransaction tx) {
        switch (tx.getTxType()){
            case tx_import_static_data:
                return this.service.submit(execute((ImportStaticDataTx) tx));
            case tx_import_temporal_data:
                return this.service.submit(execute((ImportTemporalDataTx) tx));
            case tx_query_reachable_area:
                return this.service.submit(execute((ReachableAreaQueryTx) tx));
            case tx_query_road_earliest_arrive_time_aggr:
                return this.service.submit(execute((EarliestArriveTimeAggrTx)tx));
            case tx_query_node_neighbor_road:
                return this.service.submit(execute((NodeNeighborRoadTx) tx));
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
//            stmt.execute("CREATE DATABASE beijing_traffic");
            stmt.execute("USE beijing_traffic");
//            stmt.execute("CREATE TABLE cross_node ( id int PRIMARY KEY, name char(255) )");
//            stmt.execute("CREATE TABLE road ( id int PRIMARY KEY, r_name char(16), r_start int, r_end int, r_length int, r_type int)");
//            stmt.execute("CREATE TABLE temporal_status (t int, rid int, status int, travel_t int, seg_cnt int)");
//            stmt.execute("create clustered index tr_index on temporal_status(t, rid)");
//            stmt.execute("create index rs_index on road(r_start)");
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
                for(ImportTemporalDataTx.StatusUpdate s : tx.data){
                    stat.setInt(1, s.getTime());
                    stat.setInt(2, Math.toIntExact(s.getRoadId()));
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

    private Callable<DBProxy.ServerResponse> execute(ReachableAreaQueryTx tx){
        return new Req(){
            @Override
            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
                conn.setAutoCommit(true);
                EarliestArriveTime algo = new EarliestArriveTimeSQL(tx.getStartCrossId(), tx.getDepartureTime(), tx.getTravelTime(), conn);
                List<EarliestArriveTime.NodeCross> answer = new ArrayList<>(algo.run());
                answer.sort(Comparator.comparingLong(EarliestArriveTime.NodeCross::getId));
                ReachableAreaQueryTx.Result result = new ReachableAreaQueryTx.Result();
                result.setNodeArriveTime(answer);
                metrics.setReturnSize(answer.size());
                return result;
            }
        };
    }

    private Callable<DBProxy.ServerResponse> execute(NodeNeighborRoadTx tx){
        return new Req(){
            @Override
            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
                conn.setAutoCommit(true);
                NodeNeighborRoadSQL algo = new NodeNeighborRoadSQL(conn);
                List<Long> answer = algo.getNeighborRoad(tx.getNodeId());
                NodeNeighborRoadTx.Result result = new NodeNeighborRoadTx.Result();
                result.setRoadIds(answer);
                metrics.setReturnSize(answer.size());
                return result;
            }
        };
    }

    private Callable<DBProxy.ServerResponse> execute(EarliestArriveTimeAggrTx tx){
        return new Req(){
            @Override
            protected AbstractTransaction.Result executeQuery(Connection conn) throws Exception{
                conn.setAutoCommit(true);
                RoadEarliestArriveTimeSQL algo = new RoadEarliestArriveTimeSQL(conn);
                try {
                    return new EarliestArriveTimeAggrTx.Result(algo.getEarliestArriveTime(tx.getRoadId(), tx.getDepartureTime(), tx.getEndTime()));
                }catch(UnsupportedOperationException e){
                    return new EarliestArriveTimeAggrTx.Result(-1);
                }
            }
        };
    }

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
                metrics.setConnId(connIdMap.get(conn));
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

    private static class EarliestArriveTimeSQL extends EarliestArriveTime {
        private final PreparedStatement getEndNodeIdStat;
        private final NodeNeighborRoadSQL nodeNeighborRoadSQL;
        private final RoadEarliestArriveTimeSQL earliestTime;

        EarliestArriveTimeSQL(long startId, int startTime, int travelTime, Connection conn) throws SQLException {
            super(startId, startTime, travelTime);
            this.getEndNodeIdStat = conn.prepareStatement("SELECT r_end FROM road WHERE id=?");
            this.nodeNeighborRoadSQL = new NodeNeighborRoadSQL(conn);
            this.earliestTime = new RoadEarliestArriveTimeSQL(conn);
        }

        @Override
        protected int getEarliestArriveTime(Long roadId, int departureTime) throws UnsupportedOperationException {
            return earliestTime.getEarliestArriveTime(roadId, departureTime, endTime);
        }

        @Override
        protected Iterable<Long> getAllOutRoads(long nodeId) {
            try {
                return nodeNeighborRoadSQL.getNeighborRoad(nodeId);
            } catch (SQLException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        @Override
        protected long getEndNodeId(long roadId) {
            try {
                this.getEndNodeIdStat.setInt(1, Math.toIntExact(roadId));
                ResultSet rs = this.getEndNodeIdStat.executeQuery();
                if(rs.next()){
                    return rs.getInt("r_end");
                }else{
                    throw new RuntimeException("road not found, should not happen!");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private static class NodeNeighborRoadSQL{
        private final PreparedStatement getCrossOutRoadStat;
        private NodeNeighborRoadSQL(Connection conn) throws SQLException {
            this.getCrossOutRoadStat = conn.prepareStatement("SELECT id FROM road WHERE r_start=?");
        }

        public List<Long> getNeighborRoad(long nodeId) throws SQLException {
            this.getCrossOutRoadStat.setInt(1, Math.toIntExact(nodeId));
            ResultSet rs = this.getCrossOutRoadStat.executeQuery();
            List<Long> result = new ArrayList<>();
            while(rs.next()){
                result.add((long) rs.getInt("id"));
            }
            return result;
        }
    }

    private static class RoadEarliestArriveTimeSQL{
        private final PreparedStatement getStartTStat;
        private final PreparedStatement getTemporalStat;

        RoadEarliestArriveTimeSQL(Connection con) throws SQLException {
            this.getStartTStat = con.prepareStatement("SELECT MAX(t) as ts FROM temporal_status WHERE rid=? AND t<=?");// top 1. limit is not support in sql server
            this.getTemporalStat = con.prepareStatement("SELECT t, travel_t FROM temporal_status WHERE rid=? AND t>=? AND t<=?");
        }

        protected int getEarliestArriveTime(long roadId, int departureTime, int endTime) throws UnsupportedOperationException {
            try {
                int startT = maxTimeLessOrEq(roadId, departureTime);
                ResultSet rs = getTT(roadId, startT, endTime);
                int minArriveTime = Integer.MAX_VALUE;
                int curT = departureTime;
                while(rs.next() && curT<minArriveTime){
                    curT = rs.getInt("t");
                    int travelT = rs.getInt("travel_t");
                    if(curT<departureTime){
                        minArriveTime = departureTime+travelT;
                    }else if(curT+travelT<minArriveTime){
                        minArriveTime = curT+travelT;
                    }
                }
                if(minArriveTime!=Integer.MAX_VALUE) return minArriveTime;
                else throw new UnsupportedOperationException();
            } catch (SQLException e) {
                e.printStackTrace();
                throw new UnsupportedOperationException(e);
            }
        }

        private int maxTimeLessOrEq(long roadId, int time) throws SQLException {
            this.getStartTStat.setInt(1, Math.toIntExact(roadId));
            this.getStartTStat.setInt(2, time);
            ResultSet rs = this.getStartTStat.executeQuery();
            if(rs.next()){
                Object resultObj = rs.getObject("ts");
                if(resultObj!=null) return (int) resultObj;
                else throw new UnsupportedOperationException();
            }else{
                throw new UnsupportedOperationException();
            }
        }

        private ResultSet getTT(long roadId, int start, int end) throws SQLException {
            this.getTemporalStat.setInt(1, Math.toIntExact(roadId));
            this.getTemporalStat.setInt(2, start);
            this.getTemporalStat.setInt(3, end);
            return this.getTemporalStat.executeQuery();
        }

    }
}
