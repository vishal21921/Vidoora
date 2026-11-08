# Phase 8 — My Videos search, editing and pagination

Approved 26 October 2026 after the user confirmed the report-removal update worked. Scope: search the signed-in uploader's videos, edit their title/description, and load older uploads beyond the previous latest-50 limit. Private/link-only sharing, Block/Unblock and the no-report policy remain in effect.

## Behavior

My Videos now has a search field and explicit Search button. Search is a trimmed, case-insensitive literal substring of title or description, up to 100 characters. Empty search shows all the owner's uploads. `%`, `_` and `!` are escaped as literal characters; no regex/wildcard or global discovery is enabled. Only the authenticated uploader's rows are queried, regardless of their sharing state.

The first page contains up to 25 uploads. Load more follows a server cursor; maximum API page size is 50. Ordering is creation date descending, then UUID descending, so same-date uploads are not lost between pages. Cursors use a bounded, validated timestamp/UUID encoding, not an offset. The uploader filter is independently enforced on every page, including supplied cursors. There is no promise of a database snapshot across requests: new uploads or edits changing search membership may require Refresh.

Android cancels a superseded request and fences stale responses by generation. New searches reset pages/cursors, while Load more continues the applied search even if the user has typed a new unsubmitted query. Failed page loads keep earlier pages and the cursor for retry. Responses are deduplicated by ID. Authentication/access failures clear stale rows. Returning from details refreshes the current applied search from its first page; Refresh does the same. Clearing the field and pressing Search removes the filter.

Owner details, at any upload/processing state, expose Edit details. The title is trimmed and must contain 1–128 characters. Description is optional, at most 5000 characters, trimmed on save; empty means null. Older descriptions are displayed in full, not silently truncated; an over-limit value must be shortened explicitly before saving. Cancel sends no update. Failed saves retain the draft for retry. Successful saves show the server's details and close the dialog. A viewer with a valid share capability still cannot edit. Owner/suspension checks and row locking run on the backend.

Editing changes only title, description and updated_at. It does not alter the uploader, creation date, processing state, private R2 object references, sharing state/generation/expiry or Block/Unblock. Existing complete links remain valid under the same access rules. Concurrent owner saves use last-write-wins; no edit-version merge UI is included. Upload queue titles are historical local task metadata; My Videos and video details are the authoritative current titles.

Search requests are excluded from Android INFO HTTP logging and backend CallLogging so query text/cursors are not logged. Existing share-header redaction remains. Metadata request bodies are not logged by the INFO HTTP logger. Private R2, the 1 GiB limit and the removed Report endpoint remain unchanged. No delete-video, public feed, ads, payouts, hosting or new credentials are added.

## API and schema

- `GET /api/v1/videos/mine/page?q=...&cursor=...&limit=25`: normal access JWT, active account, no-store. Returns `data.items` (existing library fields only) and nullable `data.nextCursor`. Invalid search length, limit or cursor returns 400. Cursor alone never grants access.
- Existing `GET /api/v1/videos/mine` keeps its latest-50 array contract for older clients/tests; the updated app uses the page endpoint.
- `PATCH /api/v1/videos/{id}/details`: normal JWT and actual owner required, no-store. Body contains title and optional description; sharing capability is not editing permission. Returns updated details, with 404 for a non-owner/missing video and 400 for invalid metadata.
- V6 adds `idx_videos_owner_created_id` on (uploader_id, created_at DESC, id DESC). No records/media are deleted and no earlier migration is edited. This supports owner pagination; substring search may still scan an owner's rows. Search scale/load and trigram indexing are deferred until measured.

## Changed files

Backend under server/src:
- main/kotlin/com/vidoora/backend/modules/playback/LibraryManagement.kt: page/edit DTOs and validated cursor.
- main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt: owner-scoped search/keyset query and locked metadata update.
- main/kotlin/com/vidoora/backend/modules/playback/PlaybackRoutes.kt: new page and PATCH routes; existing library route retained.
- main/kotlin/com/vidoora/backend/plugins/Routing.kt: omit library search requests from CallLogging; allow PATCH in the existing CORS method list. Production CORS restrictions remain an earlier outstanding task.
- main/resources/db/migration/V6__owner_library_pagination.sql: additive pagination index.
- test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt: same-date pagination, literal search, account isolation, validation, metadata/sharing/storage invariants and route auth.
- test/kotlin/com/vidoora/backend/LibraryManagementIntegrationTest.kt: opt-in real PostgreSQL temporary schema, V6 migration/index, 61 owner uploads plus five other-account uploads, microsecond timestamps and UUID tie handling, search and owner-only edits preserving a valid capability.

