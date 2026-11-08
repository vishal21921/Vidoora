# Phase 13 — Private folders

Approved 26 October 2026 after the user reported all Phase 12 sorting/filtering options work. This is user-reported phone evidence, not instrumentation. Scope: private single-level folders for My Videos, owner-managed create/rename/remove, moving a video into one folder or Unfiled, and folder-aware search/pagination. No folder sharing or media deletion is added.

## User flow and limits

My Videos adds a Folder selector: All videos (default), Unfiled and the signed-in owner's folders. Folder selection composes with search, Newest/Oldest, status and sharing. Changing folder resets pagination and cancels stale requests; Load more/Refresh preserve the selected folder. Reset view returns to All videos. Cards show their folder name when available. Returning from folder management refreshes the folder list; a removed selected folder falls back to All videos rather than keeping an invalid scope.

Manage folders provides New folder, Rename and Remove. Names are trimmed, 1–64 characters, with case-insensitive uniqueness within an account. Different accounts may use the same names. The initial bound is 100 folders per account, allowing a complete bounded picker rather than an unbounded metadata response. Names/keys are validated server-side. A failure leaves typed input/list entries available for refresh/retry; folder controls do not claim success without acknowledgement.

Owner video details add Move to folder. The picker loads only the owner's folder list, offers Unfiled, and saves only the selected folder ID. Failures retain the selection; Cancel changes nothing. One video belongs to at most one folder. All videos always includes both filed and unfiled uploads. New/existing uploads are Unfiled by default; nested folders, bulk moves and selecting a folder at upload time are not part of this increment.

Remove folder requires a confirmation explicitly saying its videos move to Unfiled and no videos are deleted. The transaction clears only membership and removes the folder record, including references on retained tombstones. Media, playback, share generations/links, title/description, timestamps, processing state, thumbnails and video deletion/cleanup state remain unchanged. Folder removal is not Delete video, and never calls R2. Renaming keeps the same folder ID.

Only actual owners can list/manage a folder or move their videos. A share-link viewer cannot move another person's upload or see that uploader's folder name/ID. Library folder IDs are owner-only metadata; details return a folder ID only when isOwner=true. A deleted video cannot be moved or reappear through a folder filter. Account sign-out/navigation isolation remains unchanged.

## API, schema and concurrency

All routes require normal access JWTs, active accounts and no-store:

- GET /api/v1/folders → owner-only list of {id,name}, at most 100.
- POST /api/v1/folders with {name} → created folder.
- PATCH /api/v1/folders/{id} with {name} → renamed owned folder.
- DELETE /api/v1/folders/{id} → accepted after atomically unfiling its videos; foreign/missing IDs return 404. A repeated removal of a now-missing folder returns 404; refresh reconciles a lost response.
- PUT /api/v1/videos/{id}/folder with {folderId: UUID or null} → updated owner details; null means Unfiled. No client-supplied uploader or storage key is accepted.
- Existing GET /api/v1/videos/mine/page adds folder=ALL|UNFILED|owned-folder-UUID. Unknown/foreign folder IDs are rejected, not interpreted as All videos.

Mutations serialize on the active owner's user row, protecting name uniqueness, count bounds and move/remove races. A per-owner normalized-name unique constraint adds database protection. V9 creates video_folders, adds nullable videos.folder_id and a composite FK (uploader_id,folder_id) → (owner_id,id). PostgreSQL therefore rejects a direct cross-owner assignment even if service code were bypassed. Folder deletion explicitly unfiles rows before removing the referenced folder; no cascade deletes video records. A matching owner/folder/date/UUID index supports paging.

The page cursor includes the folder view in its fingerprint. ALL retains the existing fingerprint format for Phase 12 compatibility; legacy two-field cursors remain accepted only for the default All/Newest/no-status-or-sharing-filter view. Owner predicates remain the actual authorization, independent of cursor consistency checks. Folder/search/other filters are enforced together and deleted rows remain excluded. Membership changes require Refresh for a new dataset; no snapshot guarantee is made across requests.

V9 is additive: existing videos stay Unfiled and no existing names, sharing settings, original/HLS/thumbnail objects or tombstones are removed. V1–V8 are unchanged. No new dependency or credential is required; no new S3 operations are introduced by this feature. Folder metadata adds small database/query overhead, not a scalability guarantee.

## Changed files

