# Phase 12 — My Videos sorting and filters

Approved 26 October 2026 after the user reported Delete worked and its old shared link showed Video unavailable. Those are manual access-revocation results, not independent physical-cleanup confirmation. The prior five-minute cleanup observation remains pending separately.

## Current behavior

My Videos keeps its search field and adds three immediately applied dropdowns:

- Sort: Newest (default), Oldest — upload creation date plus UUID tie-breaker, in the same direction for both columns.
- Status: All statuses (default), Ready (PROCESSED), Waiting / processing (UPLOADED or PROCESSING), Failed (PROCESSING_FAILED).
- Sharing: All sharing (default), Private (link sharing not enabled), Shared by link (UNLISTED and is_published=true).

Filters combine with each other and the currently applied title/description search. Typing text alone does not change the applied search; Search still submits it explicitly. Changing a dropdown retains that applied search but resets pages/cursor and cancels the previous request. Load more, Refresh and returning from details keep the selected sort/filters. Superseded responses are fenced by the existing generation counter, preventing stale pages from mixing with the new view. Repeated selection of the current value makes no extra request. Reset view clears the search and restores Newest/All statuses/All sharing. Empty results explain that the current search/filters have no match.

Private includes an UNLISTED video whose sharing was stopped. Shared means its link-sharing switch is enabled, not that every previously issued capability is unexpired. Uploading/cancelled entries remain in All statuses but are not mislabeled as waiting/processing or failed; local upload-worker failures remain in Upload Queue, not the server processing-failure filter. No public visibility choice or discovery is added. Deleted rows are excluded for every combination and sort. All results remain actual-uploader-only.

Selections are screen/ViewModel state, retained through ordinary navigation/configuration recreation. They are not saved as a permanent cross-account/global preference. Ordinary account navigation clears the old library as before. Existing thumbnail access, edits, sharing, blocking, player quality/speed and deletion rules remain unchanged.

## API and cursor compatibility

`GET /api/v1/videos/mine/page` retains q/cursor/limit and adds strict optional enums:

- sort=NEWEST|OLDEST
- state=ALL|READY|PROCESSING|FAILED
- sharing=ALL|PRIVATE|SHARED

Invalid values return 400, without interpolating raw sort/filter input into SQL. Existing owner, active-account and deleted-row predicates apply independently of the cursor. Search remains a bounded literal case-insensitive substring, with wildcard characters escaped. NUL search input is rejected before reaching PostgreSQL.

New cursors include a SHA-256 view fingerprint over owner, normalized search, sort, status and sharing, beside the bounded timestamp/UUID encoding. A new cursor reused for another view/account is rejected with 400. The fingerprint is a consistency check, not a secret capability or authorization mechanism; the uploader predicate is the access control. Legacy two-field cursors are still accepted only with default Newest/All/All, preserving older clients' search pagination. No client can gain another uploader's rows by supplying a cursor. Cursor size/date/UUID validation remains bounded.

The existing owner/date/UUID index supports reverse scanning for Oldest. No new migration, dependency, credential, storage write or deletion is introduced. The old mine-array endpoint remains unchanged. Refresh is still needed for a changed dataset: pagination is not a snapshot across mutations.

## Changed files

Backend:
- server/src/main/kotlin/com/vidoora/backend/modules/playback/LibraryManagement.kt: contextual cursor encoding/validation and view fingerprint.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt: strict enum validation, combined predicates and ascending/descending keyset boundaries.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackRoutes.kt: forward the new library parameters; retired discovery remains empty and unchanged.
- server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt: both directions with ties, context mismatch/invalid-enum rejection, status/sharing combinations, stopped-share/private behavior, deleted/foreign exclusion.
- server/src/test/kotlin/com/vidoora/backend/LibraryManagementIntegrationTest.kt: real PostgreSQL reverse pagination and combined search/Ready/Private filtering across pages, including microsecond timestamp ties and excluded deleted rows.

Android:
- app/src/main/java/com/example/vidoora/feature/playback/VideoLibraryViewModel.kt: selected criteria, reset and request snapshot parameters.
- app/src/main/java/com/example/vidoora/feature/playback/VideoLibraryScreen.kt: three dropdowns, Reset view, combined-empty-results text.
- app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt: callbacks.
- app/src/test/java/com/example/vidoora/LibraryFiltersTest.kt: query/context preservation across paging/refresh/reset and cancellation of an old page after filter change.

AGENTS.md, README and master plan record approval/evidence. Backup: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\filters-before. The existing repository remains F:\app\app\Vidoora; no replacement project was created.

## Verification

Executed 26 October 2026: **Android 59 passed, zero skipped/failures/errors; backend 60 passed, four skipped, zero failures/errors; debug APK built.** Both final Gradle commands completed successfully; JUnit XML totals were inspected. The expanded real PostgreSQL library fixture executed both sort directions and combined filters across pages with timestamp ties. The four skipped suites are local FFmpeg, live R2 upload, live R2 processing, and live R2 deletion; none was opted in for this read-only library-query increment. Existing H2 authorization/deletion exclusion tests ran in the backend suite.

App/server source hashes match the verification copies. All V1–V8 migration files are unchanged, and no migration is added. No normal database/video/R2 content was altered, no API restart/app installation was performed by the agent and no physical-device behavior is claimed. The earlier deletion storage-cleanup observation remains separate and unverified.

Built APK: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-library-filters-debug.apk. A report copy is in that outputs directory. Restart/update and the phone checks below remain pending.

Normal project commands:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
# With the database credentials privately loaded in this same session:
$env:VIDOORA_POSTGRES_TEST='true'
.\gradlew.bat :server:test --tests com.vidoora.backend.LibraryManagementIntegrationTest --tests com.vidoora.backend.PlaybackSecurityTest --rerun-tasks
```

Actual verification uses the established source-matching Gradle 9.6/Java 17 copies, Android copied SDK and offline Robolectric init script; original project Java 25 daemon settings are unchanged. Final verification runs were approved outside the Windows sandbox to avoid the cached-file read failures documented in prior phases. Tests/expectations were not weakened.

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-LibraryVerification.ps1'
# outputs\phase5-verification, existing Java 17 JAVA_HOME:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

The backend helper privately imports only the saved encrypted database password and restores its environment. Real PostgreSQL tests create validated random schemas and remove only their fixtures; no normal database records, migrations or R2 assets are changed. Existing R2/FFmpeg/destructive integration tests are not opted in for this filtering increment. Android tests use Robolectric/mock HTTP, not device/Compose gesture instrumentation.

## Phone check

Restart Start-Vidoora.cmd to load the API's new filter/sort parameters, then Sync and Run the Android project. No database migration/new credential is required. In My Videos:

1. Toggle Newest/Oldest and check the upload-date order. Same-date ordering uses UUID, not title.
2. Try Ready, Waiting / processing and Failed with whatever uploads actually exist. An empty result is valid when no video has that state.
3. Try Private and Shared by link; a stopped share belongs to Private. Combine with status and search.
4. Load more under a selected view if there are enough matches; change a filter and confirm old results do not remain mixed in.
5. Use Reset view, open details and Back, and switch accounts. No foreign or deleted upload should appear. Thumbnails and normal actions should remain usable.

Phone behavior remains pending until reported. Earlier deletion-link denial does not prove physical cleanup completion; keep that verification separate. No new reporting, public feed, hosting, monetization or deletion action is authorized by this feature.

