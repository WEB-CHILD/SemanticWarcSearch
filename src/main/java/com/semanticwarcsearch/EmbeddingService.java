package com.semanticwarcsearch;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    // Batch version — more efficient for bulk indexing
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
