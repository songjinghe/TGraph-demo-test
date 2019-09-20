package org.act.tgraph.demo.utils;


import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.errors.ProducerException;
import com.aliyun.openservices.log.common.LogItem;
import com.sun.management.OperatingSystemMXBean;
import org.act.tgraph.demo.Config;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class TCypherServer {
    private final String dbPath;
    private final String SEPARATOR;

    private final Producer logger;
    private GraphDatabaseService db;
    private volatile boolean shouldRun = true;
    private ServerSocket server;
    private String testTopic;
    private final List<Thread> threads = Collections.synchronizedList(new LinkedList<>());
    private final String serverCodeVersion;

    public static void main(String[] args){
        TCypherServer server = new TCypherServer("AMITABHA", Config.Default.onlineLogger, args[0]);
//        TCypherServer server = new TCypherServer("AMITABHA", Config.Default.onlineLogger, "/media/song/test/db-network-only");
        try {
            server.start();
        } catch (IOException | InterruptedException | ProducerException e) {
            e.printStackTrace();
        }
    }

    public TCypherServer(String separator, Producer logger, String dbPath) {
        this.SEPARATOR = separator;
        this.logger = logger;
        this.dbPath = dbPath;
        serverCodeVersion = Config.Default.gitStatus;
    }




    public void start() throws IOException, InterruptedException, ProducerException {
        db = new GraphDatabaseFactory().newEmbeddedDatabase( new File(dbPath));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            db.shutdown();
//            try {
//                logger.close();
//            } catch (InterruptedException | ProducerException e) {
//                e.printStackTrace();
//            }
        }));
        new MonitorThread().start();

        server = new ServerSocket(8438);
        System.out.println("waiting for client to connect.");

        while(shouldRun) {
            Socket client;
            try {
                client = server.accept();
            }catch (SocketException ignore){ // closed from another thread.
                break;
            }
            Thread t = new ServerThread(client);
            threads.add(t);
            System.out.println("GET one more client, currently "+threads.size()+" client");
            t.setDaemon(true);
            t.start();
        }
        for(Thread t : threads){
            t.join();
        }
        db.shutdown();
        logger.close();
        System.out.println("main thread exit.");
    }


    private class Req implements Runnable{
        String[] queries;
        String[] results = new String[0];
        int resultSize;
        boolean success=true;

        @Override
        public void run() {
            results = new String[queries.length];
            try {
                try (Transaction tx = db.beginTx()) {
                    for (int i = 0; i < queries.length; i++) {
                        String query = queries[i];
                        Result result = db.execute(query);
                        results[i] = result.resultAsString().replace("\n", "\\n");
                        resultSize += results[i].length();
                    }
                    tx.success();
                }
            }catch (Exception msg){
                msg.printStackTrace();
                success = false;
            }
        }
    }

    private class MonitorThread extends Thread{
        public void run(){
            final OperatingSystemMXBean bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            Runtime runtime = Runtime.getRuntime();
            try {
                while(shouldRun){
                    Thread.sleep(1_000);
                    int activeConn = threads.size();
                    if(activeConn==0) continue;
                    long curMem = runtime.totalMemory() - runtime.freeMemory();
                    double processCpuLoad = bean.getProcessCpuLoad();
                    double systemCpuLoad = bean.getSystemCpuLoad();
                    LogItem log = new LogItem();
                    log.PushBack("timestamp", String.valueOf(System.currentTimeMillis()));
                    log.PushBack("vm_memory", String.valueOf(curMem));
                    log.PushBack("thread_cnt", String.valueOf(activeConn));
                    if(!(processCpuLoad<0)) log.PushBack("process_load", String.valueOf(processCpuLoad));
                    if(!(systemCpuLoad<0)) log.PushBack("system_load", String.valueOf(systemCpuLoad));
                    logger.send("tgraph-demo-test", "tgraph-log", testTopic, "sjh-ubuntu1804", log);
                }
            } catch (InterruptedException | ProducerException e) {
                e.printStackTrace();
            }
        }
    }

    private class ServerThread extends Thread{
        Socket client;
        BufferedReader fromClient;
        PrintStream toClient;
        long reqCnt = 0;

        ServerThread(Socket client) throws IOException {
            this.client = client;
            client.setTcpNoDelay(true);
            this.fromClient = new BufferedReader(new InputStreamReader(client.getInputStream()));
            this.toClient = new PrintStream(client.getOutputStream(), true);
        }

        public void run(){
            long tid = Thread.currentThread().getId();
            Thread.currentThread().setName("TCypher con "+tid);
            System.out.println(Thread.currentThread().getName()+" started.");
            TimeMonitor time = new TimeMonitor();
            time.begin("Send");
            try {
                while(true){
                    time.mark("Send", "Wait");
                    long previousSendTime = time.duration("Send");
                    String line;
                    try {
                        line = fromClient.readLine();
                    }catch (SocketException ignore){// client close conn.
                        System.out.println("client close connection.");
                        client.close();
                        break;
                    }
                    if(line==null){
                        System.out.println("client close connection. read end.");
                        client.close();
                        break;
                    }else if("EXIT".equals(line)){ //client ask server exit;
                        client.close();
                        server.close();
                        shouldRun = false;
                        System.out.println("client ask server exit.");
                        break;
                    }else if("GC".equals(line)){
                        Runtime.getRuntime().gc();
                        System.out.println("client ask server gc.");
                        continue;
                    }else if(line.startsWith("TOPIC:")){
                        testTopic = line.substring(6)+"-"+serverCodeVersion;
                        System.out.println("topic changed to "+testTopic);
                        toClient.println("GOT");
                        continue;
                    }
                    time.mark("Wait", "Transaction");
                    Req req = new Req();
                    req.queries = line.split(";");
                    req.run();
                    time.mark("Transaction", "Send");
                    toClient.println(
                            req.success + SEPARATOR + req.resultSize + SEPARATOR +
                                    time.endT("Wait") + SEPARATOR +
                                    previousSendTime + SEPARATOR +
                                    time.duration("Transaction") + SEPARATOR +
                                    String.join(SEPARATOR, req.results)
                    );
                    reqCnt++;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            threads.remove(this);
            System.out.println(Thread.currentThread().getName()+" exit. process "+reqCnt+" queries.");
        }
    }

}