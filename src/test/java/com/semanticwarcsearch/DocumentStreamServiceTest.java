package com.semanticwarcsearch;

import org.apache.solr.client.solrj.request.SolrQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentStreamServiceTest {

    @Mock
    private SolrRepository<WarcDocument> repository;

    private DocumentStreamService service;

    @BeforeEach
    void setUp() {
        service = new DocumentStreamService(repository);
    }

    /** Verifies that streamAllDocuments issues a wildcard query to retrieve all documents. */
    @Test
    void streamAllDocumentsUsesWildcardQuery() {
        when(repository.stream(any(SolrQuery.class), anyInt())).thenReturn(Stream.empty());

        service.streamAllDocuments();

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(repository).stream(captor.capture(), anyInt());
        assertEquals("*:*", captor.getValue().getQuery());
    }

    /** Verifies that streamAllDocuments projects only the id and content fields. */
    @Test
    void streamAllDocumentsProjectsIdAndContentFields() {
        when(repository.stream(any(SolrQuery.class), anyInt())).thenReturn(Stream.empty());

        service.streamAllDocuments();

        ArgumentCaptor<SolrQuery> captor = ArgumentCaptor.forClass(SolrQuery.class);
        verify(repository).stream(captor.capture(), anyInt());
        String fields = captor.getValue().getFields(); // getFields returns a comma-separated string of projected fields
        assertTrue(fields.contains("id"), "should project id field");
        assertTrue(fields.contains("content"), "should project content field");
        assertEquals(2, fields.split(",").length, "should project exactly id and content");
    }

    /** Verifies that streamAllDocuments returns the stream produced by the repository. */
    @Test
    void streamAllDocumentsReturnsRepositoryStream() {
        Stream<WarcDocument> expected = Stream.empty();
        when(repository.stream(any(SolrQuery.class), anyInt())).thenReturn(expected);

        Stream<WarcDocument> result = service.streamAllDocuments();

        assertSame(expected, result);
    }

    /** Verifies that streamAllDocuments passes a positive batch size to the repository. */
    @Test
    void streamAllDocumentsUsesPositiveBatchSize() {
        when(repository.stream(any(SolrQuery.class), anyInt())).thenReturn(Stream.empty());

        service.streamAllDocuments();

        ArgumentCaptor<Integer> batchCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(repository).stream(any(), batchCaptor.capture());
        assertTrue(batchCaptor.getValue() > 0, "batch size should be positive");
    }
}
