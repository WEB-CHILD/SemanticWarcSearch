# SemanticWarcSearch

This repository contains an initial approach to adding dense vector search to an already existing Solr index of half a billion documents. The application should be compatible with already existing solr indexes backing SolrWayback installations around the world. 

Currently the project is thought of as follows:
1. Content and id fields from already indexed documents are retrieved from solr
2. A small multimodal model is used to compute a DenseVector
3. An atomic update with the dense vector is then indexed on the document

The application uses atomic updates, so that it can be run as a post processing stes ater initial indexing with the WarcIndexer-tool from netarchive suite. This application should also be available as a java package, so that future indexing applications/or updates to the current WarcIndexer can implement these core features. 

When the index has been updated. The SolrWayback frontend needs to be able to connect to the same model for vectorising queries into this field. 

## Prerequisites

- Java 21
- Maven
- Docker (for running tests)

## Building

```bash
mvn clean package
```

## Running Tests

Tests use [Testcontainers](https://www.testcontainers.org/) to spin up a Solr instance in Docker automatically.

### Docker Setup on macOS

1. **Install Docker Desktop** from [docker.com](https://www.docker.com/products/docker-desktop/).

2. **Ensure Docker is running** before executing tests:
   ```bash
   docker info
   ```

3. **If Testcontainers cannot find Docker**, set the `DOCKER_HOST` environment variable pointing to the Docker Desktop socket:
   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock
   ```
   Add this to your `~/.zshrc` to make it permanent.

   Alternatively, create `~/.testcontainers.properties`:
   ```properties
   docker.host=unix:///Users/<your-username>/.docker/run/docker.sock
   ```

4. **Run the tests:**
   ```bash
   mvn clean test
   ```

## Configuration

| Property         | Default                        | Description          |
|------------------|--------------------------------|----------------------|
| `solr.base-url`  | `http://localhost:8983/solr`   | Solr connection URL  |
