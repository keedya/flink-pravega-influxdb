package io.pravega.flinkprocessor;

import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.flinkprocessor.util.UTF8StringDeserializationSchema;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.streaming.connectors.elasticsearch6.ElasticsearchSink;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a general-purpose class that reads UTF-8 JSON events from Pravega and writes them to ElasticSearch.
 * The JSON is passed without any conversion.
 */
public class PravegatoES extends AbstractJob {
    private static Logger log = LoggerFactory.getLogger(PravegatoES.class);

    public PravegatoES(AppConfiguration appConfiguration) {
        super(appConfiguration);
    }
    /**
     * The entry point for Flink applications.
     *
     */
    public static void main(String... args) throws Exception {
        AppConfiguration config = new AppConfiguration(args);
        log.info("config: {}", config);
        PravegatoES job = new PravegatoES(config);
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
            setupElasticSearch();

            FlinkPravegaReader<String> flinkPravegaReader = FlinkPravegaReader.<String>builder()
                    .withPravegaConfig(inputStreamConfig.getPravegaConfig())
                    .forStream(inputStreamConfig.getStream(), startStreamCut, endStreamCut)
                    .withDeserializationSchema(new UTF8StringDeserializationSchema())
                    .build();

            DataStream<String> events = env
                    .addSource(flinkPravegaReader)
                    .name("events");
            ElasticsearchSink<String> elasticSink = newElasticSearchSink();
            events.addSink(elasticSink).name("Write to ElasticSearch");
            env.execute(PravegatoES.class.getSimpleName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected ElasticsearchSinkFunction getResultSinkFunction() {
        String index = getConfig().getElasticSearch().getIndex();
        String type = getConfig().getElasticSearch().getType();

        return new ResultSinkFunction(index, type);
    }

    public static class ResultSinkFunction implements ElasticsearchSinkFunction<String> {
        private final String index;
        private final String type;

        public ResultSinkFunction(String index, String type) {
            this.index = index;
            this.type = type;
        }

        @Override
        public void process(String event, RuntimeContext ctx, RequestIndexer indexer) {
            //log.error(event);
            indexer.add(createIndexRequest(event));
        }

        private IndexRequest createIndexRequest(String event) {
            try {
                return Requests.indexRequest()
                        .index(index)
                        .type(type)
                        .source(event, XContentType.JSON);
                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}