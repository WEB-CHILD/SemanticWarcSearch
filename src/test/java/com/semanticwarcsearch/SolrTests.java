package com.semanticwarcsearch;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SolrTests {

    private static final String COLLECTION = "testcollection";

    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9"))
            .withCollection(COLLECTION);

    private static HttpJdkSolrClient solrClient;

    @BeforeAll
    static void setUp() {
        solrContainer.start();
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        solrClient = new HttpJdkSolrClient.Builder(solrUrl).build();
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (solrClient != null) solrClient.close();
        solrContainer.stop();
    }

    /** Verifies the Solr container started successfully. */
    @Test
    void solrIsRunning() {
        assertTrue(solrContainer.isRunning());
    }

    /** Verifies that a document can be indexed and retrieved by field query. */
    @Test
    void canIndexAndQueryDocument() throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "1");
        doc.addField("title", "Test Document");

        solrClient.add(COLLECTION, doc);
        solrClient.commit(COLLECTION);

        QueryResponse response = solrClient.query(COLLECTION, new SolrQuery("id:1"));
        SolrDocumentList results = response.getResults();

        assertFalse(results.isEmpty());
        Object titleValue = results.get(0).getFieldValue("title");
        // Solr may return multi-valued fields as a list
        if (titleValue instanceof java.util.Collection<?> c) {
            assertTrue(c.contains("Test Document"));
        } else {
            assertEquals("Test Document", titleValue);
        }
    }

    /** Verifies that {@link SolrRepository} can connect and execute a query. */
    @Test
    void repositoryCanQueryDocuments() throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "2");
        doc.addField("title", "Repo Test");

        solrClient.add(COLLECTION, doc);
        solrClient.commit(COLLECTION);

        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<Object> repo = new SolrRepository<>(solrUrl, COLLECTION, Object.class)) {
            // Simple query via the repository - verifies it can connect and execute queries
            var results = repo.query(new SolrQuery("id:2"));
            assertNotNull(results);
        }
    }

    /** Verifies that {@link SolrRepository#atomicUpdate} replaces a field value without reindexing the full document. */
    @Test
    void atomicUpdateModifiesField() throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "3");
        doc.addField("title", "Original Title");

        solrClient.add(COLLECTION, doc);
        solrClient.commit(COLLECTION);

        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<Object> repo = new SolrRepository<>(solrUrl, COLLECTION, Object.class)) {
            repo.atomicUpdate("3", Map.of("title", "Updated Title"));
        }

        QueryResponse response = solrClient.query(COLLECTION, new SolrQuery("id:3"));
        SolrDocumentList results = response.getResults();

        assertFalse(results.isEmpty());
        Object titleValue = results.get(0).getFieldValue("title");
        if (titleValue instanceof java.util.Collection<?> c) {
            assertTrue(c.contains("Updated Title"));
        } else {
            assertEquals("Updated Title", titleValue);
        }
    }
}