Backend:
- server/src/main/resources/db/migration/V9__private_folders.sql — table, constraints, membership column/index.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/VideoFolders.kt — mappings/DTOs.
- server/src/main/kotlin/com/vidoora/backend/modules/video/VideoTables.kt — nullable folder reference.
- server/src/main/kotlin/com/vidoora/backend/infrastructure/DatabaseFactory.kt — explicit H2 fixture creation order.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt — owner folder CRUD/membership, folder-aware library and owner-only details metadata.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackRoutes.kt — authenticated endpoints and folder query parameter.
- server/src/main/kotlin/com/vidoora/backend/modules/playback/LibraryManagement.kt — folder-aware cursor scope.
- server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt and LibraryManagementIntegrationTest.kt — ownership/names, moving, safe removal, link preservation, cursor separation, API auth and real PostgreSQL composite FK.

Android:
- app/src/main/java/com/example/vidoora/feature/playback/FoldersViewModel.kt — folder DTO/list/manage state and authenticated requests.
- app/src/main/java/com/example/vidoora/feature/playback/FolderScreens.kt — management/name/remove confirmations and move dialog.
- app/src/main/java/com/example/vidoora/feature/playback/VideoLibraryViewModel.kt and VideoLibraryScreen.kt — selection, filtering/reset, names and management entry.
- app/src/main/java/com/example/vidoora/feature/playback/PublishingViewModels.kt and PublishingScreens.kt — owner move state/action/dialog, acknowledged details update.
- app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt — management route, callbacks and folder refresh/fallback.
- app/src/test/java/com/example/vidoora/PrivateFoldersTest.kt — failed/acknowledged mutations, selected move retry, unchanged sharing flag and folder parameter/reset behavior.

AGENTS.md, README and master plan record scope/evidence. Backup: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\folders-before. Work remains in the existing F:\app\app\Vidoora project.

## Verification

Executed 26 October 2026: **Android 62 passed, zero skipped/failures/errors; backend 63 passed, four skipped, zero failures/errors; debug APK built.** Both final Gradle commands completed successfully, and JUnit XML totals were inspected. The real PostgreSQL library/folder fixture executed V9, ownership constraints, move/rename/remove with video preservation and continued validity of the existing share capability. The four skipped suites are local FFmpeg, live R2 upload, live R2 processing and live R2 deletion; they were not opted in for this metadata-only feature.

App/server source hashes match the verification copies. V1–V8 hashes are unchanged. V9 was applied only to generated isolated schemas; no normal database migration, folder/video move/removal, API restart or app installation was performed by the agent. No existing user media or R2 object was changed. Phone acceptance remains pending.

Built APK: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-private-folders-debug.apk. A report copy is in that outputs directory. Restart/update and the steps below remain required for the real app.

Standard project commands:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process'
# With database credentials privately loaded in the same terminal:
$env:VIDOORA_POSTGRES_TEST='true'
.\gradlew.bat :server:test --tests com.vidoora.backend.LibraryManagementIntegrationTest --tests com.vidoora.backend.PlaybackSecurityTest --rerun-tasks
```

Actual verification uses the established source-matching Java 17/Gradle 9.6 copies and Android copied SDK/offline Robolectric init script, approved outside the Windows sandbox to avoid prior cache-read failures. Original project Java 25 daemon/dependency settings remain unchanged. The PostgreSQL helper loads the saved encrypted database password privately and restores process environment variables:

```powershell
& 'C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Run-LibraryVerification.ps1'
# outputs\phase5-verification, existing Java 17 JAVA_HOME:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Real PostgreSQL fixtures use generated validated temporary schemas and remove only their own data/schema. The folder fixture applies V9, moves synthetic rows, rejects a direct foreign-owner FK assignment, removes a synthetic folder while retaining videos and validates the prior capability still works. No normal database/R2 content is changed; R2/FFmpeg/destructive live suites are not opted in here. Android checks are Robolectric/ViewModel/mock HTTP, not phone/Compose dialog instrumentation. No API restart/app install is performed by the agent.

## Phone acceptance

1. Restart Start-Vidoora.cmd to apply V9 and load new folder APIs, then Sync and Run the existing Android Studio project. Keep app data.
2. My Videos → Manage folders → New folder, for example Family. Create another, rename one, and check a duplicate name is rejected without losing existing folders.
3. Open an owned video → Move to folder → Folder: Family → Move. Return to My Videos and select Family. Combine search/status/sharing and check Refresh/Load more as applicable.
4. Switch to All videos or Unfiled; moving a video does not remove it from All videos. Reopen its old enabled sharing link in a second account: playback should still work, and no folder metadata/move control belonging to the uploader should appear.
5. Remove Family after reading the confirmation. Its videos must remain playable in All videos/Unfiled, and sharing must remain as before. Removing a currently selected folder should recover to All videos after returning/refreshing.
6. Switch accounts in both directions. Folder lists/membership must stay private. Offline errors must retain list/draft/selection for retry, with no false success.

Device acceptance, wider concurrency/load testing and earlier physical deletion-cleanup observation remain separate pending checks. No folder sharing, public listing, Report option, bulk video deletion, monetization or hosting is added.

