package com.semanticwarcsearch;

import org.apache.solr.client.solrj.beans.Field;

/**
 * Represents a document retrieved from Solr with the fields required for embedding generation.
 */
public class WarcDocument {

    @Field
    private String id;

    @Field
    private String content;

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public void setId(String id) {
        this.id = id;
    }
    
    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return "WarcDocument{id='" + id + "'}";
    }
}
