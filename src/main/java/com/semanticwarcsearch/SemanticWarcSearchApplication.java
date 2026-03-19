package com.semanticwarcsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SemanticWarcSearchApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SemanticWarcSearchApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(SemanticWarcSearchApplication.class, args);
    }

    @Override
    public void run(String... args) {
        logger.info("SemanticWarcSearch started");
        // TODO: implement WARC file processing and semantic search logic here
    }

}
