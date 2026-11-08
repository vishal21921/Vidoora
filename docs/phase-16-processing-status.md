# Phase 16 — automatic processing-status updates

Approved 27 October 2026 after the user confirmed phone login, guest playback after logout and the Upload login gate. Those are device reports; server refresh rotation/revocation evidence remains the Phase 15 automated tests.

## Behavior

Upload Queue distinguishes file transfer completion from playback readiness. Completed cards say UPLOAD COMPLETE, explain that the stored file still needs processing and offer Processing & sharing to open the existing owner details screen. The local upload queue remains transfer history; it does not pretend that 100% uploaded means playable.

Video details automatically checks an owned pending video's server state every five seconds while resumed: Uploading, Waiting for processing, Processing video, Ready to share, or Processing failed. Ready exposes the existing Watch and Enable link sharing controls. Processing does not enable sharing or issue a link automatically. Guests/completed/failed details stop making processing-status requests. Failure guidance points to the processor log and a valid replacement upload when appropriate; it is not a new retry-job action.

My Videos checks only pending cards currently in the visible viewport, with at most 50 IDs per batch. Results update rows in place without clearing loaded pages, changing their order, resetting cursor/search/filter/folder state or showing a full-page loading flash. The applied search is used, not an unsubmitted text-field draft. Snapshots can remove requested rows that were deleted or no longer match the selected view; polling never deletes their content.

Status updates are not a complete live feed. Offscreen/unloaded items, newly uploaded videos absent from the loaded results and new matches in Ready/Failed-only views require existing Refresh/return/Load more behavior. Manual Refresh and normal onResume reload retain their earlier behavior. Completed thumbnails reflect the server snapshot; a preview backfilled later can still require Refresh.

The shared poller sends one request at a time. It starts at five seconds, exponentially backs off after failures (10/20/40/60 seconds), and cancels on lifecycle pause/navigation/disposal. With no pending target it makes no network request. Transient errors retain cached video state and show a retry notice; authenticated snapshot rejection clears the owner list. There is no Android background polling, made-up conversion percentage, estimated finish time or new notification service.

The API and continuous Start-VidooraProcessor must remain running for local development. Larger videos and earlier queued jobs take longer. This increment does not start a stopped processor or make the local laptop service available while it is off.

## Backend boundary

New authenticated `POST /api/v1/videos/mine/status` accepts a JSON body with `ids` (1–50) plus the current `search`, `sort`, `state`, `sharing` and `folder` view. It returns only the requesting active owner's non-deleted matching rows. Foreign/missing/deleted/nonmatching IDs are omitted. It reuses libraryPage search escaping, folder ownership and filter predicates, applying a bounded ID set; it returns no storage keys, URLs or cursor. Responses use no-store. Guests and share capabilities alone do not authorize it. IDs/filter text stay out of request URLs. It reads database metadata only, with no R2 calls or content mutation. No migration or new credentials/dependencies are needed.

Details uses the existing authenticated-owner/read-only details endpoint. Automatic replies are fenced by lifecycle epoch, manual-operation revision and share-access context; they cannot overwrite an edit/move draft, deletion, sharing change or newer view. Polling pauses while editing/moving or another action is loading. Library snapshots use view generation and lifecycle fencing, preserve untouched rows and cannot mix old-filter data into a new view.

## Files

Android `app/src/main/java/com/example/vidoora/`:

- New `feature/playback/ProcessingStatusPoller.kt`: lifecycle-controlled single-flight/backoff loop and truthful status labels/guidance.
- `feature/playback/VideoLibraryViewModel.kt`: bounded snapshot DTO, visible targets, merge/error/privacy handling and version fences.
- `feature/playback/VideoLibraryScreen.kt`: visible-card observation and status presentation.
- `feature/playback/PublishingViewModels.kt`: owner detail checks with mutation/edit/lifecycle protection.
- `feature/playback/PublishingScreens.kt`: processing guidance and automatic-ready controls.
- `feature/upload/UploadQueueScreen.kt`: upload-versus-processing explanation and details action.
- `ui/navigation/NavGraph.kt`: LifecycleResumeEffect start/stop and queue/details wiring.
- New test `app/src/test/java/com/example/vidoora/ProcessingStatusTest.kt`.

