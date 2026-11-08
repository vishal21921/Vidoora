# Phase 4 — private HLS processing

Approved by the user on 14 October 2026. Playback, publication, sharing, ads and payouts are outside this milestone. Remaining Phase 3.3 gaps stay in phase-3.3-audit.md.

## Operation and states

A separate `:server:runProcessor` JVM processes one job at a time. The API does not automatically transcode. `--once` (Gradle `-PprocessingOnce=true`) attempts one due job and returns a failing exit code if processing fails; no due job is reported explicitly.

Claiming is PostgreSQL-only: row locks with SKIP LOCKED, a unique attempt token, two-minute lease and twenty-second heartbeat. Completion/failure require the same token and a live lease. Expired attempts are fenced out, scheduled for cleanup and requeued with bounded retries. The original plus three retries is the default budget; normal transient failures back off for 1/2/4 minutes up to fifteen minutes. Video states: UPLOADED → PROCESSING → PROCESSED, or PROCESSING_FAILED. Jobs: QUEUED → PROCESSING → COMPLETED, retry QUEUED or terminal FAILED.

V2 is additive: processing lease/backoff fields, private output bucket/key fields and an attempt ledger. V1 is unchanged. Existing users/uploads are preserved. A processing completion is not a publication decision; visibility remains unchanged and published_at remains unset. There is no playback route or Android player yet.

## Media and storage rules

The worker downloads the acknowledged original with exact-size validation (1 GiB bound), probes locally and rejects missing video, invalid duration, more than two hours, dimensions over 4096, extreme aspect ratios, unsupported rotation and non-square-pixel sources. It accounts for right-angle rotation. It currently accepts the allowed MOV/MP4, Matroska/WebM, AVI, MPEG-TS, FLV and Ogg demuxers; unsupported sources fail explicitly. Extend this policy only with fixtures and tests.

Renditions fit 640×360, 854×480 and 1280×720 boxes, deduplicated for smaller sources, with even dimensions and no upscale. Portrait content retains its shape inside those boxes. H.264/yuv420p, AAC stereo when audio exists, fixed 30 fps, aligned six-second keyframes and VOD MPEG-TS HLS are produced sequentially. Silent video is supported. Master references are relative.

Outputs use a unique `processed/{videoUUID}/{attemptUUID}/` prefix. Segments upload first, variant playlists next and master last; every PUT is followed by a size HEAD. Only then are renditions, master key and job/video state committed atomically. `master_playlist_url` stays null. Legacy rendition `playlist_url` holds an internal `r2://` locator, not a publicly usable URL; `playlist_object_key` stores the explicit key. Future playback must resolve private keys through an access-control design. Do not make the bucket public.

The local launcher reuses `vidoora-test-upload-verification` for originals and processed prefixes, with the existing scoped credentials. A dedicated production processed bucket is not provisioned. Storage credentials stay backend-only. For another private processed bucket, scope the token to both required buckets before starting the worker.

## Resource limits and cleanup

One job, sequential variants, two decoder/encoder threads, one-hour transcode wall-time, one-minute probe timeout, six-GiB output bound, two-MiB process log limit and eight-GiB free scratch precondition. Processes use argument arrays, restricted input protocols/demuxers, no shell and no network media inputs. Lease loss kills running transcoding; normal JVM shutdown kills child processes. Diagnostics contain static stages/classes, not FFmpeg/provider message text or signed URLs.

Normal attempts remove scratch in finally. Abandoned owned scratch directories older than a day are swept at processor start. Failed/expired attempt prefixes have durable CLEANUP records, delayed an hour to let in-flight stale requests finish. Cleanup only deletes validated attempt prefixes; failed deletion retains the ledger for retry. Committed prefixes and originals are retained. Hard kill and OS restart can delay cleanup until a processor is running again.

These are application-level bounds, not OS isolation or a complete memory sandbox. Before public untrusted uploads, run a patched FFmpeg under an isolated restricted account/container with CPU/memory/disk limits, monitoring and durable backups. No thumbnail, moderation, CDN, public release or production capacity guarantee is included.

## Windows use

FFmpeg 9.0.2 essentials was downloaded from gyan.dev (linked by ffmpeg.org) and checked against the publisher's SHA-256. It is local development tooling under ignored `build/phase4-tools/extracted`, not committed source. The executables are selected by the launcher. New machines must install/configure FFmpeg and ffprobe independently.

Use `Start-Vidoora.cmd` for the API. Run `Start-VidooraProcessor.cmd` in the same local outputs folder for one processing attempt; it reuses encrypted Windows credentials without prompting again. The API window can stay open. The processor window may exit after its one attempt. To continuously process queued videos:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Start-VidooraBackend.ps1" -Processor -Continuous
```

For manual environment-based setup from the repository:

```powershell
# Privately load the usual database/R2/JWT configuration first.
# Set R2_PROCESSED_BUCKET_NAME, FFMPEG_PATH, FFPROBE_PATH and PROCESSING_WORK_DIR.
.\gradlew.bat :server:runProcessor '-PprocessingOnce=true' '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

The local phone still depends on the laptop API and USB reverse; this worker does not provide cloud hosting. Processing can take time; UPLOADED in the current Android queue refers to transfer completion.

## Test commands and evidence

