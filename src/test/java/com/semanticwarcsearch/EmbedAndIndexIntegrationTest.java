package com.semanticwarcsearch;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that wires together {@link SolrRepository} and {@link EmbeddingService}:
 * streams documents from Solr, generates dense embeddings via Ollama, posts them back using
 * an atomic update, and verifies the {@code content_as_vector} field is populated.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Docker available (Testcontainers starts Solr 9 automatically)</li>
 *   <li>Ollama running on {@code http://localhost:11434}</li>
 *   <li>Model pulled: {@code ollama pull qwen3-embedding:0.6B}</li>
 * </ul>
 */
class EmbedAndIndexIntegrationTest {

    private static final String COLLECTION      = "testcore";
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6B";

    @SuppressWarnings("resource")
    static SolrContainer solrContainer = new SolrContainer(DockerImageName.parse("solr:9"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("solr/testcore"),
                                     "/opt/solr/server/solr/configsets/testcore");

    private static HttpJdkSolrClient solrClient;
    private static EmbeddingService  embeddingService;
    private static String            solrUrl;

    @BeforeAll
    static void setUp() throws Exception {
        solrContainer.start();
        solrContainer.execInContainer("solr", "create_core", "-c", COLLECTION, "-d", COLLECTION);
        solrUrl = "http://" + solrContainer.getHost() + ":" + solrContainer.getSolrPort() + "/solr";
        solrClient = new HttpJdkSolrClient.Builder(solrUrl).build();

        OllamaApi ollamaApi = new OllamaApi(OLLAMA_BASE_URL);
        OllamaEmbeddingModel model = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaOptions.builder().model(EMBEDDING_MODEL).build())
                .build();
        embeddingService = new EmbeddingService(model);
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (solrClient != null) solrClient.close();
        solrContainer.stop();
    }

    /**
     * Streams 5 documents from Solr, embeds their {@code content} field via Ollama,
     * writes the resulting vectors back as atomic updates, then verifies that every
     * document has a non-null {@code content_as_vector} field.
     */
    @Test
    void streamEmbedAndIndexVectors() throws SolrServerException, IOException {
        // --- 1. Seed 5 documents in Solr ---
        List<String> contents = List.of(
                "Web archiving preserves digital content for future generations.",
                "Dense vector search enables semantic retrieval over large corpora.",
                "WARC files store raw HTTP exchanges captured during web crawls.",
                "Solr supports approximate nearest-neighbour search via DenseVectorField.",
                "Ollama runs large language models locally without cloud dependencies."
        );
        for (int i = 0; i < contents.size(); i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", "embed-" + i);
            doc.addField("content", contents.get(i));
            solrClient.add(COLLECTION, doc);
        }
        solrClient.commit(COLLECTION);

        // --- 2. Stream the 5 documents back via SolrRepository ---
        List<WarcDocument> docs;
        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            docs = repo.stream(new SolrQuery("id:(embed-0 embed-1 embed-2 embed-3 embed-4)"), 10).toList();
        }
        assertEquals(5, docs.size(), "should stream exactly 5 documents");

        // --- 3. Embed all content values in a single batch call ---
        List<String> texts = docs.stream().map(WarcDocument::getContent).toList();
        List<float[]> vectors = embeddingService.embedAll(texts);
        assertEquals(docs.size(), vectors.size(), "one vector per document");

        // --- 4. Atomic-update the content_as_vector field for each document ---
        try (SolrRepository<Object> repo = new SolrRepository<>(solrUrl, COLLECTION, Object.class)) {
            for (int i = 0; i < docs.size(); i++) {
                repo.atomicUpdate(docs.get(i).getId(), Map.of("content_as_vector", vectors.get(i)));
            }
        }

        // --- 5. Verify every document now has a populated content_as_vector field ---
        QueryResponse response = solrClient.query(
                COLLECTION,
                new SolrQuery("id:(embed-0 embed-1 embed-2 embed-3 embed-4)").setRows(10)
        );
        List<SolrDocument> results = response.getResults();
        assertEquals(5, results.size(), "all 5 documents should be retrievable after update");

        for (SolrDocument result : results) {
            Object vectorValue = result.getFieldValue("content_as_vector");
            assertNotNull(vectorValue,
                    "document " + result.getFieldValue("id") + " should have content_as_vector after atomic update");
        }
    }
}
