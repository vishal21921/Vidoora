# Phase 7 correction — Blocking only; reporting removed

The user confirmed on 26 October 2026 that Block uploader hides the video and Unblock restores it, then explicitly withdrew the Report option because they will not manage reports. This supersedes the initial reporting/blocking scope. Those are user-reported device checks, not instrumentation results.

## Current behavior

- Shared-video details offer Block uploader with confirmation. Home → Blocked uploaders supports confirmed Unblock and pagination.
- No Report button, reason dialog, report DTO or Android submission method remains.
- The backend report route and report-writing service method are removed. `POST /api/v1/videos/{id}/report` returns 404, including for an authenticated older client with a valid sharing capability. It writes no report data.
- Blocking authorization, per-viewer account isolation and future details/playlist/segment denial are unchanged. Unblocking still requires a valid enabled complete link. Owner preview and other viewers are unaffected.
- Existing reports, table mappings and V1–V5 migration files remain untouched. There is no deletion, schema reset or historical Flyway checksum change. The retained mapping only supports legacy data/schema compatibility and the no-new-write regression test; no active API writes reports.
- No moderation/report-management interface is planned in the current authorized scope. Private libraries, link-only sharing, the private bucket and the 1 GiB upload limit remain unchanged.

## Changed files

- Android `feature/playback/PublishingScreens.kt`, `SafetyScreens.kt`: removed report controls/dialog/callback.
- Android `feature/playback/PublishingViewModels.kt`, `SafetyViewModels.kt`: removed report DTO/submission/error messages; retained block state handling.
- Android `ui/navigation/NavGraph.kt`: removed report callback.
- Android `SafetyActionsTest.kt`: block-only success/failure/header regression, unblock behavior, explicit publish/unpublish routing regression.
- Backend `modules/playback/PlaybackRoutes.kt`, `PlaybackService.kt`: removed report endpoint/request/quota/writer; block/list/unblock methods remain.
- Backend `PlaybackSecurityTest.kt`: removed tests for withdrawn report-writing behavior; retained block security/pagination tests and verifies retired report endpoint returns 404 and stores no report.
- Backend `LiveProcessingIntegrationTest.kt`: retains real PostgreSQL/R2 block checks when explicitly opted in; no report creation and expects no reports in the synthetic schema.
- `AGENTS.md`, README and master plan updated to the approved block-only scope. Initial phase-7-reporting-blocking.md is historical.

Paths above are under the existing F:\app\app\Vidoora modules (server/src/... and app/src/...); no replacement project was created.

## Verification

Final executed results on 26 October 2026: Android 42 passed, zero skipped/failures/errors; backend 42 passed, 5 opt-in integration tests skipped, zero failures/errors. Both Gradle builds completed successfully and the debug APK was built. Inspected JUnit XML. The five skipped tests are FFmpeg, PostgreSQL processing, PostgreSQL privacy migration, live R2 upload, and live PostgreSQL/R2 processing; they were not opted in or rerun for this report-removal correction. Earlier successful live checks remain historical evidence, not a new live-test claim. Source hashes match the Android/server verification copies. V1–V5 migration hashes are unchanged.

Executed in the established sandbox verification copies with Gradle 9.6 and Java 17, matching the same source/dependencies. Original project daemon/SDK settings are unchanged. Android uses the existing offline Robolectric init script and copied SDK. Commands:

```powershell
# outputs\phase4-verification (server-only verification build)
.\gradlew.bat test '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
# outputs\phase5-verification
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

Equivalent standard project tasks:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
```

This correction does not require new cloud configuration or any database migration. No device test or normal API restart was performed by the agent. Initial Gradle startup failed to connect to its local daemon under the restricted network permission; the permission was granted and the build was retried, not counted as a pass.

## Update on phone

Restart Start-Vidoora.cmd to load the removed endpoint and rebuild/run the existing project in Android Studio. Do not uninstall or clear app data. In a second account, open an active complete sharing link: Block uploader remains; Report video is absent. Confirm blocking, reopen the link to see unavailable, then Home → Blocked uploaders → Unblock and reopen the still-valid link to watch again.

