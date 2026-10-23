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

## Post-run addendum (2026-10-23)

- **This run was source-based, not distribution-based.** It used the original
  classes jar with the distribution's `lib/` directory and an external
  `application.properties`; it does not validate the shipped `run-crawler.sh`
  or the repackaged jar. #27 tracks that packaging gap. The passing results
  remain valid for the connector code under test, not for the distribution
  artifact.
- **#408 was fixed after this run** in platform #409 (`04744208cf917fabac25900b5fe56dda4dc05ed3`).
  The failure record above remains historical: this run did not exercise the
  fix. Criteria 5a, 5b, 6, 7, and 11 still require a clean rerun with the
  interruption/failure-injection harness before #16 can close.
- **Artifact retention is intentional.** There is no reference counting. Reclaiming
  shared artifacts requires either reference counting or mark-and-sweep over live
  identities; neither is in scope. Disk growth from deleted duplicates is
  intentional retention, not a leak.
- **Permanent failures currently re-emit indefinitely.** Preserving the
  fingerprint on the 400 was the correct safety behavior, but a permanent
  contract failure should not look identical to a transient one. The
  transient/permanent classification is tracked in #29, within Lucentrix's
  reconciliation scope and separate from the deferred EWP-2 retry policy.

## Margin-stress corpus reproducibility (2026-10-23)

The deterministic generator is committed at
`scripts/acceptance/generate_margin_stress_corpus.py` with seed
`lucentrix-margin-stress-v1`. It generates 151 one-page PDFs (131 designated
tombstones with high term frequency, 20 live) containing the query term
`retention`; the public search limit is 20 and the over-fetch margin is 100, so
the top 120 is tombstone-dominated and the guard refetches to 220.

Regeneration check: generated twice into separate directories and compared:

```text
4869cb0907b3d6d29334d36f000a059860e9376b9f2d9b4147cfd847e28fc615
```

Both runs produced byte-identical manifests. The stress manifest hash is
`4869cb0907b3d6d29334d36f000a059860e9376b9f2d9b4147cfd847e28fc615`; core
corpus hash `b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e`
is unchanged. The 111/10 version was replaced after the public limit (20) made
its window too small to trigger the guard; this is a corpus-design correction,
not a product change.

## Second run — 2026-10-23 (observed values)

**Result: PASS for the acceptance criteria exercised by this run.** #16 can
close; the prior failed run remains preserved above.

