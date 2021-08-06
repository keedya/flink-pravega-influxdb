package io.pravega.flinkprocessor;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHost;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ElasticSetup {
    private static final Logger LOG = LoggerFactory.getLogger(ElasticSetup.class);

    private static final String VISUALIZATION_ID = "a60144d4-921b-472c-8616-f7d524ddb150";

    private final AppConfiguration.ElasticSearch elasticConfig;

    public ElasticSetup(AppConfiguration.ElasticSearch elasticConfig) {
        this.elasticConfig = elasticConfig;
    }

    public void run() throws Exception {
        String host = elasticConfig.getHost();
        int port = elasticConfig.getPort();
        List<InetSocketAddress> transports = new ArrayList<>();
        transports.add(new InetSocketAddress(InetAddress.getByName(host), port));

        LOG.info("Connecting to Elastic Search http://{}:{}", host, port);

        RestHighLevelClient client = new RestHighLevelClient(
                RestClient.builder(new HttpHost(host, port, "http")));

        if (elasticConfig.isDeleteIndex()) {
            LOG.info("Deleting old Elasticsearch index");
            try {
                client.indices().delete(Requests.deleteIndexRequest(elasticConfig.getIndex()));
            } catch (ElasticsearchStatusException ignore) {
            }
        }

        LOG.info("Creating Elasticsearch Index");
        String fileName = String.format("%s-%s-elastic-index.json", elasticConfig.getIndex(), elasticConfig.getType());
        String indexBody = getTemplate(fileName, Collections.singletonMap("type", elasticConfig.getType()));

        CreateIndexRequest createIndexRequest = Requests.createIndexRequest(elasticConfig.getIndex())
                .mapping(elasticConfig.getType(), indexBody, XContentType.JSON);

        try {
            client.indices().create(createIndexRequest);
        } catch (ElasticsearchStatusException ignore) {
        }
    }

    private String getTemplate(String file, Map<String, String> values) throws Exception {
        URL url = getClass().getClassLoader().getResource(file);
        if (url == null) {
            throw new IllegalStateException("Template file " + file + " not found");
        }

        String body = IOUtils.toString(url.openStream());
        for (Map.Entry<String, String> value : values.entrySet()) {
            body = body.replace("@@" + value.getKey() + "@@", value.getValue());
        }

        return body;
    }
}