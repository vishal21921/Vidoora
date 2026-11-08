# Phase 11 — Owner-only video deletion and durable storage cleanup

Approved 26 October 2026 after the user reported thumbnails appeared. Scope: Delete video with explicit confirmation, immediate access revocation, and safe retryable removal of its original/HLS/thumbnail storage assets. This does not authorize deleting any existing user upload during development; tests use only generated synthetic fixtures.

## User flow and API

Owner details expose Delete video → Delete permanently / Cancel. The confirmation says the action cannot be undone, sharing stops, and storage cleanup follows. A viewer has neither UI permission nor backend permission. The client sends DELETE only through the confirmed action, keeps normal details on retryable failure, clears stale permission on 401/403/404, and never claims acceptance without a successful accepted response. A lost response says deletion could not be confirmed and offers refresh/retry rather than asserting the server did nothing.

`DELETE /api/v1/videos/{id}` requires a normal access JWT and the active actual uploader. A share capability is not deletion permission. Successful requests return the standard accepted result and no-store; repeating the same owner's request is idempotent. Unknown/foreign targets return 404 without storage calls. If an upload session is ACTIVE, return 409 with instructions to cancel the upload in Upload Queue first or finish it; existing provider cancellation closes issued multipart URLs. Completed, cancelled, queued/processing and failed video states can otherwise be deleted.

Acceptance locks upload sessions first, processing jobs second, and the video row last, after verifying ownership. In one transaction it sets deleted_at/publication_state=DELETED, disables sharing, rotates its generation, erases title/description, cancels processing jobs and clears processing/thumbnail worker permissions. The uploader's library/search excludes tombstones. Owner and viewer details, playlists, segments, thumbnails, edits, sharing and new processing/backfill access are denied. Completed-upload retries are explicitly rejected before their old idempotent job-repair branch, so an old receipt cannot recreate a deleted video/job.

The client clears only this video's remembered capability and shows that storage cleanup is queued. Back returns to the library, which refreshes as before. Other videos, capabilities, users and block preferences are unaffected. Local Upload Queue entries are historical transfer receipts, not a playable cloud library; this increment does not erase those device history rows. A minimal backend tombstone and operational upload/processing/history references are retained for retry/idempotency; this is not an account-erasure feature or a restore bin.

Already buffered/authorized in-flight media cannot be recalled. Subsequent authorized endpoint requests are denied immediately after acceptance; physical removal is a separate asynchronous operation.

## Cleanup and race handling

- Initial storage cleanup is scheduled five minutes after acceptance. This allows an already authorized bounded provider request/processing upload to settle after worker permissions are revoked. The video is hidden and links are unavailable during the delay.
- The real API starts a separate supervised IO cleanup loop at startup and polls every 30 seconds, up to five due videos per batch. Explicit mock storage does not run real cleanup. Keep the backend running; a laptop shutdown pauses cleanup, and the durable queue resumes at the next startup. It is not a promise that a large video finishes physical removal exactly at five minutes.
- PostgreSQL claims use FOR UPDATE SKIP LOCKED, a random cleanup token and a five-minute lease, renewed every 20 seconds while storage work runs. Complete/fail updates are token fenced; completion also requires a current unexpired lease. Storage operations never hold the database claim transaction open.
- Delete only the exact bound raw/<video UUID>.(mp4|mov|mkv|webm) object and the exact processed/<video UUID>/ prefix. Processed buckets come from configured storage plus the video's current/recorded processing attempts and must match configured buckets before any delete. No uploader/bucket-wide prefix, arbitrary caller key, original of another UUID or unconfigured bucket is accepted.
- Remove all original and processed-attempt assets, including HLS segments/playlists, thumbnail and abandoned attempt files. Missing objects are idempotent. Verify each processed prefix is empty and the raw object returns 404 before marking storage_deleted_at. Only then clear the stored media references/duration; never report storage complete after an exception.
- Failure leaves cleanup pending with exponential retry delay capped at one hour, without silently abandoning the job. Logs contain generic cleanup status, not provider messages, keys or private metadata. Misbound/corrupt legacy keys or inaccessible historical buckets require operator investigation and remain pending rather than widening deletion scope.
- Processing and thumbnail workers cannot claim deleted rows. Existing job ownership is cancelled, and old heartbeats/completions cannot restore the video. Normal processing/thumbnail cleanup may idempotently encounter an already removed attempt.

## Schema and changed files

V8 adds deleted_at, storage_deleted_at, cleanup token/lease/next-attempt/attempt count and a partial pending index. It does not mark any existing video for deletion or delete/reset any record. V1–V7 remain unchanged. No normal public-schema migration or user deletion is run by the agent for this increment; the updated API applies V8 at restart.

Backend:
- server/src/main/resources/db/migration/V8__video_deletion.sql
- server/src/main/kotlin/com/vidoora/backend/modules/video/VideoTables.kt (tombstone/cleanup fields and mapping of existing job lease column)
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt and PlaybackRoutes.kt (owner deletion, idempotency, library/access exclusion, 409 handling)
- server/src/main/kotlin/com/vidoora/backend/modules/upload/UploadService.kt (reject deleted upload-session mutations/replay)
- server/src/main/kotlin/com/vidoora/backend/modules/processing/PostgresProcessingRepository.kt and ThumbnailWorker.kt (deleted-row exclusions)
- server/src/main/kotlin/com/vidoora/backend/modules/deletion/VideoDeletionWorker.kt (validated key binding, durable PostgreSQL cleanup repository and worker)
- server/src/main/kotlin/com/vidoora/backend/infrastructure/S3StorageService.kt (scoped delete and absence verification)
- server/src/main/kotlin/com/vidoora/backend/Application.kt (supervised cleanup lifecycle)
- server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt, VideoDeletionWorkerTest.kt and LiveVideoDeletionIntegrationTest.kt (ownership/revocation/active upload/replay, idempotency/retry/path safety, real PostgreSQL/R2 isolation/fencing/removal)