Backend:

- `server/src/main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt`: bounded status request and shared owner/view predicates.
- `.../PlaybackRoutes.kt`: protected no-store POST endpoint.
- `server/src/test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt`: bounds, owner/deleted isolation, filter consistency and HTTP auth/no-store.
- `.../LibraryManagementIntegrationTest.kt`: real PostgreSQL snapshot/filter/owner checks in its isolated fixture schema.

README, AGENTS and master plan updated. V1–V10 migrations remain unchanged. Existing uploads/sharing/storage objects are untouched by development.

## Verification

Final results: **84 Android tests passed**, zero skips/failures/errors; debug APK built. **74 backend tests passed, 4 opt-in FFmpeg/live R2 tests skipped**, zero failures/errors. Real PostgreSQL snapshot/filter/owner checks executed in generated isolated fixture schemas. App/server source files match verification copies in both directions, with no extra copied source files; V1–V10 migrations are unchanged. The review APK is saved as outputs/Vidoora-processing-status-debug.apk. Phone acceptance remains pending. Tests cover foreground-only/backoff/single-flight behavior, loaded-page/cursor/draft/filter preservation, removal only of requested nonmatches, offline cached-state retention, stale-view response rejection, ready transition without publication, edit protection, guest idleness and unauthorized-list clearing. Backend tests cover authentication, owner/deleted isolation, request bounds and filter/search behavior, including PostgreSQL fixtures.

Actual verification uses source-matching Java 17 copies and the established Android SDK/offline Robolectric init script; approved outside the Windows sandbox to avoid known Gradle cache access failures. Original repository toolchain/dependency configuration is unchanged.

```powershell
# outputs: private encrypted DB-password setup and full server regressions/isolated PostgreSQL checks
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Run-LibraryVerification.ps1
# outputs/phase5-verification, Java 17:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Normal project equivalents: `./gradlew.bat :server:test` and `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest`, with its configured SDK/toolchains. Real PostgreSQL needs the existing private environment setup. Android tests are Robolectric/mock HTTP/coroutine checks, not Compose/phone instrumentation. No live R2 or normal API restart, app install, new real upload/processing result is claimed for this increment.

## Phone acceptance

1. Restart Start-Vidoora, keep the continuous Start-VidooraProcessor window running, then Sync and Run in Android Studio. No SQL Shell or new credentials are required. Keep app data and the existing USB reverse setup.
2. Upload a small test video. On its completed queue card, choose Processing & sharing. Leave details open: it should update to Ready to share without tapping Refresh; a fast job may skip an intermediate state between polls. If already processed, the initial response shows ready immediately.
3. Verify Watch works, then explicitly enable sharing and copy the link. Automatic readiness must not publish or enable sharing by itself. Existing guest playback and logged-out Upload gate must remain intact.
4. In My Videos, scroll a pending card into view and leave it visible. Check its status/preview changes as available, without losing loaded older pages or typed search drafts. A Processing-only filter removes a video when it becomes ready; switch to All/Ready and Refresh to discover newly matching results.
5. Leave/background the screen and return; no background status-poll service should be running. With the API temporarily unreachable, cached rows should remain with a retry notice; restore it and allow the bounded retry interval. Do not confuse this notice with the backend stopping processing.
6. Edit/move a pending video and leave an unsaved draft while processing completes. Automatic status must not dismiss/overwrite it. Cancel/save, then allow the next check. Terminal processing failures should show useful guidance, without a fake success or automatic re-upload.

Phone acceptance, later thumbnail-backfill observation and wider concurrency/load checks remain pending separately. No public discovery, reporting, monetization, hosting or cloud spend is added.

