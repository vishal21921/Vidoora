> Historical implementation report. The user subsequently removed reporting. Current scope and verification: [Blocking only](phase-7-blocking-only.md). No Report option or submission API remains.

# Phase 7 — Shared-video reporting and uploader blocking

Approved 26 October 2026, following the user's manual confirmation of link-only playback and Stop sharing revocation in two accounts. This adds safety controls to personal link sharing, not public discovery or monetization.

## Behavior and API

- Shared-video details offer Report video and Block uploader only to a non-owner who currently has access.
- `POST /api/v1/videos/{id}/report`: normal access JWT and `X-Vidoora-Share` capability required. Body: reason plus optional details (at most 1000 characters). Reasons: SPAM, HARASSMENT, VIOLENCE, SEXUAL_CONTENT, COPYRIGHT, OTHER. Saves PENDING with authenticated reporter, video and actual uploader IDs. The caller cannot choose another reporter/target or report their own upload. No report text, reporter identity or report listing is exposed to the uploader/viewers.
- Duplicate retries retain one pending report per reporter/video, preserving the original reason/details. Serialize mutations with a reporter user-row lock; a new report is limited to 20 per rolling 24 hours per account, returning 429 beyond that. A pending duplicate remains idempotent at the limit. Account-wide/request/IP anti-abuse limits remain future hardening.
- `POST /api/v1/videos/{id}/block-uploader`: authenticated viewer plus current link required for a new block. Resolve the uploader on the server; reject self-block. A repeated block is idempotent.
- A block is the viewer's one-way preference: all that uploader's videos become unavailable to that viewer, including details, master/media playlists and segments. It does not globally suspend/delete the uploader or prevent the uploader from watching their own files. Other viewers and the blocking viewer's own uploads remain unaffected.
- `GET /api/v1/users/me/blocked-uploaders`: authenticated account's own list only, 50 entries with UUID cursor pagination; display name and user ID only. No email or storage keys.
- `DELETE /api/v1/users/me/blocked-uploaders/{uploaderId}`: idempotently removes only the caller's block. Unblocking does not grant playback without a still-valid, enabled sharing link.
- Home → Blocked uploaders provides refresh, load more, confirmed Unblock. Shared details confirm blocking and show a result/error. Successful blocking clears cached details/Watch permission. Failure retains the entry/state for retry; 401/403/404 clear stale details.
- Reports are stored for future operator review. There is no moderator dashboard, notification service, automated takedown, sanction workflow or review-time guarantee in this increment. Reporting alone does not disable sharing.
- Link-only authorization, sign-in, suspension checks, private R2 and the upload limit remain enforced. Capabilities stay in the redacted header, not URL queries. Report details are not logged by the INFO HTTP logger. Do not enter credentials or complete private links in report text.
- Blocking/revocation affects subsequent requests; already buffered/in-flight media cannot be remotely erased.

## Schema and changed files

Existing V1 `reports` and `user_blocks` tables are mapped rather than replaced. V5 adds indexes on (reporter_id, created_at) and (reporter_id, video_id, status). There is no data deletion/reset; V1–V4 are unchanged. H2 explicit-test setup includes the two mapped tables. PostgreSQL uses Flyway normally on next backend start.

Backend:
- `server/src/main/kotlin/com/vidoora/backend/modules/playback/SafetyTables.kt` — table mappings.
- `server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt` — report, block/list/unblock and per-request block enforcement.
- `server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackRoutes.kt` — authenticated routes, no-store and report quota status.
- `server/src/main/kotlin/com/vidoora/backend/infrastructure/DatabaseFactory.kt` — explicit H2 fixture table creation.
- `server/src/main/resources/db/migration/V5__reporting_and_blocks.sql` — additive indexes.
- `server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt` — report access/validation/quota/dedup, blocked assets/account isolation/unblock, cursor pagination and route auth.
- `server/src/test/kotlin/com/vidoora/backend/LiveProcessingIntegrationTest.kt` — real PostgreSQL report persistence/dedup, block/list/asset denial and unblock against private R2 HLS in the synthetic fixture.

