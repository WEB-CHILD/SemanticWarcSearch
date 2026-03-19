package com.semanticwarcsearch;

import org.apache.solr.client.solrj.request.SolrQuery;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

/**
 * Service that streams {@link WarcDocument} instances from Solr for downstream processing
 * (e.g. dense-vector embedding and atomic re-indexing).
 *
 * <p>Documents are fetched lazily in batches using cursor mark pagination,
 * so memory usage stays constant regardless of index size.
 */
@Service
public class DocumentStreamService {

    private static final int BATCH_SIZE = 100;

    private final SolrRepository<WarcDocument> repository;

    public DocumentStreamService(SolrRepository<WarcDocument> repository) {
        this.repository = repository;
    }

    /**
     * Returns a lazy stream of every document in the Solr collection,
     * projecting only the {@code id} and {@code content} fields.
     */
    public Stream<WarcDocument> streamAllDocuments() {
        SolrQuery query = new SolrQuery("*:*");
        query.setFields("id", "content");
        return repository.stream(query, BATCH_SIZE);
    }
}
