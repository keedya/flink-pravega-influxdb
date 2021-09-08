package io.pravega.flinkprocessor;

import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.flinkprocessor.datatypes.tag;
import io.pravega.flinkprocessor.util.JsonDeserializationSchema;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.io.jdbc.JDBCOutputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Types;
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
            String query = String.format("INSERT INTO %s(time, tagname, devicename, deviceid, value, success) VALUES (?, ?, ?, ?, ?, ?)",
                    getConfig().getTimescaledbTable());
            JDBCOutputFormat jdbcOutput = JDBCOutputFormat.buildJDBCOutputFormat()
                    .setDrivername("org.postgresql.Driver")
                    .setDBUrl(String.format("jdbc:%s/%s?user=%s&password=%s",
                            getConfig().getTimescaledbUrl(),
                            getConfig().getTimescaledbDatabase(),
                            getConfig().getTimescaledbUsername(),
                            getConfig().getTimescaledbPassword()
                             )
                    )
                    .setQuery(query)
                    .setSqlTypes(new int[] { Types.TIMESTAMP_WITH_TIMEZONE, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BOOLEAN })
                    .setBatchInterval(getConfig().getTimescaledbBatchSize())
                    .finish();
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
            //events.printToErr();
            DataStream<Row> rows = events.map((MapFunction<tag, Row>) metricValue -> {
                Row row = new Row(6);
                row.setField(0, LocalDateTime.ofEpochSecond(metricValue.timestamp/1000, 0 , ZoneOffset.UTC));
                row.setField(1, metricValue.tagName);
                row.setField(2, metricValue.deviceName);
                row.setField(3, metricValue.deviceID);
                row.setField(4, metricValue.value);
                row.setField(5, metricValue.success);
                return row;
            });
            rows.writeUsingOutputFormat(jdbcOutput);

            env.execute(PravegaToTimeScaleDB.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
