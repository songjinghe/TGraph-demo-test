package edu.buaa.benchmark;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.AbstractIterator;
import edu.buaa.benchmark.transaction.AbstractTransaction;
import edu.buaa.utils.Helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

public class BenchmarkReader extends AbstractIterator<AbstractTransaction> {

    private final BufferedReader reader;

    public BenchmarkReader(File file) throws IOException {
        reader = Helper.gzipReader(file);
    }

    @Override
    protected AbstractTransaction computeNext() {
        try {
            String line = reader.readLine();
            if(line==null) return endOfData();
            return JSONObject.parseObject(line, AbstractTransaction.class);
        } catch (IOException e) {
            e.printStackTrace();
            return endOfData();
        }
    }

    public void close() throws IOException {
        reader.close();
    }
}
