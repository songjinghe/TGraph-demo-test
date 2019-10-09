package run;

import com.aliyun.openservices.aliyun.log.producer.errors.ProducerException;
import org.act.temporalProperty.impl.InternalEntry;
import org.act.temporalProperty.meta.ValueContentType;
import org.act.tgraph.demo.Config;
import org.act.tgraph.demo.utils.TCypherClient;
import org.act.tgraph.demo.vo.RuntimeEnv;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.temporal.TemporalRangeQuery;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 *  create by sjh at 2019-09-10
 *
 *  Test TGraph Server TCypher 'property set' performance.
 */

@RunWith(Parameterized.class)
public class TCypherWriteTemporalPropertyTest {
    private int threadCnt; // number of threads to send queries.
    private int queryPerTx; // number of TCypher queries executed in one transaction.
    private String serverHost; // hostname of TGraph (TCypher) server.
    private String dataFilePath; // should be like '/media/song/test/data-set/beijing-traffic/TGraph/byday/100501'
    private long totalDataSize; // number of lines to read from data file.

    public TCypherWriteTemporalPropertyTest(int threadCnt, int queryPerTx, String serverHost, String dataFilePath, long totalDataSize){
        this.threadCnt = threadCnt;
        this.queryPerTx = queryPerTx;
        this.serverHost = serverHost;
        this.dataFilePath = dataFilePath;
        this.totalDataSize = totalDataSize;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() throws ParseException {
        Config conf = RuntimeEnv.getCurrentEnv().getConf();
        System.out.println("current runtime env: "+RuntimeEnv.getCurrentEnv().name());

        String serverHost = conf.get("server_host").asString();
        int totalDataSize = 60_0000;
        String dataFileDir = conf.get("dir_data_file_by_day").asString();

        return Arrays.asList(new Object[][] {
                { 10, 100, serverHost, getDataFilePath(dataFileDir, "2010.05.01"), totalDataSize }
        });
    }

    @Test
    public void run() throws InterruptedException, ParseException, ProducerException, IOException {
        System.out.println("Host: "+ serverHost);
        System.out.println("Thread Num: "+threadCnt);
        System.out.println("Q/Tx: "+queryPerTx);
        System.out.println("Total line send: "+totalDataSize);
        System.out.println("Data path: "+ dataFilePath);
        runTest(serverHost, threadCnt, dataFilePath, queryPerTx, totalDataSize);
    }

    public static void main(String[] args){
        if(args.length<6){
            System.out.println("need valid params.");
            return;
        }
        String serverHost = args[0];
        int threadCnt = Integer.parseInt(args[1]);
        int queryPerTx = Integer.parseInt(args[2]);
        int totalDataSize = Integer.parseInt(args[3]);
        String dataFilePath = args[4];

        System.out.println("Host: "+ serverHost);
        System.out.println("Thread Num: "+threadCnt);
        System.out.println("Q/Tx: "+queryPerTx);
        System.out.println("Total line send: "+totalDataSize);
        System.out.println("Data path: "+ dataFilePath);
        try {
            runTest(serverHost, threadCnt, dataFilePath, queryPerTx, totalDataSize);
        } catch (IOException | ParseException | InterruptedException | ProducerException e) {
            e.printStackTrace();
        }
    }

    private static void runTest(String serverHost, int threadCnt, String dataFilePath, int queryPerTx, long totalDataSize) throws IOException, InterruptedException, ProducerException, ParseException {
        String logSource = RuntimeEnv.getCurrentEnv().name();
        TCypherClient client = new TCypherClient("cs-write-T-prop", logSource, serverHost, threadCnt, 2000, true);
        Map<String, Long> roadMap = client.start();

        String dataFileName = new File(dataFilePath).getName(); // also is time by day. format yyMMdd

        long lineSendCnt = 0;
        try(BufferedReader br = new BufferedReader(new FileReader(dataFilePath)))
        {
            String s;
            List<String> dataInOneTx = new LinkedList<>();
            do{
                s = br.readLine();
                if(s!=null) {
                    lineSendCnt++;
                    dataInOneTx.add(s);
                    if (dataInOneTx.size() == queryPerTx) {
                        client.addQuery(dataLines2tCypher( dataFileName, dataInOneTx, roadMap));
                        dataInOneTx.clear();
                    }
                }else{
                    client.addQuery(dataLines2tCypher(dataFileName, dataInOneTx, roadMap));
                    dataInOneTx.clear();
                }
            }
            while (lineSendCnt < totalDataSize && s!=null);
        }
        client.awaitSendDone();
    }

    private static String dataLines2tCypher(String dataFileName, List<String> lines, Map<String, Long> roadMap) throws ParseException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String[] arr = line.split(":");
            int time = parseTime(dataFileName, arr[0]);
            String[] d = arr[2].split(",");
            String q = "MATCH ()-[r:ROAD_TO]->() WHERE id(r)={0} SET " +
                    "r.travel_time=TV({1}~NOW:{2}), " +
                    "r.full_status=TV({1}~NOW:{3}), " +
                    "r.vehicle_count=TV({1}~NOW:{4}), " +
                    "r.segment_count=TV({1}~NOW:{5});";
            String qq = MessageFormat.format(q, String.valueOf(roadMap.get(arr[1])), String.valueOf(time), d[0], d[1], d[2], d[3]);
            sb.append(qq);
        }
        return sb.substring(0, sb.length()-1);
    }

    private static SimpleDateFormat timeParser = new SimpleDateFormat("yyyyMMddHHmm");
    private static int parseTime(String yearMonthDay, String hourAndMinute) throws ParseException {
        return Math.toIntExact(timeParser.parse("20"+yearMonthDay+hourAndMinute).getTime()/1000);
    }

    private static String getDataFilePath(String dataFileDir, String day) throws ParseException {
        return new SimpleDateFormat("MMdd").format(new SimpleDateFormat("yyyy.MM.dd").parse(day));
    }


