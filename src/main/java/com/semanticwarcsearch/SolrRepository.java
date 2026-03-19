package com.semanticwarcsearch;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Generic Solr repository providing typed query and atomic update operations
 * for a given collection and document type {@code T}.
 *
 * <p>Document beans must use SolrJ {@code @Field} annotations so that
 * {@link QueryResponse#getBeans(Class)} can map results to {@code T}.
 *
 * <p>Usage example:
 * <pre>{@code
 * try (SolrRepository<WarcDocument> repo =
 *         new SolrRepository<>("http://localhost:8983/solr", "warc", WarcDocument.class)) {
 *
 *     List<WarcDocument> results = repo.query(new SolrQuery("content:java"));
 *
 *     repo.atomicUpdate("doc-1", Map.of("title", "New Title", "score", 0.9));
 * }
 * }</pre>
 */
public class SolrRepository<T> implements Closeable {

    private final SolrClient solrClient;
    private final String collection;
    private final Class<T> type;

    public SolrRepository(String baseUrl, String collection, Class<T> type) {
        this.solrClient = new HttpJdkSolrClient.Builder(baseUrl).build();
        this.collection = collection;
        this.type = type;
    }

    /**
     * Executes a {@link SolrQuery} and returns the matching documents as a typed list.
     *
     * @param query the Solr query to run
     * @return list of documents of type {@code T}; empty list if nothing matched
     */
    public List<T> query(SolrQuery query) throws SolrServerException, IOException {
        QueryResponse response = solrClient.query(collection, query);
        return response.getBeans(type);
    }

    /**
     * Performs an atomic (partial) update on an existing document.
     * Only the specified fields are changed; all other fields remain untouched.
     *
     * <p>Each entry in {@code fieldUpdates} is applied with the {@code set} modifier,
     * which replaces the field value. Use {@code null} as the value to remove a field.
     *
     * @param id           the unique id of the document to update
     * @param fieldUpdates map of field name → new value
     */
    public void atomicUpdate(String id, Map<String, Object> fieldUpdates)
            throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        for (Map.Entry<String, Object> entry : fieldUpdates.entrySet()) {
            doc.addField(entry.getKey(), Map.of("set", entry.getValue()));
        }
        solrClient.add(collection, doc);
        solrClient.commit(collection);
    }

    @Override
    public void close() throws IOException {
        solrClient.close();
    }
}
