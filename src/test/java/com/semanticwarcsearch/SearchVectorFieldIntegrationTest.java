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
import java.util.stream.Stream;

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
class SearchVectorFieldIntegrationTest {

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

        // --- 1. Seed 5 documents in Solr ---
        List<String> contents = List.of(
                "Web archiving preserves digital content for future generations.",
                "Dense vector search enables semantic retrieval over large corpora.",
                "WARC files store raw HTTP exchanges captured during web crawls.",
                "Solr supports approximate nearest-neighbour search via DenseVectorField.",
                "Ollama runs large language models locally without cloud dependencies.",
                "Jeg er en dreng på 12 år, og jeg elsker at spille fodbold med mine venner i parken hver weekend.", 
                "Mine favoritfodboldspillere er Lionel Messi og Cristiano Ronaldo, fordi de er utroligt dygtige og har inspireret mig til at blive bedre.",
                "Mine hobbier er at male og læse og ridde på min hest på ridderskolen, hvor jeg lærer om heste og får masser af frisk luft.",
                "Jeg går i 6. klasse og mine yndlingsfag er matematik og historie",
                "I am a boy, 12 years old, and I love playing football with my friends in the park every weekend."
        );
        for (int i = 0; i < contents.size(); i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", "embed-" + i);
            doc.addField("content", contents.get(i));
            solrClient.add(COLLECTION, doc);
        }
        solrClient.commit(COLLECTION);

        // --- 2. Stream → embed → atomic-update as a single pipeline (no intermediate lists) ---
        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            Stream<Map.Entry<String, Map<String, Object>>> updates =
                    repo.stream(new SolrQuery("*:*"), 10)
                        .map(doc -> Map.entry(
                                doc.getId(),
                                Map.<String, Object>of("content_as_vector",
                                        embeddingService.embed(doc.getContent()))));
            repo.atomicUpdateAll(updates);
        }

        // --- 3. Verify every document now has a populated content_as_vector field ---
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

    @AfterAll
    static void tearDown() throws IOException {
        if (solrClient != null) solrClient.close();
        solrContainer.stop();
    }

    /**
     * Embeds a natural-language query string via Ollama and performs a KNN vector search
     * against the {@code content_as_vector} field, returning the single closest document
     * in the embedding space and printing both the query and the matched content.
     */
    @Test
    void queryVectors() throws SolrServerException, IOException {
        String queryText = "Mine favoritfodboldspillere er Lionel Messi og Cristiano Ronaldo, fordi de er utroligt dygtige og har inspireret mig til at blive bedre.";
        float[] queryVector = embeddingService.embed(queryText);

        try (SolrRepository<WarcDocument> repo = new SolrRepository<>(solrUrl, COLLECTION, WarcDocument.class)) {
            List<WarcDocument> results = repo.searchByVector(queryVector, "content_as_vector", 1);
            assertFalse(results.isEmpty(), "KNN search should return at least one result");
            WarcDocument closest = results.get(0);
            assertNotNull(closest.getContent(), "closest document should have a content field");
            System.out.println("Query : \"" + queryText + "\"");
            System.out.println("Closest: \"" + closest.getContent() + "\"");
        }
    }
}