Android:
- `app/src/main/java/com/example/vidoora/feature/playback/PublishingViewModels.kt` — safety submissions and result/error state.
- `app/src/main/java/com/example/vidoora/feature/playback/PublishingScreens.kt` — non-owner controls.
- `app/src/main/java/com/example/vidoora/feature/playback/SafetyViewModels.kt` — DTOs, blocked list/pagination/unblock.
- `app/src/main/java/com/example/vidoora/feature/playback/SafetyScreens.kt` — report reasons/details, confirmations and blocked list.
- `app/src/main/java/com/example/vidoora/feature/home/HomeScreen.kt` — Blocked uploaders entry; scrollable for smaller screens.
- `app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt` — navigation and callbacks.
- `app/src/test/java/com/example/vidoora/SafetyActionsTest.kt` — header-only capability, successful/failed block state, report submission and unblock failure/success.

## Verification

Executed 26 October 2026: **Android 41 passed, zero skipped/failures/errors; backend 48 passed, one skipped, zero failures/errors; debug APK built.** Inspected JUnit XML after both Gradle commands completed successfully. The skipped test is the earlier LiveR2IntegrationTest without its separate opt-in; the extended live PostgreSQL/R2/FFmpeg pipeline test did execute. Real PostgreSQL V5 index migration, report persistence/deduplication and block/unblock access checks passed in generated isolated schemas. App and server source hashes match their respective verification copies; V1–V4 hashes match the pre-change backup.

Initial runs caught a synthetic pagination username exceeding the existing 32-character limit and the MockEngine client's missing default JSON content type for report submission. The fixture username was shortened; report requests now explicitly set JSON content type. Both final suites were rerun successfully. No failing test was skipped or removed.

Backup: `C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\safety-before`. Built APK: `C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-safety-debug.apk`. No physical-device UI check, application uninstall/data clearing, live public-schema update or normal API restart was performed by the agent. The user must restart/update for the cutover below.

Normal project commands in PowerShell:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
```

Real tests require securely configured environment values; never paste credentials in chat or source. With existing DATABASE_URL/DB_USERNAME/DB_PASSWORD, private R2 test credentials/bucket and FFmpeg paths loaded in the same session:

```powershell
$env:VIDOORA_POSTGRES_TEST='true'
$env:VIDOORA_LIVE_PROCESSING_TEST='true'
$env:VIDOORA_FFMPEG_TEST='true'
.\gradlew.bat :server:test --tests com.vidoora.backend.PlaybackSecurityTest --tests com.vidoora.backend.LiveProcessingIntegrationTest --tests com.vidoora.backend.LinkOnlyMigrationIntegrationTest --rerun-tasks
```

Execution here uses the previously documented sandbox verification copies: `outputs/phase4-verification` for the server with Gradle 9.6/Java 17; `outputs/phase5-verification` for Android with the existing copied SDK and offline Robolectric init script. Original Java 25 daemon settings remain unchanged. These builds are local JVM/H2/Robolectric plus opt-in PostgreSQL/R2/FFmpeg tests, not physical-device instrumentation. Credential helper reads the existing Windows-encrypted file privately and restores process environment variables. Live tests use generated temporary schemas and synthetic object prefixes, cleaning only their own fixtures. They do not update the normal public schema or report real users.

Exact executed command entry points:

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-Phase4Verification.ps1' -Tests
# Working directory: outputs\phase5-verification, JAVA_HOME = existing Java 17 toolchain
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

## Phone acceptance / cutover

1. Stop the old API console with Ctrl+C, then run Start-Vidoora.cmd and wait for Responding at ...8080. This loads the new routes/checks and applies V5 indexes. Connect/authorize the USB phone before launch; preserve credentials and app data.
2. Sync and Run the existing F: project in Android Studio. Do not uninstall/clear data to update.
3. In the second account, paste an enabled complete sharing link. Details should offer Report video and Block uploader; the uploader's own details should not offer these controls.
4. Submit a synthetic Other report (no secrets) and see pending-review acknowledgement. This does not stop playback.
5. Confirm Block uploader. Reopen the same link and another enabled link from that uploader: Video unavailable. The owner's playback must still work.
6. Home → Blocked uploaders → Unblock, then reopen the valid link: playback works again. Stop sharing still makes it unavailable after unblocking.
7. Check an offline/failed submit/unblock does not show success, canceling a dialog makes no change, and logout/account switching isolates the blocked list.

Phone acceptance remains pending until the user reports these checks. No cloud hosting, clickable HTTPS links, ad counting, earnings or payouts were added.


