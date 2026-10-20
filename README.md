# Lucentrix

Lucentrix is a **pluggable ingestion CLI** for [Synanton](https://github.com/andreminin/synanton). It crawls source systems (dummy data, web, and future connectors), then **pushes raw documents** into Synanton (`synvault` + `synflux`). It does not parse, chunk, enrich, or index - those stages stay in Synanton.

## Requirements

- Java 21
- Maven

## Build

```bash
mvn -DskipTests package
```

The Spring Boot CLI is `ingestion/crawler`. Plugins zip under `plugins/*/target`.

## Run

1. Start Synanton ingestion stack (Cassandra, MinIO, synvault, synflux). See the Synanton README ingestion demo.
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
  "baseUrl": "http://localhost:8088",
  "synfluxUrl": "http://localhost:8090",
  "tenant": "demo",
  "apiKey": ""
}
```

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
| `synanton-target-plugin` | Push bytes to synvault `POST /content/{tenant}`, then enqueue synflux `source=synvault` |
| `null-target-plugin` | No-op sink for local throughput tests |

Source plugins implement `SourcePlugin`; sinks implement `SinkPlugin`. Domain types: `SourceDocument`, `ContentChange`, `ChangeOp`, `ChangePage`.

## Docs

- [`docs/synanton_ingestion.md`](docs/synanton_ingestion.md) — ingestion architecture (proposed design)
- [`docs/ticket-16-filesystem-source-connector.md`](docs/ticket-16-filesystem-source-connector.md) — plan for the filesystem source connector (#16)

## License

Apache 2.0 - see [LICENSE](LICENSE).
