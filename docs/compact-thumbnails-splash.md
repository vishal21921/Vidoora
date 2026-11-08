# Compact cards, editable private thumbnails and splash

Approved 29 October 2026 as a specific follow-up to the queue UI work.

## User-visible behavior

- Queue cards show a single-line ellipsized title, compact status with the options icon on its right, and one main state action. Completed transfers say **Share**, opening the existing processing/sharing details; processing readiness and explicit sharing opt-in remain enforced. File size and numeric progress text are removed. Active transfers keep their real progress bar and Pause/Resume/Retry actions, with cancellation in the options menu and confirmed deletion via the existing swipe flow.
- Left thumbnail and right details share a row height. Large text can increase that row together; no fixed text clipping is used. My Videos shows title, Private/Shared by link and View video, with duration over the thumbnail and one-line titles.
- Owners can select **Change thumbnail** on a processed video's details screen. The selected image is center-cropped and converted to a 480×270 JPEG. Input is capped at 8 MiB, dimensions/pixels are bounded before decoding, and the Android decoder samples large images. Files are read only from the picker-granted content URI and are not retained as public originals.
- Opening a complete active link in the app shows the video's existing/generated or selected thumbnail. A bare video ID grants no viewer thumbnail access. No public R2 bucket/URL, token URL parameters, guest disk cache or social-media rich-link preview endpoint was added.
- Splash uses the existing logo artwork and a horizontally moving segment occupying 30% of the bar. The brand screen waits at least two seconds and still waits for actual session restoration if that takes longer; it never invents a restoration percentage or treats a timer as authentication.

## Secure thumbnail API

- GET /api/v1/videos/{id}/thumbnail now accepts optional authentication plus X-Vidoora-Share. It reuses the video/HLS owner/capability authorization, including expiry, video scope, suspended creators, blocking, deletion and sharing-version revocation. Responses are no-store, image/jpeg and nosniff. Processing must be complete and the stored key must be exactly the generated or custom thumbnail under the committed HLS attempt.
- PUT on the same path requires a normal authenticated owner. A share capability cannot edit. The request body is streamed with a 256 KiB bound; the server accepts only bounded JPEGs, verifies dimensions before pixel decoding, and re-encodes to remove metadata/arbitrary payloads. Storage is private and restricted to configured buckets and the owner's committed video prefix.
- The custom key is fixed custom-thumbnail.jpg under that attempt, avoiding a new orphan image per edit. Owner/deleted/processed state is rechecked under the video row lock before writing. Thumbnail backfill leases are cleared on a custom edit and backfill completion cannot replace an existing/custom thumbnail. Existing scoped video deletion cleans up both image variants.
- Details/library expose an opaque timestamp version, not storage paths. Previews reload after an edit; a resumed queue refreshes its loader and prefers the owner's server image over the local upload-frame cache. Share versions, video visibility, playback media and links remain unchanged by thumbnail editing.
- No database schema/migration change or new external configuration is required. Restart the backend and rebuild/reinstall Android to use the new read/edit endpoints.

## Main changed files

Android: feature/upload/UploadQueueScreen.kt, UploadPreview.kt, UploadViewModel.kt; feature/playback/VideoLibraryScreen.kt, VideoThumbnail.kt, PrivateThumbnailLoader.kt, PublishingViewModels.kt, PublishingScreens.kt, VideoDetailComponents.kt, VideoLibraryViewModel.kt; new ThumbnailPreparation.kt; feature/splash/SplashScreen.kt; ui/navigation/NavGraph.kt.

Server: modules/playback/PlaybackRoutes.kt, PlaybackService.kt and new ThumbnailImages.kt; infrastructure/S3StorageService.kt; modules/processing/ThumbnailWorker.kt. Tests: PlaybackSecurityTest, PrivateThumbnailTest, ThumbnailPreparationTest, UploadQueueUiTest.

## Verification

Final results are appended after execution. Checks use mock HTTP, H2/test storage and a dedicated synthetic Android emulator. No saved credentials, real accounts, PostgreSQL data or uploaded R2 objects are read/modified by these tests. Real selected-image upload/guest preview against the running backend remains a manual acceptance step.

## Executed verification — 29 October 2026

The source-identical verification copy ran:

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :server:test --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

- Combined build succeeded (1m 43s). Android: 127 unit tests passed, zero failures/errors/skips. Backend: 73 tests passed, eight opt-in integration cases skipped, zero failures/errors (81 discovered). Backend tests use H2/mock storage; no live R2/PostgreSQL/FFmpeg integration pass is claimed.
- One newly added Android invalid-input test initially failed because the legacy test decoder accepted arbitrary short input. Explicit supported-image header/length validation was added; all 127 then passed. The passing results include bounded image preparation and capability-in-header thumbnail loading.
- Backend tests verify private/owner access, guest and signed-in active-link reads, video scope, expiry, uploader blocking, revoked links, owner-only editing, read-only share capabilities, preserved sharing settings, deleted-video denial, metadata sanitization and HTTP payload/type bounds.
- Five distinct isolated native cases passed in 83.766s: compact completed queue/Share action, swipe/delete confirmation plus options-menu cancellation, single-line library title/duration overlay, minimum two-second ready-session splash, and session-restoration wait. The first cold-start instrumentation process was killed for LOW_MEMORY before assertions; the device exit receipt is retained. The successful retry used the already installed synthetic test app.
- Visual review caught an inherited black splash title. It was corrected to the explicit theme foreground. The final debug/unit/instrumentation rebuild succeeded in 1m 41s, and the updated splash timing/color/capture case passed in 6.188s. The corrected screenshot was visually reviewed. No assertion failure was hidden by the retry.
- Final source parity: 165 Android source/resource/test files and 60 server files, zero mismatches. No schema migration or launch of the actual backend/processor occurred. The physical phone and existing AVDs were not modified.
- Four nonblank synthetic UI captures are saved: [completed queue](assets/compact-thumbnails/queue-completed.png), [paused queue](assets/compact-thumbnails/queue-paused.png), [library](assets/compact-thumbnails/library-compact.png), [corrected splash](assets/compact-thumbnails/splash.png). Queue/library fixture thumbnails are intentionally placeholders; these images do not prove real gallery/R2 transfer.
- Final debug APK: chat workspace outputs/Vidoora-compact-thumbnails-debug.apk. No release build, live ads, subscription rollout or publication is claimed.

### Manual acceptance

Restart the backend using the existing secure launcher, then rebuild/run Android (or install the final debug APK). Open My Videos → View video → Change thumbnail on a processed video, select an image and check the updated preview. With sharing enabled, open its complete link as a guest/other account to check the preview and playback; after Stop sharing the same link must be unavailable. Real gallery selection/R2 transfer on the physical phone remains unexecuted by this work.
