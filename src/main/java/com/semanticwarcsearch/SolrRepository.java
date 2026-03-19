package com.semanticwarcsearch;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CursorMarkParams;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;

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
        solrClient.add(collection, buildUpdateDoc(id, fieldUpdates));
        solrClient.commit(collection);
    }

    /**
     * Applies atomic updates for every entry in {@code updates} and issues a single
     * commit at the end, which is far more efficient than committing after each document.
     *
     * <p>The stream is consumed lazily: documents are fetched, embedded, and sent to
     * Solr one by one without ever materialising the full set in memory.
     *
     * @param updates stream of (document-id, field-updates) pairs
     */
    public void atomicUpdateAll(Stream<Map.Entry<String, Map<String, Object>>> updates)
            throws SolrServerException, IOException {
        try {
            updates.forEach(entry -> {
                try {
                    solrClient.add(collection, buildUpdateDoc(entry.getKey(), entry.getValue()));
                } catch (SolrServerException | IOException e) {
                    throw new RuntimeException("Failed to add atomic update for id " + entry.getKey(), e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SolrServerException se) throw se;
            if (e.getCause() instanceof IOException ioe) throw ioe;
            throw e;
        }
        solrClient.commit(collection);
    }

    /** Builds a {@link SolrInputDocument} for an atomic partial update. */
    private static SolrInputDocument buildUpdateDoc(String id, Map<String, Object> fieldUpdates) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        for (Map.Entry<String, Object> entry : fieldUpdates.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof float[] floats) {
                List<Float> list = new java.util.ArrayList<>(floats.length);
                for (float f : floats) list.add(f);
                value = list;
            }
            doc.addField(entry.getKey(), Map.of("set", value));
        }
        return doc;
    }

    /**
     * Returns a lazy {@link Stream} over all documents matching the given query,
     * fetched from Solr in batches using cursor mark (deep-pagination) to avoid
     * memory pressure on large indexes.
     *
     * <p>The query <em>must</em> include a sort on the unique key field (e.g.
     * {@code sort=id asc}); this method appends {@code id asc} automatically if
     * no sort is present.
     *
     * @param baseQuery  the Solr query to execute (rows controls the batch size)
     * @param batchSize  number of documents fetched per round-trip
     * @return lazy stream of documents; close the stream when done to avoid leaks
     */
    public Stream<T> stream(SolrQuery baseQuery, int batchSize) {
        baseQuery.setRows(batchSize);
        if (baseQuery.getSortField() == null || baseQuery.getSortField().isEmpty()) {
            baseQuery.addSort("id", SolrQuery.ORDER.asc);
        }

        Iterator<T> iterator = new Iterator<>() {
            private String cursorMark = CursorMarkParams.CURSOR_MARK_START;
            private List<T> batch = Collections.emptyList();
            private int batchIndex = 0;
            private boolean exhausted = false;

            @Override
            public boolean hasNext() {
                if (batchIndex < batch.size()) return true;
                if (exhausted) return false;
                fetchNextBatch();
                return batchIndex < batch.size();
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return batch.get(batchIndex++);
            }

            private void fetchNextBatch() {
                try {
                    baseQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
                    QueryResponse response = solrClient.query(collection, baseQuery);
                    String nextCursorMark = response.getNextCursorMark();
                    batch = response.getBeans(type);
                    batchIndex = 0;
                    if (nextCursorMark.equals(cursorMark) || batch.isEmpty()) {
                        exhausted = true;
                    } else {
                        cursorMark = nextCursorMark;
                    }
                } catch (SolrServerException | IOException e) {
                    throw new RuntimeException("Failed to fetch next batch from Solr", e);
                }
            }
        };

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, ORDERED | NONNULL), false);
    }

    /**
     * Performs a KNN (k-nearest-neighbour) vector search against a
     * {@code DenseVectorField}.
     *
     * @param queryVector the dense query vector (dimension must match the field definition)
     * @param vectorField name of the {@code DenseVectorField} to search
     * @param topK        maximum number of nearest neighbours to return
     * @return list of closest documents ordered by similarity; may be shorter than
     *         {@code topK} when the index contains fewer documents
     */
    public List<T> searchByVector(float[] queryVector, String vectorField, int topK)
            throws SolrServerException, IOException {
        String knnParser = "{!knn f=" + vectorField + " topK=" + topK + "}" + formatVector(queryVector);
        SolrQuery knnQuery = new SolrQuery(knnParser).setRows(topK);
        QueryRequest request = new QueryRequest(knnQuery, SolrRequest.METHOD.POST);
        QueryResponse response = request.process(solrClient, collection);
        return response.getBeans(type);
    }

    /** Formats a float array as a Solr dense-vector literal, e.g. {@code [0.1,0.2,...]}. */
    private static String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public void close() throws IOException {
        solrClient.close();
    }
}
