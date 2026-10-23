# Lucentrix real-library acceptance — 2026-10-23

**Result: INCOMPLETE / FAILED on deletion.** #16 remains open. This document records what passed, what failed, and the exact blocker; it is evidence, not a fix.

## Revision and environment

| Item | Value |
|---|---|
| Lucentrix code under test | `1e7f6b541a323cab24ce05d5c565c9e55ddfc19a` (PR #24 merge; deletion slice) |
| CI for that code | [run 39961307242](https://github.com/synanton/lucentrix/actions/runs/39961307242), green |
| Later docs-only commit | `9935256` (PR #26, `Refs #16` convention); not part of the crawler under test |
| Platform | `local-real-demo` profile, fixed project `synanton-local-real-demo` |
| Platform endpoints | synflux `http://localhost:18090`, synquest `http://localhost:18083` |
| Tenant / source | `lucentrix-acceptance` / `fs-acceptance` (`connector_type=upload`) |
| Distribution | `distribution/target/metaframe-build-0.1.0-SNAPSHOT.zip`, started with the #27 workaround |

The distribution's `run-crawler.sh` cannot start the Spring Boot repackaged jar via `-cp`; see #27. The run used the original classes jar and an external `application.properties`:

```text
java -cp "config:lib/crawler-classes.jar:lib/*:." \
  org.lucentrix.ingest.crawler.IngestApplication --ingest --once --no-ui
```

## Corpus (hash-pinned)

`corpus-manifest.sha256` SHA-256:
`b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e`

| SHA-256 | Relative path |
|---|---|
| `04f0cba44882959ebc6699bab529f90e47505534b06e1357f0d77ccfdb3a21c4` | `library/level1/level2/level3/acceptance.epub` |
| `efdc607a19a80f5dd4d5a385baf876a08c5e51fdbaa08bc8332f16c422d4ae7e` | `library/level1/level2/level3/change-me.pdf` |
| `d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f` | `library/level1/level2/level3/duplicate.pdf` |
| `c5a2cb611100d8ba7697717bb9622f6726e7b0e17d41828b096a8e9454c1646c` | `library/level1/level2/level3/ignored.txt` |
| `d60e114c25f8bf7d69df2bdddead567b2958c8df326f30ccfb8800c3ebdb191f` | `library/level1/level2/level3/mental-health-report-2010.pdf` |
| `1d3e11e15bae66182e91b37fe07b7e9b49de613aece146f81756bce212a0b9cc` | `library/level1/level2/level3/remove-me.pdf` |
| `efdc607a19a80f5dd4d5a385baf876a08c5e51fdbaa08bc8332f16c422d4ae7e` | `library/level1/level2/locked/locked.pdf` |

The EPUB is a valid EPUB 3 with XHTML chapters, a table (`Program | Allocation`), a figure caption, and a code block. Its pinned sentence is:

> Lucentrix acceptance EPUB chapter one: the ledger balances at midnight.

## Results

| # | Criterion | Result | Observed |
|---|---|---|---|
| 1 | Discovery | **PASS** | 6 source versions: EPUB, change-me, duplicate, mental-health, remove-me, locked; `ignored.txt` absent; depth 3 reached. |
| 2 | Unchanged rescan | **PASS** | Run 2 emitted no `REPLACE`/`DELETE`; 6 versions before and after; all `version_id`s identical. Search projection was not rebuilt. |
| 3 | Changed file | **PASS** | Replaced `change-me.pdf` (`efdc607…`) with `quarterly-report.pdf`; exactly one new version `5e415504-b485-5dbf-a2c6-7622839e72b8`; all sibling `version_id`s unchanged. |
| 4 | Duplicate decision | **PASS** | `mental-health-report-2010.pdf` (`edb481c8…`) and `duplicate.pdf` (`6bdc0498…`) are two versions sharing artifact `3f5df5bd-789b-4de2-aa90-994198d69382`. `change-me.pdf` and `locked.pdf` also shared artifact `6f6e2274-94a1-4bfe-b70a-17a56df0238d` before the change. |
| 5a | Complete-scan delete | **FAIL** | `DELETE /v1/sources/fs-acceptance/resources?resource_id=…` returned HTTP 400. Platform issue [#408](https://github.com/synanton/platform/issues/408): the endpoint rejects `upload` sources in `requireActiveFilesystemSource`. The Lucentrix fingerprint for `remove-me.pdf` remained in state (6 fingerprints) and the next run re-attempted the same delete; no tombstone was created. |
| 5b | Incomplete-scan suppression | **NOT EXECUTED** | Blocked by 5a: the unresolved `remove-me.pdf` deletion caused run 6 to fail before the suppression scenario could be run cleanly. |
| 5c | Reappearance cancels delete | **PASS (test-level)** | `FileSystemSourcePluginTest.reappearedFileIsNotDeletedWhenEmissionResumes` in the code under test. |
| 6 | Interruption at `source.save()` | **NOT EXECUTED** | No fault-injection harness at this point existed in the run; atomic-save unit test is present, but the pinned interruption criterion was not exercised. |
| 7 | Sink failure re-emit | **PARTIAL** | The real HTTP 400 delete failure left the fingerprint in state and re-emitted on the next run. A specific HTTP 500 injection and the `404` idempotency retry were not exercised. |
| 8a | EPUB delivery | **PASS** | `POST /v1/ingestion-runs` returned a run/operation; source version `05540829…` with `application/epub+zip`. |
| 8b | EPUB extraction | **PASS** | `/v1/search` returned the pinned sentence; operation outcome `PARTIAL` (requested feature states), but the text is searchable. |
| 9 | PDF provenance | **PASS** | Fact `overall response rate for fiscal year 2007 was 78 percent` returned hit `292ce661-46fe-59cb-9cfe-bc5c3cbd7a65:5`; provenance lists source versions `edb481c8…` and `6bdc0498…`, digest `d60e114c…b191f`; canonical chunk page `2–2`, section `APPENDIX A: DATA SOURCE DESCRIPTIONS`. |
| 10 | Tenant isolation smoke | **PASS** | Tenant `lucentrix-other` returned 0 results for the EPUB sentence and 404 for the tenant-A provenance URL. |
| 11 | Rescan after interruption equals clean scan | **NOT EXECUTED** | Depends on criterion 6; no interruption was injected. |

## Findings

1. **Platform blocker — upload-source deletion.** `DELETE /v1/sources/{sourceId}/resources` rejects `upload` sources with 400. Tracked in [#408](https://github.com/synanton/platform/issues/408). Lucentrix's own delete request is correct; no client-side change will fix it.
2. **Distribution launcher.** `run-crawler.sh` uses `-cp` against the Spring Boot repackaged jar and cannot find the main class. Tracked in [#27](https://github.com/synanton/lucentrix/issues/27). The acceptance used a documented workaround; a clean distribution must start with one command.
3. **EPUB extraction is real but partial.** The pinned sentence and table/code content are searchable; the operation reports `PARTIAL`, so extraction is not silently failing.

## Decision

#16 is **not closed**. The next run requires #408 to be fixed, a named interruption harness at `source.save()`, and a clean re-run of criteria 5a, 5b, 6, 7, and 11. The passing results above remain valid for the code under test; they should not be re-interpreted as a full acceptance.

Local run artifacts (logs and JSON responses) were kept outside the repository under `/tmp/opencode/lucentrix-acceptance/`; the values above are the durable record.
