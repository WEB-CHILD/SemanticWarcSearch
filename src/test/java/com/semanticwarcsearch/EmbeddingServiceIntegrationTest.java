package com.semanticwarcsearch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EmbeddingService} against a locally-running Ollama instance.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Ollama is running on {@code http://localhost:11434}</li>
 *   <li>The model is pulled: {@code ollama pull qwen3-embedding:0.6B}</li>
 * </ul>
 */
class EmbeddingServiceIntegrationTest {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6B";

    private static EmbeddingService embeddingService;

    @BeforeAll
    static void setUp() {
        OllamaApi ollamaApi = new OllamaApi(OLLAMA_BASE_URL);
        OllamaEmbeddingModel model = OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaOptions.builder().model(EMBEDDING_MODEL).build())
                .build();
        embeddingService = new EmbeddingService(model);
    }

    /** Verifies that a non-empty vector is returned for a simple input text. */
    @Test
    void embedReturnsNonEmptyVector() {
        float[] vector = embeddingService.embed("The quick brown fox jumps over the lazy dog.");

        assertNotNull(vector);
        assertTrue(vector.length > 0, "embedding vector should have at least one dimension");
    }

    /** Verifies that all values in the returned vector are finite (no NaN or Infinity). */
    @Test
    void embedReturnsFiniteValues() {
        float[] vector = embeddingService.embed("Spring Boot is a Java framework.");

        for (int i = 0; i < vector.length; i++) {
            assertTrue(Float.isFinite(vector[i]), "value at index " + i + " should be finite");
        }
    }

    /** Verifies that two semantically distinct texts produce different embedding vectors. */
    @Test
    void embedProducesDifferentVectorsForDifferentTexts() {
        float[] v1 = embeddingService.embed("A cat sat on the mat.");
        float[] v2 = embeddingService.embed("The European Central Bank raised interest rates.");

        assertEquals(v1.length, v2.length, "vectors should have the same dimensionality");
        boolean differs = false;
        for (int i = 0; i < v1.length; i++) {
            if (v1[i] != v2[i]) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "distinct texts should produce distinct embeddings");
    }

    /** Verifies that embedAll returns one vector per input text in the correct order. */
    @Test
    void embedAllReturnsOneVectorPerText() {
        List<String> texts = List.of(
                "First document about web archiving.",
                "Second document about machine learning.",
                "Third document about information retrieval."
        );

        List<float[]> vectors = embeddingService.embedAll(texts);

        assertNotNull(vectors);
        assertEquals(texts.size(), vectors.size(), "should return one vector per input text");
        vectors.forEach(v -> assertTrue(v.length > 0, "each vector should be non-empty"));
    }

    /** Verifies that all vectors returned by embedAll share the same dimensionality. */
    @Test
    void embedAllReturnsConsistentDimensionality() {
        List<float[]> vectors = embeddingService.embedAll(List.of(
                "Semantic search over WARC files.",
                "Dense vector indexing with Solr."
        ));

        int expectedDim = vectors.get(0).length;
        for (float[] v : vectors) {
            assertEquals(expectedDim, v.length, "all vectors should have the same number of dimensions");
        }
    }
}
