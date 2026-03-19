package com.semanticwarcsearch;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring service that delegates text embedding to a locally-running
 * <a href="https://ollama.com">Ollama</a> instance via Spring AI's {@link EmbeddingModel} abstraction.
 *
 * <h2>Configuration</h2>
 * The service is auto-configured by the {@code spring-ai-ollama-spring-boot-starter} dependency.
 * Add the following properties to {@code application.yaml} to point it at your Ollama instance
 * and choose a model:
 *
 * <pre>{@code
 * spring:
 *   ai:
 *     ollama:
 *       base-url: http://localhost:11434
 *       embedding:
 *         model: nomic-embed-text   # or qwen3-embedding:0.6B, mxbai-embed-large, all-minilm, etc.
 * }</pre>
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Ollama must be running: {@code ollama serve}</li>
 *   <li>The chosen model must be pulled before first use: {@code ollama pull nomic-embed-text}</li>
 * </ol>
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embeds a single text string into a dense float vector using the configured Ollama model.
     *
     * @param text the input text to embed; must not be null or empty
     * @return a float array representing the embedding vector
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /**
     * Embeds multiple texts in a single request, which is more efficient than calling
     * {@link #embed(String)} repeatedly for bulk indexing workloads.
     *
     * @param texts the list of input texts to embed; must not be null
     * @return a list of float arrays, one embedding vector per input text, in the same order
     */
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
