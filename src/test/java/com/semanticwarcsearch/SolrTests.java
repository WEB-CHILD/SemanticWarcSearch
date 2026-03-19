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
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SolrTests {

    private static final String COLLECTION = "testcore";

    @SuppressWarnings("resource")
    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("solr/testcore"),
                                    "/opt/solr/server/solr/configsets/testcore");

    private static HttpJdkSolrClient solrClient;    

    @BeforeAll
    static void setUp() throws Exception {
        solrContainer.start();
        // Create core with the custom configset (withCollection uses _default)
        solrContainer.execInContainer("solr", "create_core", "-c", COLLECTION, "-d", COLLECTION);
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
        doc.addField("content", "Lorem ipsum dolor sit amet, consectetur adipiscing elit.");

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

    /** Verifies that {@link SolrRepository#stream} returns all matching documents. */
    @Test
    void streamReturnsAllDocuments() throws SolrServerException, IOException {
        solrClient.add(COLLECTION, docWithContent("10", "Stream doc A"));
        solrClient.add(COLLECTION, docWithContent("11", "Stream doc B"));
        solrClient.add(COLLECTION, docWithContent("12", "Stream doc C"));
        solrClient.commit(COLLECTION);

        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            List<WarcDocument> results = repo.stream(new SolrQuery("id:10  OR id:11  OR id:12"), 10).toList();
            assertEquals(3, results.size());
            assertTrue(results.stream().map(WarcDocument::getId).toList().containsAll(List.of("10", "11", "12")));
        }
    }

    /** Verifies that {@link SolrRepository#stream} paginates correctly when batchSize is smaller than total results. */
    @Test
    void streamPaginatesAcrossMultipleBatches() throws SolrServerException, IOException {
        for (int i = 20; i < 25; i++) {
            solrClient.add(COLLECTION, docWithContent(String.valueOf(i), "Batch doc " + i));
        }
        solrClient.commit(COLLECTION);

        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            // batchSize=2 forces 3 round-trips for 5 documents
            List<WarcDocument> results = repo.stream(new SolrQuery("id:(20 21 22 23 24)"), 2).toList();
            assertEquals(5, results.size());
        }
    }

    /** Verifies that {@link SolrRepository#stream} returns an empty stream when no documents match. */
    @Test
    void streamReturnsEmptyStreamWhenNoDocumentsMatch() throws IOException {
        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            List<WarcDocument> results = repo.stream(new SolrQuery("id:nonexistent-doc-xyz"), 10).toList();
            assertTrue(results.isEmpty());
        }
    }

    /** Verifies that {@link SolrRepository#atomicUpdate} correctly stores a {@code float[]} embedding vector in a {@code knn_vector} field. */
    @Test
    void atomicUpdateOfGeneratedVector() throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "4");
        doc.addField("content", "Test content for vector update");

        solrClient.add(COLLECTION, doc);
        solrClient.commit(COLLECTION);

        String solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        try (SolrRepository<Object> repo = new SolrRepository<>(solrUrl, COLLECTION, Object.class)) {
            // Simulate embedding generation and atomic update of the vector field
            float[] dummyVector = new float[1024]; // Must match vectorDimension in schema.xml
            repo.atomicUpdate("4", Map.of("content_as_vector", dummyVector));
        }

        QueryResponse response = solrClient.query(COLLECTION, new SolrQuery("id:4"));
        SolrDocumentList results = response.getResults();

        assertFalse(results.isEmpty());
        Object vectorValue = results.get(0).getFieldValue("content_as_vector");
        assertNotNull(vectorValue);
    }

    private static SolrInputDocument docWithContent(String id, String content) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        doc.addField("content", content);
        return doc;
    }
}
