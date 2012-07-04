package com.thinkaurelius.faunus.io.formats.titan;

import com.thinkaurelius.faunus.io.graph.FaunusVertex;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.hadoop.ColumnFamilyInputFormat;
import org.apache.cassandra.hadoop.ColumnFamilySplit;
import org.apache.cassandra.hadoop.ConfigHelper;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.TokenRange;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Hadoop InputFormat allowing map/reduce against Cassandra rows within one ColumnFamily.
 * <p/>
 * At minimum, you need to set the CF and predicate (description of columns to extract from each row)
 * in your Hadoop job Configuration.  The ConfigHelper class is provided to make this
 * simple:
 * ConfigHelper.setColumnFamily
 * ConfigHelper.setSlicePredicate
 * <p/>
 * You can also configure the number of rows per InputSplit with
 * ConfigHelper.setInputSplitSize
 * This should be "as big as possible, but no bigger."  Each InputSplit is read from Cassandra
 * with multiple get_slice_range queries, and the per-call overhead of get_slice_range is high,
 * so larger split sizes are better -- but if it is too large, you will run out of memory.
 * <p/>
 * The default split size is 64k rows.
 */
public class TitanCassandraInputFormat extends InputFormat<NullWritable, FaunusVertex>

{
    private static final Logger logger = LoggerFactory.getLogger(ColumnFamilyInputFormat.class);

    public static final String MAPRED_TASK_ID = "mapred.task.id";
    // The simple fact that we need this is because the old Hadoop API wants us to "write"
    // to the key and value whereas the new asks for it.
    // I choose 8kb as the default max key size (instanciated only once), but you can
    // override it in your jobConf with this setting.
    public static final String CASSANDRA_HADOOP_MAX_KEY_SIZE = "cassandra.hadoop.max_key_size";
    public static final int CASSANDRA_HADOOP_MAX_KEY_SIZE_DEFAULT = 8192;

    private String keyspace;
    private String cfName;

    private static void validateConfiguration(Configuration conf) {
        if (ConfigHelper.getInputKeyspace(conf) == null || ConfigHelper.getInputColumnFamily(conf) == null) {
            throw new UnsupportedOperationException("you must set the keyspace and columnfamily with setColumnFamily()");
        }
        if (ConfigHelper.getInputSlicePredicate(conf) == null) {
            throw new UnsupportedOperationException("you must set the predicate with setPredicate");
        }
    }

