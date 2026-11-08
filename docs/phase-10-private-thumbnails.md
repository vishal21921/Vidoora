# Phase 10 — Private thumbnails in My Videos

Approved 26 October 2026. The user reported quality selection works and confirmed playback speed was already available; no duplicate speed feature was added. Scope: recognizable thumbnail previews for the uploader's private My Videos library, including existing processed videos.

## Generation, existing videos and storage

- New processing attempts generate a 480×270 JPEG after valid HLS output. FFmpeg reads at most the first two seconds, scales/letterboxes without stretching, selects a representative frame from up to 30 downscaled frames and strips metadata/audio/subtitles/data. Tool execution uses argument arrays, restricted local file/pipe protocols/formats, two threads, a 30-second deadline and the existing bounded diagnostics/disk monitoring.
- The JPEG is at most 256 KiB, uploaded with image/jpeg to the private committed HLS attempt prefix as thumbnail.jpg. Existing provider HEAD size verification applies. The processing completion transaction stores the object key only if the generated file exists. HLS remains master-last/lease-fenced; a preview-generation failure is deferred rather than discarding valid video processing output.
- Old processed videos can be backfilled without reuploading/transcoding the original. A separate maintenance pass reads the private master, lowest-resolution variant and one first segment (playlists at most 256 KiB each; segment at most 16 MiB), then generates/uploads the same bounded JPEG. All playlist paths are validated; no external manifest URLs are followed. Scratch requires 64 MiB free and is removed in finally under the existing validated attempt-root rule.
- Backfill claims use PostgreSQL row locks/SKIP LOCKED, a random token, a two-minute lease and heartbeat every 20 seconds. Complete is token/lease/master/bucket/state-fenced. Failed attempts wait at least a minute and are capped at three claims, including interrupted claims. No tight retry loop; exhausted attempts stay placeholders until investigated/reset deliberately by an operator. Originals/HLS/playback are untouched.
- Backfill writes a deterministic thumbnail key under the existing immutable committed attempt. Repeated writes are idempotent; interruption after upload/before metadata commit can be reconciled by retry without deleting video files. No storage calls/tool execution are held inside the short database claim/complete transactions.
- Normal processor idle loops try a missing preview when no video-processing job is due. `-PthumbnailBackfill=true` selects a thumbnail-only pass, bounded to 100 due videos, stopping when none are due or an attempt fails. It does not claim/transcode queued video jobs. Normal one-shot processing remains supported.

## Private delivery and Android UI

- Both owner library DTOs include a thumbnailAvailable boolean, never an R2/public/signed URL.
- `GET /api/v1/videos/{id}/thumbnail` requires a normal access JWT, active account, actual uploader and processed state. A valid share capability is not thumbnail permission. It checks that the key is exactly thumbnail.jpg beside that video's committed master, shares the four-read storage limit, bounds JPEG bytes and responds image/jpeg, Cache-Control: no-store and nosniff. Missing previews return the existing not-ready response; other accounts get Video unavailable. Playback asset routes still reject thumbnail.jpg.
- Android renders a 16:9 preview at the top of each My Videos card, using the existing refresh-capable authenticated Ktor client. Requests are streamed and bounded to 256 KiB. JPEG content type/signature and decode bounds (at most 480×270) are checked before bitmap allocation. Four concurrent image loads per library instance; decoding runs off the main thread.
- There is no disk cache or account-shared thumbnail cache. The loader belongs to the library ViewModel, and image state lives with the composed library item. Leaving a screen/account cancels its load/composition; normal sign-out/navigation clearing remains unchanged. Refresh/reopening reloads previews through authorization. No credential/share token enters a thumbnail URL.
- Missing, loading, offline, oversized or malformed images show a placeholder. Unprocessed uploads show Preview after processing; a processed video without a loaded image shows Preview unavailable. Thumbnails never block View video, search, editing, sharing, Block/Unblock, quality selection or playback speed. No video becomes public and no Report option returns.

## Migration and files

V7 adds nullable thumbnail_object_key/thumbnail_worker_id/thumbnail_lease_until, retry count and next-attempt time plus a partial pending index. Existing thumbnail_url remains unused; no public URLs are inferred. V1–V6 are not edited. No video, upload, report or storage object is deleted/reset.

Backend changes:
- server/src/main/resources/db/migration/V7__private_thumbnails.sql
- server/src/main/kotlin/com/vidoora/backend/modules/video/VideoTables.kt (new fields)
- server/src/main/kotlin/com/vidoora/backend/modules/processing/FfmpegTranscoder.kt (bounded JPEG generation)
- server/src/main/kotlin/com/vidoora/backend/modules/processing/PostgresProcessingRepository.kt (atomic preview key commit)
- server/src/main/kotlin/com/vidoora/backend/modules/processing/ThumbnailWorker.kt (leased older-video backfill)
- server/src/main/kotlin/com/vidoora/backend/modules/processing/ProcessingMain.kt (idle/thumbnail-only processing)
- server/src/main/kotlin/com/vidoora/backend/infrastructure/S3StorageService.kt (JPEG content type)
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt and PlaybackRoutes.kt (owner-only delivery and boolean)
- server/build.gradle.kts (thumbnailBackfill processor argument)
- server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt, FfmpegIntegrationTest.kt, LiveProcessingIntegrationTest.kt (owner isolation/response bounds, real JPEG dimensions/content type and leased backfill)