//    @Test
//    public void tCypherTest(){
////        System.out.println(System.getProperty("java.vm.name"));
////        System.out.println(System.getProperty("java.vm.info"));
////        System.exit(0);
//        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase(new File("/media/song/test/db-network-only-ro"));
//        Runtime.getRuntime().addShutdownHook(new Thread(db::shutdown));
//        long t = System.currentTimeMillis();
////        try (Transaction tx = db.beginTx()) {
////            db.getRelationshipById(1).setTemporalProperty("travel_time", 0, 2L);
////            tx.success();
////        }
//        try (Transaction tx = db.beginTx()) {
////            for(int i=0; i<10; i++) {
////                System.out.println(db.execute("MATCH ()-[r:ROAD_TO]->() WHERE id(r)=1 SET r.travel_time_100"+i+"="+(30+i)).resultAsString());
//                db.execute("MATCH ()-[r:ROAD_TO]->() WHERE id(r)=1 SET r.travel_time=TV(3~13:30, 100~NOW:2)");
////            }
//            tx.success();
//        }
//        System.out.println(System.currentTimeMillis() - t);
//        try (Transaction tx = db.beginTx()) {
//            for(int i=0; i<102; i++) {
//                System.out.println(db.getRelationshipById(1).getTemporalProperty("travel_time", i));
////                System.out.println(db.execute("MATCH ()-[r:ROAD_TO]->() WHERE r.id=1 SET r.travel_time_100="+(30+i)).resultAsString());
//            }
////            System.out.println(db.execute("MATCH ()-[r:ROAD_TO]->() WHERE r.id=1 SET r.travel_time=TV(100~NOW:30)").resultAsString());
//            tx.success();
//        }
//        System.out.println(System.currentTimeMillis() - t);
//        try(Transaction tx = db.beginTx()){
//            Relationship r = db.getRelationshipById(1);
////            for(String key : r.getPropertyKeys()){
////                System.out.println(key+": "+r.getProperty(key));
////            }
////            r.setTemporalProperty("travel_time", 400, 88);
//            r.getTemporalProperty("travel_time", 0, Integer.MAX_VALUE-4, new TemporalRangeQuery() {
//                @Override
//                public void setValueType(ValueContentType valueType) {
//                    System.out.println(valueType);
//                }
//
//                @Override
//                public void onNewEntry(InternalEntry entry) {
//                    System.out.print(entry.getKey().getStartTime()+":["+entry.getKey().getValueType()+"]"+entry.getValue().toString());
//                }
//
//                @Override
//                public Object onReturn() {
//                    return null;
//                }
//            });
//            tx.success();
//        }
//        System.out.println(System.currentTimeMillis() - t);
//    }
}