    public List<InputSplit> getSplits(JobContext context) throws IOException {
        Configuration conf = context.getConfiguration();

        validateConfiguration(conf);

        // cannonical ranges and nodes holding replicas
        List<TokenRange> masterRangeNodes = getRangeMap(conf);

        keyspace = ConfigHelper.getInputKeyspace(context.getConfiguration());
        cfName = ConfigHelper.getInputColumnFamily(context.getConfiguration());

        // cannonical ranges, split into pieces, fetching the splits in parallel
        ExecutorService executor = Executors.newCachedThreadPool();
        List<InputSplit> splits = new ArrayList<InputSplit>();

        try {
            List<Future<List<InputSplit>>> splitfutures = new ArrayList<Future<List<InputSplit>>>();
            KeyRange jobKeyRange = ConfigHelper.getInputKeyRange(conf);
            IPartitioner partitioner = null;
            Range<Token> jobRange = null;
            if (jobKeyRange != null) {
                partitioner = ConfigHelper.getInputPartitioner(context.getConfiguration());
                assert partitioner.preservesOrder() : "ConfigHelper.setInputKeyRange(..) can only be used with a order preserving paritioner";
                assert jobKeyRange.start_key == null : "only start_token supported";
                assert jobKeyRange.end_key == null : "only end_token supported";
                jobRange = new Range<Token>(partitioner.getTokenFactory().fromString(jobKeyRange.start_token),
                        partitioner.getTokenFactory().fromString(jobKeyRange.end_token),
                        partitioner);
            }

            for (TokenRange range : masterRangeNodes) {
                if (jobRange == null) {
                    // for each range, pick a live owner and ask it to compute bite-sized splits
                    splitfutures.add(executor.submit(new SplitCallable(range, conf)));
                } else {
                    Range<Token> dhtRange = new Range<Token>(partitioner.getTokenFactory().fromString(range.start_token),
                            partitioner.getTokenFactory().fromString(range.end_token),
                            partitioner);

                    if (dhtRange.intersects(jobRange)) {
                        for (Range<Token> intersection : dhtRange.intersectionWith(jobRange)) {
                            range.start_token = partitioner.getTokenFactory().toString(intersection.left);
                            range.end_token = partitioner.getTokenFactory().toString(intersection.right);
                            // for each range, pick a live owner and ask it to compute bite-sized splits
                            splitfutures.add(executor.submit(new SplitCallable(range, conf)));
                        }
                    }
                }
            }

            // wait until we have all the results back
            for (Future<List<InputSplit>> futureInputSplits : splitfutures) {
                try {
                    splits.addAll(futureInputSplits.get());
                } catch (Exception e) {
                    throw new IOException("Could not get input splits", e);
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assert splits.size() > 0;
        Collections.shuffle(splits, new Random(System.nanoTime()));
        return splits;
    }

    /**
     * Gets a token range and splits it up according to the suggested
     * size into input splits that Hadoop can use.
     */
    class SplitCallable implements Callable<List<InputSplit>> {

        private final TokenRange range;
        private final Configuration conf;

        public SplitCallable(TokenRange tr, Configuration conf) {
            this.range = tr;
            this.conf = conf;
        }

        public List<InputSplit> call() throws Exception {
            ArrayList<InputSplit> splits = new ArrayList<InputSplit>();
            List<String> tokens = getSubSplits(keyspace, cfName, range, conf);
            assert range.rpc_endpoints.size() == range.endpoints.size() : "rpc_endpoints size must match endpoints size";
            // turn the sub-ranges into InputSplits
            String[] endpoints = range.endpoints.toArray(new String[range.endpoints.size()]);
            // hadoop needs hostname, not ip
            int endpointIndex = 0;
            for (String endpoint : range.rpc_endpoints) {
                String endpoint_address = endpoint;
                if (endpoint_address == null || endpoint_address.equals("0.0.0.0"))
                    endpoint_address = range.endpoints.get(endpointIndex);
                endpoints[endpointIndex++] = InetAddress.getByName(endpoint_address).getHostName();
            }

            for (int i = 1; i < tokens.size(); i++) {
                ColumnFamilySplit split = new ColumnFamilySplit(tokens.get(i - 1), tokens.get(i), endpoints);
                logger.debug("adding " + split);
                splits.add(split);
            }
            return splits;
        }
    }

    private List<String> getSubSplits(String keyspace, String cfName, TokenRange range, Configuration conf) throws IOException {
        int splitsize = ConfigHelper.getInputSplitSize(conf);
        for (int i = 0; i < range.rpc_endpoints.size(); i++) {
            String host = range.rpc_endpoints.get(i);

            if (host == null || host.equals("0.0.0.0"))
                host = range.endpoints.get(i);

            try {
                ///// TODO: Cassandra.Client client = ConfigHelper.createConnection(host, ConfigHelper.getRpcPort(conf), true);
                Cassandra.Client client = null;
                client.set_keyspace(keyspace);
                return client.describe_splits(cfName, range.start_token, range.end_token, splitsize);
            } catch (Exception e) {
                logger.debug("failed connect to endpoint " + host, e);
            } /*catch (TException e) {
                throw new RuntimeException(e);
            } catch (InvalidRequestException e) {
                throw new RuntimeException(e);
            } */
        }
        throw new IOException("failed connecting to all endpoints " + StringUtils.join(range.endpoints, ","));
    }


    private List<TokenRange> getRangeMap(Configuration conf) throws IOException {
        Cassandra.Client client = ConfigHelper.getClientFromInputAddressList(conf);

        List<TokenRange> map;
        try {
            map = client.describe_ring(ConfigHelper.getInputKeyspace(conf));
        } catch (TException e) {
            throw new RuntimeException(e);
        } catch (InvalidRequestException e) {
            throw new RuntimeException(e);
        }
        return map;
    }

    public TitanCassandraRecordReader createRecordReader(InputSplit inputSplit, TaskAttemptContext taskAttemptContext) throws IOException, InterruptedException {
        return new TitanCassandraRecordReader();
    }


}