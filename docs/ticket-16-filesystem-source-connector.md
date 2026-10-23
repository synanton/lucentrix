# Lucentrix #16 — Filesystem source connector (PDF/EPUB library crawling)

**Status:** Slices 1–4 implemented; real-library acceptance evidence pending
**Ticket:** Lucentrix #16
**Repo:** `lucentrix`
**Related:** Platform #328 (`synanton/platform` — shared ingestion contract / folder upload)

## Goal

Add a `filesystem-source-plugin` that incrementally crawls a local library of
PDF and EPUB files, detects additions/modifications/deletions, and emits
normalized `ContentChange`s through the existing sink so the documents become
searchable through the same Synanton pipeline as any other source.

The filesystem walk is the easy part; the real work is the **delivery metadata**
that makes this source converge with the shared ingestion contract (identity,
content digest, tenant, deletion), and the **incremental cursor** that keeps a
large personal library tractable.

## Non-goals

- SharePoint / FileNet / cloud-drive connectors (explicitly out of scope).
- EPUB *extraction* — discovering EPUB files does not assume downstream EPUB
  extraction works (`docs/synanton_ingestion.md` §16 routes EPUB by media type;
  the connector just delivers bytes).
- Document parsing, chunking, indexing — those stay in Synanton.
- Directory-name → tag inference. The connector does **not** derive tags from
  directory names; tags (if any) are assigned in the platform UI (see #328),
  not invented by the crawler.

## Verified current state

The kernel is reusable as-is; do not build a one-off crawler app.

| Fact | Where | Consequence for #16 |
|---|---|---|
| `SourcePlugin extends PersistableIterator<ChangePage>` | `plugin-api/.../SourcePlugin.java` | The connector is an iterator with `hasNext()`/`next()`/`save()` |
| `WebSourcePlugin` is the working template | `plugins/web-source-plugin/.../WebSourcePlugin.java` | Copy its `start()` cursor-load / `next()` page / `save()` shape |
| `Field` vocabulary already covers files | `core/.../field/Field.java` | `SOURCE_URI`, `SOURCE_ID`, `MIME_TYPE`, `CONTENT_BYTES`, `CONTENT_SIZE`, `MODIFY_DATETIME`, `TITLE`, `FILE_EXTENSION`, `IS_FOLDER`, `FOLDER_PATH`, `PATHS` |
| Cursor is one JSON file per plugin name (`FileJsonPersistence`) | `plugin-api/.../FileJsonPersistence.java` | A multi-thousand-file fingerprint map in the cursor is rewritten on every `save()` — decide sharding before load |
| Sink posts multipart `POST /v1/ingestion-runs` (slice 4); legacy pair removed | `plugins/synanton-target-plugin/.../SynantonClient.java` | Frozen `/v1` source + run + operation contract |
| Sink routes `DELETE` to the per-resource tombstone endpoint | `plugins/synanton-target-plugin/.../SynantonSinkPlugin.java`; `SynantonClient.delete` | Deletion is wired; a missing resource (`404`) is treated as already absent |

### The two checks, answered

1. **`POST /content/{tenant}` still exists** in `synvault`
   (`ContentPushController`), and **`content_ref_id` is `UUIDv5(tenant + ":" +
   sha256(bytes))`**, not the raw digest (`ContentPushPort`). The response returns
   `{content_ref_id, sha256, size_bytes}`. **The sink no longer uses it (slice 4);**
   it remains for other callers until #329. The connector therefore does **not**
   need to send a digest; the platform computes it. The connector's own SHA-256 is
   still required for *local* change detection.
2. **`FileJsonPersistence` does not scale to a megabyte cursor.** It rewrites the
   whole JSON on each `save()`. The fingerprint cursor must be sharded (see
   §Cursor design).

## Contract gaps that decide whether #16 is actually done

These are the "same pipeline, one contract" issues. Each is a decision, not a
given — record the decision in the PR.

1. **Identity — implemented (slice 4).** The platform's frozen source model is
   `{tenant_id, connector_id, source_id, resource_id}` + `content_digest` +
   `source_revision` (`platform/docs/api/source-model.md`). The multipart sink
   sends:
   - `connector_id` → configurable, default `upload` (the platform's caller-pushed
     connector).
   - `source_id` → `books-demo` (configurable, stable across runs).
   - `resource_id` → normalized connector-relative path, from `vsId` or the
     Lucentrix source URI.
   - The platform stores the upload label as
     **`upload://<source_id>/<resource_id>`**. The Lucentrix
     `lucentrix://filesystem/<root>/<rel-path>` URI remains a local label; the
     platform's `upload://` URI is what provenance returns.
2. **Tenant — implemented (slice 4).** The sink sends no `X-Tenant`; it presents a
   bearer token and the platform derives the tenant from the verified identity.
   `tenant` remains in the config for logging/backward compatibility only.
3. **Route — implemented (slice 4).** The sink now uses the frozen
   `/v1/sources` + `/v1/ingestion-runs` + `/v1/operations` contract (multipart).
   The legacy `/content/{tenant}` + `/ingest` pair remains for other callers until
   `synanton/platform` #329 removes it.
4. **DELETE — implemented.** The connector emits `DELETE` only after a
   complete scan, and the sink calls
   `DELETE /v1/sources/{id}/resources?resource_id=...`. An incomplete scan
   (unreadable directory/file, or an unclassifiable entry) sets a sticky
   `scanFailed` flag and skips reconciliation, so a partial scan can never
   tombstone previously ingested documents. The fingerprint is removed only
   when the `DELETE` is emitted into a page, and the runner pushes that page
   before `source.save()`, so a sink failure re-emits rather than losing the
   deletion.

## Design decisions

### Identity and content

- `connector_id = "filesystem"`, `source_id = <configured root id>`, `resource_id`
  = relative path with `/` separators, normalized (no `..`, no leading `/`).
- `SOURCE_ID` = root id; `SOURCE_URI` = `lucentrix://filesystem/<root-id>/<rel-path>`
  (a stable label, not a locator — see §Contract gaps item 1).
- `MIME_TYPE` derived from extension: `.pdf` → `application/pdf`, `.epub` →
  `application/epub+zip`. `FILE_EXTENSION` set. `TITLE` = filename stem initially
  (real metadata is the extractor's job).
- `CONTENT_BYTES` = raw bytes; `CONTENT_SIZE` = byte length; `MODIFY_DATETIME` =
  file mtime. SHA-256 of bytes computed locally for change detection; the digest
  may be surfaced on a field (e.g. `Field.of("content_hash", STRING)` holding
  `sha256:<hex>`) for sink-side idempotency, but synvault re-computes and
  dedupes by digest regardless.

### Discovery and change detection

- Recursive walk of the configured root (bounded depth), filtered by extension
  allowlist (`pdf`, `epub` by default).
- Strongest available signal is mtime + size; SHA-256 is the authoritative
  equality check for change detection (per platform source model, mtime+size is
  preferred over digest, but the local fingerprint compares digest to be safe).
- Emit `REPLACE` for new or changed files; `DELETE` for previously-known,
  now-absent files **only after a complete scan** (never on a partial/failed
  scan — mirror `SYNC_INCOMPLETE`).

### Cursor / checkpoint

- Shard the fingerprint map by subtree (e.g. one cursor per first-level
  directory, or a fixed-size bucket of paths) so a single `save()` is small.
- The cursor itself holds scan progress + a reference to the fingerprint store;
  fingerprints (path → size, mtime, sha256) live in the sharded side store.
- On `save()`, persist only the changed shards. On `start()`, reload and
  reconcile.

## Sliced implementation

| Slice | Deliverable | Status |
|---|---|---|
| 1 | `filesystem-source-plugin` + config + cursor: walk root, emit `REPLACE` per PDF/EPUB, identity fields set | Implemented (PR #18) |
| 2 | Fingerprint cursor (path → size, mtime, hash); changed file → new `REPLACE` | Implemented (PR #18) |
| 3 | Complete-scan reconciliation: `DELETE` only for previously-known, now-absent files, and only after a complete scan | Implemented; tests cover complete-scan delete, incomplete-scan suppression, and recovery after permissions return |
| 4 | Sink alignment: migrate `SynantonClient` to `/v1/...`; tenant from verified identity (no `X-Tenant` assertion); wire `DELETE` to the per-resource tombstone path | Implemented (PR #21); DELETE wiring added with `SynantonClient.delete` |

## Deletion behavior

- A path is a deletion candidate only when it is present in the persisted
  fingerprint state and absent from a scan that completed without a listing,
  traversal, or file-read failure.
- `scanFailed` is sticky for the scan. A failed directory listing, unreadable
  file, or unclassifiable filesystem entry suppresses all deletion
  reconciliation for that pass and leaves the fingerprints intact.
- Deletion candidates are removed from the fingerprint state only as they are
  emitted into a `ChangePage`; `IngestRunner` calls `target.push(page)` before
  `source.save()`, so a sink failure causes the deletion to be re-emitted on the
  next pass rather than silently lost.
- A `404` from the platform's resource-delete endpoint is treated as already
  absent. A successful delete returns the platform `run_id`/`operation_id` and
  is logged by the sink.

Slices 1–3 are pure Lucentrix work. Slice 4 decides whether "two ingestion
modes, one contract" is true, and is a prerequisite for calling #16 done
(its acceptance includes searchable documents through the same pipeline).

## New files

```
plugins/filesystem-source-plugin/
├── pom.xml
└── src/main/
    ├── resources/filtered/plugin.properties
    └── java/org/lucentrix/ingest/plugin/fs/
        ├── FileSystemSourceConfig.java     (JsonPluginConfig<Settings>)
        ├── FileSystemSourcePlugin.java     (implements SourcePlugin)
        ├── FileFingerprint.java            (path → size/mtime/sha256)
        └── FileScanState.java              (cursor + sharded fingerprints)
shared-assemblies/src/main/resources/assemblies/filesystem-source-assembly.xml
ingestion/crawler/src/main/external-resources/non-filtered/config/filesystem.json
```

Also: register the module in `plugins/pom.xml`; add the assembly descriptor to
`shared-assemblies`; document the plugin in `README.md`'s plugin table.

## Doc-vs-code drift to fix in the same PR

`docs/synanton_ingestion.md` is marked **Proposed** and describes a richer
`SourceConnector` with `discover()/search()/detectChanges()/retrieve()/describe()`
and an `IngestionDocument`; the implemented SPI is the simpler
iterator + `ContentChange`. Implement against the real SPI, and either update the
paper or mark it target-state, otherwise the next reader implements an interface
that does not exist.

## Definition of done

1. Slices 1–3 pass their acceptance tests (unit tests follow the
   `{Method}_condition_expected` convention used elsewhere in the repo).
2. `mvn -DskipTests package` builds the new plugin and the crawler CLI.
3. Slice 4 migrates `SynantonClient` to the `/v1/...` contract; identity, tenant,
   and `DELETE` are decided and implemented, not left as a gap.
4. EPUB real-file check: one real EPUB through the full pipeline yields
   searchable chunks (not `FEATURE_UNSUPPORTED`) — "listed as active" and
   "actually extracts" are different claims.
