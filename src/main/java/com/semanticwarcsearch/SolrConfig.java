package com.semanticwarcsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "solr")
public class SolrConfig {

    private String baseUrl;
    private String collection;

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    @Bean(destroyMethod = "close")
    public SolrRepository<WarcDocument> warcDocumentRepository() {
        return new SolrRepository<>(baseUrl, collection, WarcDocument.class);
    }
}
