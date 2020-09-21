package simple.tgraph.kernel;

import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.errors.ProducerException;
import edu.buaa.benchmark.BenchmarkTxResultProcessor;
import edu.buaa.benchmark.client.DBProxy;
import edu.buaa.benchmark.client.TGraphExecutorClient;
import edu.buaa.benchmark.transaction.SnapshotAggrDurationTx;
import edu.buaa.utils.Helper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class SnapshotAggregationDurationTest {
    private static int threadCnt = Integer.parseInt(Helper.mustEnv("MAX_CONNECTION_CNT")); // number of threads to send queries.
    private static String serverHost = Helper.mustEnv("DB_HOST"); // hostname of TGraph (TCypher) server.
    private static boolean verifyResult = Boolean.parseBoolean(Helper.mustEnv("VERIFY_RESULT"));
    private static String resultFile = Helper.mustEnv("SERVER_RESULT_FILE");

    private static Producer logger;
    private static DBProxy client;
    private static BenchmarkTxResultProcessor post;

    @BeforeClass
    public static void init() throws IOException, ExecutionException, InterruptedException {
        client = new TGraphExecutorClient(serverHost, threadCnt, 800);
        client.testServerClientCompatibility();

        post = new BenchmarkTxResultProcessor("TGraph(SnapshotAggregationDurationTest)", Helper.codeGitVersion());
        logger = Helper.getLogger();
        post.setLogger(logger);
        post.setVerifyResult(verifyResult);
        post.setResult(new File(resultFile));
    }

    @Test
    public void jam_statusInfo() throws Exception{
        query("jam_status", Helper.timeStr2int("201006300830"), Helper.timeStr2int("201006300930"));
    }

    private void query(String propertyName, int st, int et) throws Exception {
        SnapshotAggrDurationTx tx = new SnapshotAggrDurationTx();
        tx.setP(propertyName);
        tx.setT0(st);
        tx.setT1(et);
        post.process(client.execute(tx), tx);
    }
    @AfterClass
    public static void close() throws IOException, InterruptedException, ProducerException {
        post.close();
        client.close();
        logger.close();
    }

}
