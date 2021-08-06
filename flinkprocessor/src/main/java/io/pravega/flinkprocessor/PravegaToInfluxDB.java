package io.pravega.flinkprocessor;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.flinkprocessor.util.UTF8StringDeserializationSchema;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.influxdb.InfluxDBPoint;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Iterator;


public class PravegaToInfluxDB extends AbstractJob {
    public PravegaToInfluxDB(AppConfiguration appConfiguration) {
        super(appConfiguration);
    }

    private static Logger log = LoggerFactory.getLogger(PravegaToInfluxDB.class);

    /**
     * The entry point for Flink applications.
     */
    public static void main(String... args) throws Exception {
        AppConfiguration config = new AppConfiguration(args);
        log.info("config: {}", config);
        PravegaToInfluxDB job = new PravegaToInfluxDB(config);
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

            final FlinkPravegaReader<String> flinkPravegaReader = FlinkPravegaReader.<String>builder()
                    .withPravegaConfig(inputStreamConfig.getPravegaConfig())
                    .forStream(inputStreamConfig.getStream(), startStreamCut, endStreamCut)
                    .withDeserializationSchema(new UTF8StringDeserializationSchema())
                    .build();

            DataStream<String> events = env
                    .addSource(flinkPravegaReader)
                    .name("read-flatten-events");
            events.printToErr();
            final ObjectMapper objectMapper = new ObjectMapper();
            final String measurement = getConfig().getParams().get("input-stream", "metrics");
            DataStream<InfluxDBPoint> dbEvents = events
                    .flatMap(new MetricsProcess(objectMapper, measurement))
                    .name("metric-events-to-influxdb-point")
                    .uid("metric-events-to-influxdb-point");
            addMetricsSink(dbEvents, "metrics");
            env.execute(PravegaToInfluxDB.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private class MetricsProcess implements FlatMapFunction<String, InfluxDBPoint> {
        final ObjectMapper objectMapper;
        final String measurement;

        public MetricsProcess(ObjectMapper objectMapper, String measurement){
            this.objectMapper = objectMapper;
            this.measurement = measurement;
        }
        @Override
        public void flatMap(String in, Collector<InfluxDBPoint> out) throws Exception {
            if (in != null) {
                try {
                    final JsonNode node = objectMapper.readTree(in);
                    HashMap<String, Object> fields = new HashMap<>();
                    HashMap<String, String> tags = new HashMap<>();
                    long timestamp = System.currentTimeMillis();
                    for (final Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                        final String key = it.next();
                        final JsonNode tmp = node.get(key);
                        if (tmp == null) {
                            throw new NoSuchFieldException("Field '" + key + "' not found");
                        }
                       if (tmp.getNodeType() == JsonNodeType.NUMBER &&
                                (key == "timestamp" || key == "t" || key == "Timestamp")
                        ) {
                            timestamp = node.get(key).asLong();
                        } else if (tmp.getNodeType() == JsonNodeType.BOOLEAN) {
                            fields.put(key, node.get(key).asBoolean());
                        } else if (tmp.getNodeType() == JsonNodeType.NUMBER) {
                            fields.put(key, node.get(key).asDouble());
                        } else if (tmp.getNodeType() == JsonNodeType.STRING) {
                            tags.put(key, node.get(key).asText());
                        } else {
                            continue;
                        }
                    }
                    out.collect(new InfluxDBPoint(measurement, timestamp, tags, fields));
                } catch ( JsonParseException | NoSuchFieldException | NumberFormatException e) {
                    log.warn("Unable to extract key and counter", e);
                }
            }
        }
    }
}