Android:
- app/src/main/java/com/example/vidoora/feature/playback/PublishingScreens.kt (owner-only confirmation/action)
- app/src/main/java/com/example/vidoora/feature/playback/PublishingViewModels.kt (DELETE, acceptance/uncertainty/retry state)
- app/src/main/java/com/example/vidoora/core/network/VideoShareAccess.kt (forget only the deleted video's capability)
- app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt (callback)
- app/src/test/java/com/example/vidoora/VideoDeletionUiTest.kt (no request until action, successful acknowledgement, local access clearing, retryable failure and viewer restriction)

AGENTS.md, README and master plan record this approved scope and evidence. Backup: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\deletion-before. No replacement app, report UI, public access, ads, payouts or cloud deployment is added.

## Verification

Executed 26 October 2026: **Android 57 passed, zero skipped/failures/errors; backend 61 passed, one skipped, zero failures/errors; debug APK built.** Both final Gradle commands completed successfully and JUnit XML totals were inspected. The skipped suite is the older LiveR2IntegrationTest without its separate opt-in. The new live deletion fixture and existing real PostgreSQL/R2/FFmpeg suites executed; V8 was applied only in generated isolated schemas. The new completed-upload replay regression passed and created no replacement processing job.

App/server source hashes match verification copies; V1–V7 are unchanged. No existing user video was deleted, no normal API process was restarted and the normal public schema was not migrated by the agent during this increment. Phone confirmation/cancellation, actual five-minute observation and normal deployment remain pending.

Built APK: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-video-deletion-debug.apk. A report copy is in that outputs directory. Existing uploads stay untouched until the owner explicitly confirms Delete in the updated app.

Normal project commands:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
# With private PostgreSQL/R2 test configuration already loaded in this terminal:
$env:VIDOORA_LIVE_PROCESSING_TEST='true'
# R2_TEST_BUCKET must be a dedicated vidoora-test-* bucket.
.\gradlew.bat :server:test --tests com.vidoora.backend.LiveVideoDeletionIntegrationTest --rerun-tasks
```

Actual verification uses the established Gradle 9.6/Java 17 sandbox copies, same sources/dependencies, Android copied SDK and offline Robolectric init script. Original project Java 25 daemon configuration is unchanged. The helper privately loads the saved Windows-encrypted credentials and restores its process environment afterward:

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-Phase4Verification.ps1' -Tests
# outputs\phase5-verification; existing Java 17 JAVA_HOME:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

The live test creates a validated random PostgreSQL schema and two synthetic UUID object namespaces in the dedicated bucket. It verifies viewer denial, immediate shared-link revocation, library exclusion, processing/thumbnail cancellation, the safety delay, lease replacement/fencing, simulated provider failure and persistent retry, actual target raw/HLS/preview/abandoned-prefix removal, and survival of the neighboring synthetic raw/processed objects. The test accelerates due times only inside its own schema; production safety timing is not bypassed. Finally it removes only its explicit generated fixture keys/schema. No user upload, bucket reset or broad production cleanup is executed.

ViewModel tests use Robolectric/mock HTTP, not Compose dialog instrumentation or phone tests. The initial backend compile failed on a transient cached compiler annotations class-loading error; rerunning with filesystem watching disabled/two workers compiled successfully. The first Android KSP run also encountered cached-file read failures. Final verification commands were approved to run outside the Windows sandbox to avoid those cache-read errors; project settings and test expectations were not weakened. Failed attempts are not counted as passing.

## Device acceptance

Restart Start-Vidoora.cmd to apply V8/load the new routes and cleanup loop; then Sync and Run the existing Android Studio project. Preserve app data. Use a disposable test upload you own:

1. Open details → Delete video → Cancel: the video/link must remain usable.
2. Delete video → Delete permanently: see confirmation that access stopped and storage cleanup is queued, then Back. The video must disappear from My Videos, including search/older pages after refresh.
3. Reopen the prior complete sharing link in the second account: Video unavailable. A viewer must never see a Delete button.
4. Confirm a different upload remains playable and visible. Active uploads must require queue cancellation/finish before deletion.
5. Keep the backend running past the five-minute safety delay. Physical cleanup retries if storage is temporarily unavailable. Read-only status checks for your disposable UUID can inspect deleted_at, storage_deleted_at and deletion_cleanup_attempts; do not mistake the immediate UI acknowledgement for already-finished storage cleanup.
6. Offline deletion must not falsely show success. If a response was lost, refresh/retry safely; acceptance is idempotent.

Optional read-only operator query (replace only with the disposable video's UUID):

```sql
SELECT publication_state, deleted_at, storage_deleted_at, deletion_cleanup_attempts
FROM videos WHERE id = 'DISPOSABLE_VIDEO_UUID';
```

Phone confirmation, real-time five-minute observation, destructive Compose dialog instrumentation, large-library deletion throughput and operator monitoring remain pending. Do not claim production readiness from the synthetic fixture.


