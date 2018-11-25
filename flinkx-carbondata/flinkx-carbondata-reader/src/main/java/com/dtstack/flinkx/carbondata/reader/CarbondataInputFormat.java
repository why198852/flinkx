package com.dtstack.flinkx.carbondata.reader;


import com.dtstack.flinkx.inputformat.RichInputFormat;
import com.dtstack.flinkx.util.StringUtil;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.scan.expression.Expression;
import org.apache.carbondata.hadoop.CarbonInputSplit;
import org.apache.carbondata.hadoop.CarbonProjection;
import org.apache.carbondata.hadoop.api.CarbonTableInputFormat;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;



public class CarbondataInputFormat extends RichInputFormat{

    protected Map<String,String> hadoopConfig;

    protected String table;

    protected String database;

    protected String path;

    protected List<String> columnValue;

    protected List<String> columnType;

    protected List<String> columnName;

    protected List<String> columnFormat;

    protected String filter;

    private List<Integer> columnIndex;


    private transient Job job;

    private List<CarbonInputSplit> carbonInputSplits;

    private int pos = 0;

    private transient RecordReader recordReader;

    private transient CarbonTableInputFormat format;

    private transient TaskAttemptContext taskAttemptContext;

    private transient CarbonProjection  projection;


    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        CarbonFlinkInputSplit carbonFlinkInputSplit = (CarbonFlinkInputSplit) inputSplit;
        carbonInputSplits = carbonFlinkInputSplit.getCarbonInputSplits();
        taskAttemptContext = createTaskContext();
        try {
            recordReader = createRecordReader(pos);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    private void initColumnIndices() {
        projection = new CarbonProjection();
        columnIndex = new ArrayList<>();
        int k = 0;
        for(int i = 0; i < columnName.size(); ++i) {
            if(StringUtils.isNotBlank(columnName.get(i))) {
                columnIndex.add(k);
                projection.addColumn(columnName.get(i));
                k++;
            } else {
                columnIndex.add(null);
            }
        }
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        try {
            row = new Row(columnIndex.size());

            Object[] record = (Object[]) recordReader.getCurrentValue();
            for(int i = 0; i < columnIndex.size(); ++i) {
                if(columnIndex == null) {
                    row.setField(i, StringUtil.string2col(columnValue.get(i), columnType.get(i)));
                } else {
                    row.setField(i, record[columnIndex.get(i)]);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    @Override
    protected void closeInternal() throws IOException {
        if(recordReader != null) {
            recordReader.close();
        }
    }

    @Override
    public void configure(Configuration configuration) {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        conf.clear();
        if(hadoopConfig != null) {
            for (Map.Entry<String, String> entry : hadoopConfig.entrySet()) {
                conf.set(entry.getKey(), entry.getValue());
            }
        }
        conf.set("fs.hdfs.impl.disable.cache", "true");
        conf.set("fs.default.name", "hdfs://ns1");

        try {
            Field confField = FileFactory.class.getDeclaredField("configuration");
            confField.setAccessible(true);
            confField.set(null, conf);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        initColumnIndices();

        CarbonTableInputFormat.setDatabaseName(conf, database);
        CarbonTableInputFormat.setTableName(conf, table);
        CarbonTableInputFormat.setColumnProjection(conf, projection);
        conf.set("mapreduce.input.fileinputformat.inputdir", path);

        if(StringUtils.isNotBlank(filter)) {
            CarbonTableInputFormat.setFilterPredicates(conf, CarbonExpressUtil.eval(filter, columnName, columnType));
        }

        try {
            job = Job.getInstance(conf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        format = new CarbonTableInputFormat();

    }

    @Override
    public InputSplit[] createInputSplits(int num) throws IOException {
        List<org.apache.hadoop.mapreduce.InputSplit> splitList = format.getSplits(job);
        int splitNum = (splitList.size() < num ? splitList.size() : num);
        int groupSize = (int)Math.ceil(splitList.size() / (double)splitNum);
        InputSplit[] ret = new InputSplit[splitNum];

        for(int i = 0; i < splitNum; ++i) {
            List<CarbonInputSplit> carbonInputSplits = new ArrayList<>();
            for(int j = 0; j < groupSize && i*groupSize+j < splitList.size(); ++j) {
                carbonInputSplits.add((CarbonInputSplit) splitList.get(i*groupSize+j));
            }
            ret[i] = new CarbonFlinkInputSplit(carbonInputSplits, i);
        }

        return ret;
    }


    @Override
    public boolean reachedEnd() throws IOException {
        try {
            while(!recordReader.nextKeyValue()) {
                pos++;
                if(pos == carbonInputSplits.size()) {
                    return true;
                }
                recordReader.close();
                recordReader = createRecordReader(pos);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private TaskAttemptContext createTaskContext() {
        Random random = new Random();
        JobID jobId = new JobID(UUID.randomUUID().toString(), 0);
        TaskID task = new TaskID(jobId, TaskType.MAP, random.nextInt());
        TaskAttemptID attemptID = new TaskAttemptID(task, random.nextInt());
        TaskAttemptContextImpl context = new TaskAttemptContextImpl(job.getConfiguration(), attemptID);
        return context;
    }

    private RecordReader createRecordReader(int pos) throws IOException, InterruptedException {
        CarbonInputSplit carbonInputSplit = carbonInputSplits.get(pos);
        RecordReader recordReader = format.createRecordReader(carbonInputSplit, taskAttemptContext);
        recordReader.initialize(carbonInputSplit, taskAttemptContext);
        return recordReader;
    }

}