Android under app/src:
- main/java/com/example/vidoora/feature/playback/VideoLibraryViewModel.kt: paged DTO/state, search/load-more, cancellation/generation fence, refresh.
- main/java/com/example/vidoora/feature/playback/VideoLibraryScreen.kt: search input/button, results/empty/error UI, Load more.
- main/java/com/example/vidoora/feature/playback/PublishingViewModels.kt: edit state, validation and PATCH save/error handling.
- main/java/com/example/vidoora/feature/playback/PublishingScreens.kt: owner Edit details entry/dialog callbacks.
- main/java/com/example/vidoora/feature/playback/EditVideoDialog.kt: scrollable form, limits, cancel/save/progress.
- main/java/com/example/vidoora/ui/navigation/NavGraph.kt: callbacks and library refresh on resume.
- main/java/com/example/vidoora/core/network/KtorClientFactory.kt: omit search requests from HTTP logging.
- test/java/com/example/vidoora/LibraryManagementTest.kt: page retry/query consistency/dedup, stale-search cancellation/reset, edit failure/success, validation/cancel and viewer/denied-save restrictions.

Project AGENTS.md, README and master plan record this approved scope and evidence. Repository remains F:\app\app\Vidoora; no replacement app was created.

## Verification

Final executed results on 26 October 2026: **Android 47 passed, no skips/failures/errors; backend 49 passed, 3 skipped, no failures/errors; debug APK built.** Both final Gradle commands completed successfully; JUnit XML counts inspected. Real PostgreSQL library search/pagination/editing plus V6 index migration executed in the new synthetic integration test, alongside the opt-in existing PostgreSQL processing/privacy migration checks. The three skipped suites are FFmpeg, live R2 upload and live PostgreSQL/R2/FFmpeg processing; they were not opted in for this increment. No new real-R2 compatibility claim is made.

App and server source hashes match their verification copies; V1–V5 migration hashes match the pre-change backup. No normal public schema migration or phone verification was performed. Source backup: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\library-before. Built artifact: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-library-management-debug.apk. Phone restart/update and acceptance below remain pending.

Normal project commands:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
# With database credentials privately loaded in this same session:
$env:VIDOORA_POSTGRES_TEST='true'
.\gradlew.bat :server:test --tests com.vidoora.backend.LibraryManagementIntegrationTest --rerun-tasks
```

Actual local verification uses the established Java 17/Gradle 9.6 copies because the sandbox's Java 25 filesystem checks reject writable project paths. Original project Java/daemon/dependency configuration is retained. Server commands run from outputs/phase4-verification; Android runs from outputs/phase5-verification with the offline Robolectric init script and copied SDK:

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-LibraryVerification.ps1'
# Android verification directory, JAVA_HOME = existing Java 17 toolchain:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

The helper imports only the saved Windows-encrypted database password privately, sets the PostgreSQL opt-in and restores environment variables afterward. It never prints credentials. PostgreSQL tests create validated random schemas and remove only their own fixtures. New library integration uses mocked storage and does not read/write R2. No normal public-schema migration, API restart, phone install, app data clearing or device UI verification is performed by the agent. The earlier user-confirmed Block/Unblock/report-removal checks remain manual historical evidence.

The first compile found that the installed Exposed version requires a LikePattern value for explicit escape characters; this was corrected using the installed dependency API. One build also hit a Gradle cache AccessDeniedException while another build was active; verification was retried sequentially. These attempts are not passing test claims.

## Phone acceptance

1. Stop the old backend (Ctrl+C), run Start-Vidoora.cmd, and wait for Responding at ...8080. This loads the new routes and applies V6 normally.
2. Sync and Run the existing Android Studio project. Keep app data and the current USB reverse setup.
3. Home → My Videos: search an existing title, then description text; a different account's uploads must not appear. Clear the field and Search to return to all uploads.
4. Open one of your videos → Edit details → change title/description → Save. Return to My Videos and confirm the updated title/current search result. Canceling edits must leave stored details unchanged.
5. Reopen its existing complete sharing link in the second account: the updated details should be visible and playback should work. The viewer has no Edit details button. Stop sharing and Block/Unblock must retain their previous behavior.
6. If the account has more than 25 matching uploads, Load more should reach older ones. Automated tests cover more than 50 records; no phone-scale result is claimed without the actual records/check.
7. Try a save or page load offline: errors must not claim success or discard the editable draft/earlier pages.

Wait for user device confirmation before proposing the next scope. No monetization, hosting or destructive content-management action is authorized by this feature.