Android changes:
- app/src/main/java/com/example/vidoora/feature/playback/PrivateThumbnailLoader.kt (bounded authenticated stream/decode)
- app/src/main/java/com/example/vidoora/feature/playback/VideoThumbnail.kt (image/placeholder)
- app/src/main/java/com/example/vidoora/feature/playback/VideoLibraryViewModel.kt and VideoLibraryScreen.kt (availability/loader/card)
- app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt (loader callback)
- app/src/test/java/com/example/vidoora/PrivateThumbnailTest.kt (private path, invalid ID, JPEG/type/size/dimensions and no image reuse)

AGENTS.md, README and master plan record the scope/evidence. No replacement app/dependency was introduced. Backup: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\thumbnails-before.

## Verification

Executed 26 October 2026: **Android 55 passed, zero skipped/failures/errors; backend 54 passed, one skipped, zero failures/errors; debug APK built.** Both final Gradle commands completed successfully and JUnit XML counts were inspected. The skipped suite is the older LiveR2IntegrationTest without its separate opt-in. Real local FFmpeg JPEG/HLS generation, isolated PostgreSQL V7 migrations, live R2 JPEG PUT/HEAD/GET and older-video backfill executed in the extended processing fixture, including lease replacement/fencing, delayed retry and viewer denial. Source hashes match the verification copies; V1–V6 hashes are unchanged.

Existing-library maintenance also executed successfully via Prepare-VidooraThumbnails.ps1. This applied V7 to the normal public schema from V6 and generated three private preview objects. A separate read-only psql aggregate afterward confirmed processed_videos=3, previews_ready=3, previews_pending=0 and schema_version=7. Only preview metadata/objects were added; video originals/HLS were not reprocessed/deleted. This maintenance evidence is separate from integration tests. The regular API process was not restarted and no phone app installation/UI test was performed by the agent.

Built APK: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-thumbnails-debug.apk. Supporting helper/status scripts and the report copy are in that outputs directory. Phone appearance/account-switch acceptance remains pending.

Standard project commands, with credentials privately loaded in the same session:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
$env:VIDOORA_POSTGRES_TEST='true'
$env:VIDOORA_FFMPEG_TEST='true'
$env:VIDOORA_LIVE_PROCESSING_TEST='true'
# R2_TEST_BUCKET must name the dedicated vidoora-test-* private bucket.
.\gradlew.bat :server:test --tests com.vidoora.backend.FfmpegIntegrationTest --tests com.vidoora.backend.LiveProcessingIntegrationTest --tests com.vidoora.backend.PlaybackSecurityTest --rerun-tasks
# Bounded backfill; not a test command:
.\gradlew.bat :server:runProcessor -PthumbnailBackfill=true
```

Actual sandbox verification uses the established Gradle 9.6/Java 17 server/Android copies, same source/dependencies, Android copied SDK and offline Robolectric init script. Original project Java 25 daemon settings remain unchanged. Backend helper privately imports the saved Windows-encrypted credentials and restores environment variables. Opt-in PostgreSQL/R2 tests use generated temporary schemas and synthetic object prefixes, cleaning only their own fixtures.

Executed entry points:

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-Phase4Verification.ps1' -Tests
# outputs\phase5-verification, JAVA_HOME = existing Java 17 toolchain:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Existing-video maintenance helper: outputs\Prepare-VidooraThumbnails.ps1 (or its .cmd launcher) imports the same encrypted credentials privately and invokes thumbnail-only processing in the matching verification copy. It may apply pending additive Flyway migrations normally; it adds private preview objects/metadata without deleting or retranscoding original/HLS content. Execution evidence is recorded separately below. This is not a unit-test result.

Initial Android tests exposed nullable decoder MIME metadata in legacy Robolectric. The loader accepts absent optional decoder MIME after validating the declared JPEG type, SOI bytes and bounded positive dimensions; an explicit incompatible decoder MIME is still rejected. HTTP/decoder MIME comparisons are case-insensitive. The valid-JPEG and malformed/oversized image tests remain enabled. One verification launch also hit a transient Gradle transform-cache AccessDeniedException before testing; the retry uses two Gradle workers. Neither failed attempt is counted as passing. Final suite results are recorded above only after execution.

## Costs, limits and phone acceptance

Each ready video adds one JPEG up to 256 KiB and its PUT/HEAD operations. Backfill reads three bounded HLS assets per attempt plus writes; opening/refreshing the library issues authenticated GETs because no shared disk cache exists. API bandwidth/memory and worker CPU/storage costs remain; this is not a CDN/scale claim. No hosted service, paid deployment, ad system, payout or deletion feature is added.

Restart Start-Vidoora.cmd to load the new route/DTO/schema, then Sync and Run the existing Android project. Keep app data and normal USB reverse mapping. Home → My Videos should show prepared previews, with placeholders for pending/unprocessed/missing media. Scroll/search/load older pages, edit a title, switch accounts in both directions and verify no other owner's preview appears. Offline images must fail gracefully while controls stay usable. A shared-link viewer still cannot fetch the owner-only thumbnail endpoint. Confirm actual phone appearance/account-switch behavior before moving to another feature.



