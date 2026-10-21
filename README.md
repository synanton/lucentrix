# Lucentrix

Lucentrix is a **pluggable ingestion CLI** for [Synanton](https://github.com/andreminin/synanton). It crawls source systems (dummy data, web, and future connectors), then **pushes raw documents** into Synanton through the platform `/v1` ingestion API. It does not parse, chunk, enrich, or index - those stages stay in Synanton.

## Status (Phase 1, 2026-10-21)

The filesystem source and `synanton-target-plugin` use the current
`POST /v1/sources` + multipart `POST /v1/ingestion-runs` contract. The legacy
`/content/{tenant}` and `/ingest` paths are removed and are not supported by
the sink.

Uploads can be large (the platform accepts multipart files up to its 100 MB
limit), but the platform's sync extraction ceiling
(`EXTRACTION_MAX_SYNC_OBJECT_BYTES`, default 10 MiB) still applies. Larger
files need the asynchronous extraction path/configuration.

A re-crawl treats `409` from `/v1/sources` as "source already exists" and
continues with the upload.

## Requirements

- Java 21
- Maven

## Build

```bash
mvn -DskipTests package
```

The Spring Boot CLI is `ingestion/crawler`. Plugins zip under `plugins/*/target`.

## Run

1. Start the Synanton platform (Cassandra, MinIO, synvault, synflux, synquest). See the Synanton README ingestion demo.
2. Copy `application.properties` next to the CLI (or use the packaged `config/`).
3. Point the sink at Synanton:

```properties
source.plugin.id=dummy-source-plugin
target.plugin.id=synanton-target-plugin
dummy-source-plugin.config=config/dummy.json
synanton-target-plugin.config=config/synanton.json
```

`config/synanton.json`:

```json
{
  "name": "synanton",
  "baseUrl": "http://<platform-node-ip>:30090",
  "apiKey": "<bearer token minted from the platform JWT secret>",
  "sourceId": "books-demo",
  "connectorId": "upload",
  "sourceEndpoint": "upload://books"
}
```

`baseUrl` is the platform `/v1` API (the synflux NodePort in the demo).
`apiKey` is the bearer token; the tenant comes from the token, never from a
request header. The sink registers the upload source once, then posts each
file as `multipart/form-data` to `POST /v1/ingestion-runs` with
`connector_id`, `source_id`, `resource_id`, and the file bytes. The legacy
`synfluxUrl`/`tenant` fields are not used by the current contract.

4. Start the CLI (status UI on port 8095 by default):

```bash
java -jar ingestion/crawler/target/crawler-0.1.0-SNAPSHOT.jar
```

Open http://localhost:8095 and click **Start**, or:

```bash
java -jar ingestion/crawler/target/crawler-0.1.0-SNAPSHOT.jar ingest --once --no-ui
```

- `--ingest` / non-option `ingest` starts a job immediately
- `--once` / `--no-ui` exits when the source is idle
- `GET /api/ingest/status` and `GET /api/ingest/events` (SSE) expose progress
- `POST /api/ingest/start` and `POST /api/ingest/stop` control the job from the UI

## Plugins

| Plugin id | Role |
|---|---|
| `dummy-source-plugin` | Synthetic insurance documents |
| `web-source-plugin` | BFS HTML crawler |
| `filesystem-source-plugin` | Incremental PDF/EPUB library crawler (SHA-256 fingerprints, resumable scan state) |
| `synanton-target-plugin` | Push each file as multipart `POST /v1/ingestion-runs` (upload source; tenant from the bearer token) |
| `null-target-plugin` | No-op sink for local throughput tests |

Source plugins implement `SourcePlugin`; sinks implement `SinkPlugin`. Domain types: `SourceDocument`, `ContentChange`, `ChangeOp`, `ChangePage`.

## Docs

- [`docs/synanton_ingestion.md`](docs/synanton_ingestion.md) — ingestion architecture (proposed design)
- [`docs/ticket-16-filesystem-source-connector.md`](docs/ticket-16-filesystem-source-connector.md) — plan for the filesystem source connector (#16)

## License

Apache 2.0 - see [LICENSE](LICENSE).
