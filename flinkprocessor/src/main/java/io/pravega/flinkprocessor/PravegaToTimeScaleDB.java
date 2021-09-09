package io.pravega.flinkprocessor;

import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.flinkprocessor.datatypes.tag;
import io.pravega.flinkprocessor.util.JsonDeserializationSchema;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class PravegaToTimeScaleDB extends AbstractJob {

    public PravegaToTimeScaleDB(AppConfiguration appConfiguration) {
        super(appConfiguration);
    }

    private static Logger log = LoggerFactory.getLogger(PravegaToTimeScaleDB.class);

    /**
     * The entry point for Flink applications.
     */
    public static void main(String... args) throws Exception {
        AppConfiguration config = new AppConfiguration(args);
        log.info("config: {}", config);
        PravegaToTimeScaleDB job = new PravegaToTimeScaleDB(config);
        job.run();
    }

    public void run() {
        try {
            final AppConfiguration.StreamConfig inputStreamConfig = getConfig().getStreamConfig("input");
            log.info("input stream: {}", inputStreamConfig);
            createStream(inputStreamConfig);
            final StreamCut startStreamCut = resolveStartStreamCut(inputStreamConfig);
            final StreamCut endStreamCut = resolveEndStreamCut(inputStreamConfig);
            final String fixedRoutingKey = getConfig().getParams().get("fixedRoutingKey", "");
            log.info("fixedRoutingKey: {}", fixedRoutingKey);
            final StreamExecutionEnvironment env = initializeFlinkStreaming();

            final FlinkPravegaReader<tag> flinkPravegaReader = FlinkPravegaReader.<tag>builder()
                    .withPravegaConfig(inputStreamConfig.getPravegaConfig())
                    .forStream(inputStreamConfig.getStream(), startStreamCut, endStreamCut)
                    .withDeserializationSchema(new JsonDeserializationSchema(tag.class))
                    .build();

            DataStream<tag> events = env
                    .addSource(flinkPravegaReader)
                    .name("read-flatten-events");
            events.addSink(
                    JdbcSink.sink(
                            "INSERT INTO litmus(time, tagname, devicename, deviceid, value, success) VALUES (?, ?, ?, ?, ?, ?)",
                            (statement, metricValue) -> {
                                statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.ofEpochSecond(metricValue.timestamp/1000, 0 , ZoneOffset.UTC)));
                                statement.setString(2, metricValue.tagName);
                                statement.setString(3, metricValue.deviceName);
                                statement.setString(4, metricValue.deviceID);
                                statement.setDouble(5, metricValue.value);
                                statement.setBoolean(6, metricValue.success);
                                },
                            JdbcExecutionOptions.builder()
                                    .withBatchSize(getConfig().getInfluxdbBatchSize())
                                    .withBatchIntervalMs(1000)
                                    .withMaxRetries(5)
                                    .build(),
                            new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                    .withUrl(String.format("jdbc:%s/%s",
                                            getConfig().getTimescaledbUrl(),
                                            getConfig().getTimescaledbDatabase()
                                    ))
                                    .withDriverName("org.postgresql.Driver")
                                    .withUsername(getConfig().getTimescaledbUsername())
                                    .withPassword(getConfig().getTimescaledbPassword())
                                    .build()
                            ));

            env.execute(PravegaToTimeScaleDB.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