| Item | Value |
|---|---|
| Platform code under test | `e5114918f7de24260eea340dffefcf24da927d89` (#410/#415) |
| Lucentrix crawler code | `1e7f6b541a323cab24ce05d5c565c9e55ddfc19a` |
| Harness version | `753fa52ba3c97c27336d78e145f4c3497be6512b` |
| Core corpus hash | `b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e` |
| Stress manifest hash | `4869cb0907b3d6d29334d36f000a059860e9376b9f2d9b4147cfd847e28fc615` |
| Tenant/source | `lucentrix-rerun` / `fs-rerun` |
| Stress tenant/source | `lucentrix-stress2` / `fs-stress2` |
| Execution | source-based (original classes jar + distribution libs); #27 open |

### Criterion results

1. **Discovery — PASS.** Run 1 produced 6 source versions under
   `level1/level2/level3` plus `level1/level2/locked`; `ignored.txt` was absent.
2. **Unchanged rescan — PASS.** Run 2 emitted no `REPLACE`/`DELETE`; 6 versions
   before and after; all `version_id`s identical. No projection rebuild was
   performed for this criterion.
3. **Changed file — PASS.** `change-me.pdf` was replaced with
   `quarterly-report.pdf`; exactly one new version `ccd58e6e-3e5a-542c-a985-f4d483c1e7f4`
   appeared, and every sibling retained its run-1 `version_id`.
4. **Duplicate decision — PASS.** `mental-health-report-2010.pdf`
   (`ceba99f6-8a6b-5b28-aab9-de5220d74031`) and `duplicate.pdf`
   (`3d6aeb96-4e2e-597b-9e06-735939844551`) are two versions sharing artifact
   `a3a32259-1a8c-4af9-9e98-b7196209a452`. `change-me.pdf` and `locked.pdf`
   also shared artifact `373d2241-d478-4cd8-ba4f-d57d80e01e80` before the
   change. The PDF fact hit carried both mental/duplicate version IDs.
5. **Deletion — PASS, two separate assertions.**
   - **5b suppression:** after deleting `remove-me.pdf` and making
     `level1/level2` unreadable, the scan emitted no `DELETE` and no error;
     state retained 6 fingerprints including `remove-me.pdf`.
   - **5a complete-scan delete:** after restoring permissions, the scan emitted
     one `DELETE` for `remove-me.pdf` (run `730a6be0-38ac-353f-a8b5-7661deee6ca3`,
     operation `21444f49-5302-4b10-8211-37ebd3872380`). Search for
     `Revenue grew in Q1.` returned only the surviving live sibling
     `change-me.pdf` version `ccd58e6e…`; provenance source identity was
     `manual-upload://fs-rerun/level1/level2/level3/change-me.pdf`. Repeating
     DELETE for `remove-me.pdf` returned `404` with no new operation. Deleting
     the last live referent `change-me.pdf` (run
     `bf6d5ecb-7efa-3608-a38a-dfce14b98097`) made the chunk disappear from
     search and its provenance `404`; the shared artifact remained.
6. **Interruption at source.save — PASS (test-level).**
   `FileSystemSourcePluginTest.interruptedAfterDeleteEmissionBeforeSave_reemitsWithoutOrphanedFingerprint`
   (harness `753fa52`): DELETE emitted, save skipped, restart re-emitted it.
7. **Named HTTP failures — PASS (test-level).**
   `SynantonClientTest.deleteHttp500PropagatesAsAFailure` and
   `deleteHttp404IsIdempotentAlreadyAbsent`: 500 propagates; 404 is already-absent.
   The first run's real 400 remains historical evidence; no fix was folded in.
8. **EPUB — PASS.** Version `2bd5c0e6-d34b-5947-9b8c-e0aa3c290854` with
   `application/epub+zip`; hit `03c1e9a8-ee0a-5192-b126-00392b8391de:0`
   returned the pinned sentence. Operation outcome was `PARTIAL`.
9. **PDF provenance — PASS.** Fact hit `683c831a-3b22-50f3-bfe5-6ac810a9e531:5`;
   provenance lists `ceba99f6…` and `3d6aeb96…`, digest `d60e114c…b191f`;
   canonical page 2 (`page_start=2`, `page_end=2`), section
   `APPENDIX A: DATA SOURCE DESCRIPTIONS`.
10. **Tenant isolation — PASS.** Tenant `lucentrix-rerun-other` returned 0
    results for the PDF fact and 404 for the tenant-A provenance URL.
11. **Rescan after interruption — PASS (test-level).** The same harness test
    leaves no orphaned fingerprint and a further scan is empty; post-recovery
    state is the clean-delete state.

### Guard-triggering stress run

- 151 documents, 131 tombstones, 20 live; query `retention`.
- Pre-delete clean-window search p50: **47.6 ms**.
- Post-delete guard search p50: **139.7 ms**; 20 live results returned.
- Refetch log (synquest):
  `Tombstone filtering refetch for tenant 'lucentrix-stress2': window 120 -> 220 for query 'retention'`.
- Negative control is the unit test
  `TombstoneFilteringTest.cleanWindowDoesNotTriggerARefetch` (one search call, no refetch).

### Caveats

- This run is **source-based**, not distribution-based; #27 remains open.
- This run proves search/provenance exclusion immediately after `202`; it does
  not prove crash durability between tombstone write and response. #412 owns
  that.
- Dense-leg RRF symmetry remains a recorded, unexercised gap; the current path
  is BM25-only.
