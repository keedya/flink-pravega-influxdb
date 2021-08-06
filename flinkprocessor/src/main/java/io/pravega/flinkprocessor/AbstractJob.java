package io.pravega.flinkprocessor;

import io.pravega.client.admin.StreamInfo;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.StreamCut;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.LocalEnvironment;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch6.ElasticsearchSink;
import org.apache.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.streaming.connectors.influxdb.InfluxDBConfig;
import org.apache.flink.streaming.connectors.influxdb.InfluxDBSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An abstract job class for Flink Pravega applications.
 */
public abstract class AbstractJob implements Runnable {
    final private static Logger log = LoggerFactory.getLogger(AbstractJob.class);

    private final AppConfiguration config;

    public AbstractJob(AppConfiguration config) {
        this.config = config;
    }

    public AppConfiguration getConfig() {
        return config;
    }

    /**
     * If the Pravega stream does not exist, creates a new stream with the specified stream configuration.
     * If the stream exists, it is unchanged.
     */
    public void createStream(AppConfiguration.StreamConfig streamConfig) {
        try (StreamManager streamManager = StreamManager.create(streamConfig.getPravegaConfig().getClientConfig())) {
            StreamConfiguration streamConfiguration = StreamConfiguration.builder()
                    .scalingPolicy(streamConfig.getScalingPolicy())
                    .build();
            streamManager.createStream(
                    streamConfig.getStream().getScope(),
                    streamConfig.getStream().getStreamName(),
                    streamConfiguration);
        }
    }

    /**
     * Get head and tail stream cuts for a Pravega stream.
     */
    public StreamInfo getStreamInfo(AppConfiguration.StreamConfig streamConfig) {
        try (StreamManager streamManager = StreamManager.create(streamConfig.getPravegaConfig().getClientConfig())) {
            return streamManager.getStreamInfo(streamConfig.getStream().getScope(), streamConfig.getStream().getStreamName());
        }
    }

    /**
     * Convert UNBOUNDED start StreamCut to a concrete StreamCut, pointing to the current head or tail of the stream
     * (depending on isStartAtTail).
     */
    public StreamCut resolveStartStreamCut(AppConfiguration.StreamConfig streamConfig) {
        if (streamConfig.isStartAtTail()) {
            return getStreamInfo(streamConfig).getTailStreamCut();
        } else if (streamConfig.getStartStreamCut() == StreamCut.UNBOUNDED) {
            return getStreamInfo(streamConfig).getHeadStreamCut();
        } else {
            return streamConfig.getStartStreamCut();
        }
    }

    /**
     * For bounded reads (indicated by isEndAtTail), convert UNBOUNDED end StreamCut to a concrete StreamCut,
     * pointing to the current tail of the stream.
     * For unbounded reads, returns UNBOUNDED.
     */
    public StreamCut resolveEndStreamCut(AppConfiguration.StreamConfig streamConfig) {
        if (streamConfig.isEndAtTail()) {
            return getStreamInfo(streamConfig).getTailStreamCut();
        } else {
            return streamConfig.getEndStreamCut();
        }
    }

    public StreamExecutionEnvironment initializeFlinkStreaming() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Make parameters show in Flink UI.
        env.getConfig().setGlobalJobParameters(getConfig().getParams());

        env.setParallelism(getConfig().getParallelism());
        log.info("Parallelism={}, MaxParallelism={}", env.getParallelism(), env.getMaxParallelism());

        if (!getConfig().isEnableOperatorChaining()) {
            env.disableOperatorChaining();
        }
        if (getConfig().isEnableCheckpoint()) {
            env.enableCheckpointing(getConfig().getCheckpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setMinPauseBetweenCheckpoints(getConfig().getCheckpointIntervalMs() / 2);
            env.getCheckpointConfig().setCheckpointTimeout(getConfig().getCheckpointTimeoutMs());
            // A checkpoint failure will cause the job to fail.
            env.getCheckpointConfig().setTolerableCheckpointFailureNumber(0);
            // If the job is cancelled manually by the user, do not delete the checkpoint.
            // This retained checkpoint can be used manually when restarting the job.
            // In SDP, a retained checkpoint can be used by creating a FlinkSavepoint object.
            env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }

        // Configure environment for running in a local environment (e.g. in IntelliJ).
        if (env instanceof LocalStreamEnvironment) {
            // We can't use MemoryStateBackend because it can't store large state.
            if (env.getStateBackend() == null || env.getStateBackend() instanceof MemoryStateBackend) {
                log.warn("Using FsStateBackend instead of MemoryStateBackend");
                env.setStateBackend(new FsStateBackend("file:///tmp/flink-state", true));
            }
            // Stop immediately on any errors.
            log.warn("Using noRestart restart strategy");
            env.setRestartStrategy(RestartStrategies.noRestart());
            // Initialize Hadoop file system.
            FileSystem.initialize(getConfig().getParams().getConfiguration());
        }
        return env;
    }



    public ExecutionEnvironment initializeFlinkBatch() {
        ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        // Make parameters show in Flink UI.
        env.getConfig().setGlobalJobParameters(getConfig().getParams());

        int parallelism = getConfig().getParallelism();
        if (parallelism > 0) {
            env.setParallelism(parallelism);
        }
        log.info("Parallelism={}", env.getParallelism());

        // Configure environment for running in a local environment (e.g. in IntelliJ).
        if (env instanceof LocalEnvironment) {
            // Initialize Hadoop file system.
            FileSystem.initialize(getConfig().getParams().getConfiguration());
        }
        return env;
    }
    protected void setupElasticSearch() throws Exception {
        if (getConfig().getElasticSearch().isSinkResults()) {
            new ElasticSetup(getConfig().getElasticSearch()).run();
        }
    }

    protected ElasticsearchSinkFunction getResultSinkFunction() {
        throw new UnsupportedOperationException();
    }

    protected ElasticsearchSink newElasticSearchSink() throws Exception {
        String host = getConfig().getElasticSearch().getHost();
        int port = getConfig().getElasticSearch().getPort();

        List<HttpHost> httpHosts = new ArrayList<>();
        httpHosts.add(new HttpHost(host, port, "http"));

        ElasticsearchSink.Builder builder = new ElasticsearchSink.Builder<>(httpHosts, getResultSinkFunction());
        builder.setBulkFlushInterval(3000);
        builder.setBulkFlushMaxActions(20000);
        builder.setBulkFlushMaxSizeMb(50);
        return builder.build();

    }
    protected void addMetricsSink(DataStream datastream, String uidSuffix) {
        switch (config.getMetricsSink()) {
            case InfluxDB:
                datastream.addSink(createInfluxDBSink())
                        .name("influxdb-sink_" + uidSuffix)
                        .uid("influxdb-sink_" + uidSuffix);
                break;
            case ES:
                break;

            default:
                throw new RuntimeException("Metric Sink type not supported");
        }
    }

    protected InfluxDBSink createInfluxDBSink() {
        InfluxDBConfig influxDBConfig = InfluxDBConfig.builder(config.getInfluxdbUrl(),
                config.getInfluxdbUsername(), config.getInfluxdbPassword(), config.getInfluxdbDatabase())
                .batchActions(config.getInfluxdbBatchSize())
                .flushDuration(config.getInfluxdbFlushDuration(), TimeUnit.MILLISECONDS)
                .enableGzip(true)
                .build();
        return new InfluxDBSink(influxDBConfig);
    }
}