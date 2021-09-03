package io.pravega.flinkprocessor;

import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.flinkprocessor.datatypes.FlatMetricReport;
import io.pravega.flinkprocessor.util.JsonDeserializationSchema;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.io.jdbc.JDBCOutputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;


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
            String query = "INSERT INTO idrac(time, remote_addr, value, Metric_id, rack_label, context_id, id) VALUES (?, ?, ?, ?, ?, ?, ?)";
            JDBCOutputFormat jdbcOutput = JDBCOutputFormat.buildJDBCOutputFormat()
                    .setDrivername("org.postgresql.Driver")
                    .setDBUrl("jdbc:postgresql://localhost:5432/testdb?user=postgres&password=password")
                    .setQuery(query)
                    .finish();
            final AppConfiguration.StreamConfig inputStreamConfig = getConfig().getStreamConfig("input");
            log.info("input stream: {}", inputStreamConfig);
            createStream(inputStreamConfig);
            final StreamCut startStreamCut = resolveStartStreamCut(inputStreamConfig);
            final StreamCut endStreamCut = resolveEndStreamCut(inputStreamConfig);
            final String fixedRoutingKey = getConfig().getParams().get("fixedRoutingKey", "");
            log.info("fixedRoutingKey: {}", fixedRoutingKey);
            final StreamExecutionEnvironment env = initializeFlinkStreaming();

            final FlinkPravegaReader<FlatMetricReport> flinkPravegaReader = FlinkPravegaReader.<FlatMetricReport>builder()
                    .withPravegaConfig(inputStreamConfig.getPravegaConfig())
                    .forStream(inputStreamConfig.getStream(), startStreamCut, endStreamCut)
                    .withDeserializationSchema(new JsonDeserializationSchema(FlatMetricReport.class))
                    .build();

            DataStream<FlatMetricReport> events = env
                    .addSource(flinkPravegaReader)
                    .name("read-flatten-events");
            //events.printToErr();
            DataStream<Row> rows = events.map((MapFunction<FlatMetricReport, Row>) metricValue -> {
                Row row = new Row(7); // our prepared statement has 2 parameters
                //row.setField(0, LocalDateTime.ofEpochSecond(metricValue.Timestamp/1000, 0 , ZoneOffset.UTC));
                row.setField(0, LocalDateTime.now());
                row.setField(1, metricValue.RemoteAddr);
                row.setField(2, metricValue.MetricValue);
                row.setField(3, metricValue.MetricId);
                row.setField(4, metricValue.RackLabel);
                row.setField(5, metricValue.ContextID);
                row.setField(6, metricValue.Id);
                return row;
            });
            rows.writeUsingOutputFormat(jdbcOutput);

            env.execute(PravegaToTimeScaleDB.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