```powershell
$env:VIDOORA_FFMPEG_TEST = 'true'
$env:VIDOORA_POSTGRES_TEST = 'true'
# Optional fully live synthetic pipeline: requires privately loaded R2/PG values and test bucket.
$env:VIDOORA_LIVE_PROCESSING_TEST = 'true'
$env:R2_TEST_BUCKET = 'vidoora-test-upload-verification'
.\gradlew.bat :server:test '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

LiveProcessingIntegrationTest creates its own synthetic original, private PostgreSQL schema and R2 attempt prefixes; it downloads HLS from R2 and decodes it. Finally it attempts cleanup of only its own objects/schema/scratch. It does not delete user originals or reset public tables. PostgreSQL-only integration tests also use fresh schemas. Without each opt-in, the corresponding integration test is skipped.

Executed verification on 14 October 2026: BUILD SUCCESSFUL; JUnit reports inspected: **28 passed, 1 skipped, 0 failures/errors**. Existing backend suites: 16 passed; ProcessingTest: 9 passed; FfmpegIntegrationTest: 1 passed (audio and silent fixtures, 360/480/720 generation and decode); PostgresProcessingIntegrationTest: 1 passed (real PostgreSQL 16.15, fresh-schema V1/V2, concurrent claims, fencing, backoff and ownership cleanup); LiveProcessingIntegrationTest: 1 passed (real R2 + PostgreSQL + FFmpeg, synthetic fixture, HLS downloaded from R2 and decoded, scoped cleanup). LiveR2IntegrationTest was skipped in this run; its separate 13 October result remains historical evidence. These totals do not include an Android rerun.

Executed reproducible local helper: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File outputs/Run-Phase4Verification.ps1 -Tests`. It privately loads the existing DPAPI credentials, sets the three new integration opt-ins and R2_TEST_BUCKET, and invokes `gradlew.bat test -Pkotlin.compiler.execution.strategy=in-process --no-daemon --no-configuration-cache --console=plain` in `outputs/phase4-verification`. Source/resource hashes must match the repository before using this server-only verification copy. JUnit evidence is in that directory's build/test-results/test; the repository's old reports are not substituted for this run. An executed processor attempt against older queued data encountered NoSuchKey for its original; this was not accepted as successful processing. The additive V2 migration applied to the local public schema, and the missing-original job is retained as a processing failure; no source object was deleted by that attempt.

The original Gradle 9.6 / Java 25 repository build encountered a sandbox-specific File.canWrite directory failure. Validation uses an exact server source/resource copy under the workspace with the same Kotlin/dependency versions and Gradle 9.6, a server-only settings file and Java 17 daemon/toolchain. The repository Java 25 daemon criteria and Android configuration are unchanged. No Android build or test is claimed for this milestone.

## Changed files

- server/src/main/resources/db/migration/V2__processing_leases.sql: additive processing lifecycle schema.
- server/src/main/kotlin/com/vidoora/backend/infrastructure/DatabaseFactory.kt: short JDBC transaction helper for PostgreSQL locking.
- server/src/main/kotlin/com/vidoora/backend/infrastructure/S3StorageService.kt: bounded original download, processed PUT/HEAD and owned-prefix cleanup.
- server/src/main/kotlin/com/vidoora/backend/modules/processing/: policy/model, PostgreSQL repository, subprocess transcoder, worker and separate entry point.
- server/build.gradle.kts: runProcessor task and one-shot option.
- server/src/test/kotlin/com/vidoora/backend/{ProcessingTest,FfmpegIntegrationTest,PostgresProcessingIntegrationTest,LiveProcessingIntegrationTest}.kt: policy/worker, real media, PostgreSQL and live pipeline checks.
- AGENTS.md and docs/VIDOORA_MASTER_PLAN.md: supplied permanent context installed with the explicit Phase 4 approval recorded.
- .env.example, README.md, docs/phase-3.3-audit.md and this file: actual setup, evidence, boundaries and remaining risks.
- Local outputs launchers: reuse DPAPI credentials, select local tools and start one-shot or continuous processing. No credential values are embedded.


### Existing queued video — executed live result

The next eligible existing public-schema job, `922b3c67-d2da-4f50-8618-6d50f8a3dd4c`, completed through the real processor on 14 October at approximately 18:32 IST. The one-shot command returned BUILD SUCCESSFUL and logged Processing completed. A separate read-only JDBC query confirmed video PROCESSED, job COMPLETED, duration 7 seconds, 3 rendition rows, a private master key and published_at still null. Each real output PUT was size-checked by HEAD before commit. The synthetic live test separately downloaded and decoded its HLS. The existing video's delivered HLS was not independently downloaded/decoded in this check.

One older job encountered an original missing in R2 and was retained as PROCESSING_FAILED/FAILED after diagnostic retries; no source was deleted to resolve it. Do not manufacture a replacement original or mark it complete. A fresh upload is required if that source is no longer available.

Launcher syntax and processor CheckOnly succeeded without printing credentials. Verification source/resources were hash-compared byte-for-byte against server/src. No Android source was changed; no Android test/build, cloud deployment or player is claimed. Remaining Phase 4 acceptance includes wider real media (portrait/rotation, malformed sources and long videos), process/host interruption during active transcoding, timed orphan cleanup, resource/load/cost measurement and a broader private-delivery design before playback.
