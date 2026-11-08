# Upload queue deletion and compact media cards

Approved 29 October 2026. This is the user's specific follow-up to cinematic Stage 1: queue deletion/synchronization, horizontal upload/library cards, icon-only Back/Refresh, 12 dp action buttons, and packaging F:\app\app\vidoora_logo.png as a device-masked launcher icon. It does not start the rest of the staged redesign or change the splash screen.

## Behavior

- Swipe left on a queue card to reveal Delete. The options icon provides the same action without a gesture. Confirming deletion permanently removes the video as well as its queue entry; Cancel is still the separate upload-cancellation action.
- DELETING is a persisted local intent, written before upload work is cancelled. The owner-only server DELETE is attempted; an active multipart upload is aborted and DELETE retried. If provider completion won the race, fully acknowledged local ETags allow completion reconciliation before retrying deletion. No partial or invented ETags are submitted.
- Only an accepted server deletion removes the task, cascading multipart rows, managed upload draft and thumbnail cache. A failed request keeps the card and draft, exposes Retry delete, and schedules network-constrained WorkManager retry. Existing login recovery restores DELETING work; logout preserves the intent and stops that account's work.
- Video details/My Videos uses the same repository flow when a matching local queue task exists. Otherwise the existing owner DELETE remains, followed by matching local cleanup. Library resumes refresh the server list. No deletion is inferred from a failed/unauthorized/404 response.
- Server cleanup of raw/HLS assets remains the existing durable deferred process; a server acknowledgement revokes access before physical cleanup. No server code or database migration changed.

## UI

Queue and My Videos cards occupy the available readable content width, with thumbnail left and title/actions right. Queue status sits at top right; progress sits below the thumbnail and disappears for completed/cancelled/deleting uploads. File transfer completion opens Processing & sharing; it is not presented as proof that processing is complete. Existing sharing permissions and readiness gates remain in details.

Thumbnail decoding is bounded and runs off the main thread. Managed upload drafts produce a small private app-cache JPEG; completed uploads can fall back to existing owner-authenticated thumbnails. Android 8.0/API 26 uses the bounded server thumbnail/placeholder rather than an unbounded local frame. Media resources use release() for older Android compatibility; see [Android API reference](https://developer.android.com/reference/android/media/MediaMetadataRetriever). Original source videos outside managed upload_drafts directories are never deleted.

Back and Refresh text actions were replaced by labelled icon buttons. Primary/outlined/tonal buttons explicitly use the shared 12 dp shape, retaining minimum touch targets and callbacks. Text/icon actions keep their normal semantics.

## Launcher artwork

The supplied existing artwork is density-resampled, not replaced with a generated mark. Five legacy density assets and an inset adaptive foreground on a dark background are included; Android supplies circle/squircle/device masks. The supplied raster has a baked tile/glow and small star; it is preserved. No fabricated monochrome silhouette or full splash illustration was added. A clean layered mark remains preferable for future branding refinement.

## Changed areas

- core/data/repository/UploadRepository.kt: shared deletion flow and scoped local cleanup.
- core/database/dao/UploadTaskDao.kt; core/work/MultipartPlan.kt; core/work/UploadWorker.kt: recoverable deletion state and worker retries.
- feature/upload/UploadViewModel.kt; UploadQueueScreen.kt; new UploadPreview.kt; ui/navigation/NavGraph.kt: callbacks, compact queue, confirmation/swipe, preview loader.
- feature/playback/PublishingViewModels.kt; VideoLibraryScreen.kt; VideoThumbnail.kt: synchronized deletion and horizontal library layout.
- Existing auth/upload/playback/profile screen button declarations and Back/Refresh controls: visual shape/icon-only edits.
- res/mipmap-*; drawable-nodpi/vidoora_launcher_art.png; values/vidoora_launcher.xml; drawable/ic_ui_more.xml: launcher packaging/options icon.
- QueueDeletionTest, VideoDeletionUiTest, TestUploadRepository, UploadQueueUiTest, and existing details ViewModel test constructors: explicit repository injection and targeted coverage.

## Verification

Results will be appended after the final build/native run. Earlier builds exposed a local Gradle daemon connection restriction and stale WEBP resources in the verification copy; these were corrected. No actual uploaded video, account, live R2 object, PostgreSQL data, processor, physical phone or production ad was used/deleted by these checks.

## Executed results — 29 October 2026

Final command, in the source-identical local verification checkout:

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

- Final debug/instrumentation build: BUILD SUCCESSFUL, 1m 41s.
- 124 unit tests passed; zero failures, errors or skips. Eight new repository deletion cases cover completed and unfinished deletion, transient failure/recovery, account isolation, unaccepted server replies, matching My Videos cleanup, lost completion acknowledgement reconciliation and preserving original media. The details ViewModel tests additionally verify queue synchronization and pending/success behavior.
- Targeted native command: `adb -s emulator-5582 shell am instrument -w -e class com.example.vidoora.UploadQueueUiTest com.example.vidoora.test/androidx.test.runner.AndroidJUnitRunner`.
- Two native synthetic queue tests passed in 21.173s. Swipe reveal, cancel/confirm, completed-state progress removal and processing/sharing callback verified. Nonblank paused/completed screenshots were captured and visually reviewed: [paused](assets/queue-redesign/queue-paused.png), [completed](assets/queue-redesign/queue-completed.png). These fixtures intentionally use placeholder thumbnails and no real accounts/media.
- Source parity: 163 source files, zero mismatches between project and verification copy. Server source unchanged.
- Debug APK saved in the chat workspace as outputs/Vidoora-queue-redesign-debug.apk. No release build/publication claimed.
- Emulator used a new VidooraQueueVerify AVD with private userdata/cache paths. Initial sandbox runs could not write the default emulator feature lock or use hardware acceleration; the retry used isolated emulator-home paths and approved hardware access. The actual phone and existing AVD data were not modified.

Live backend/R2 deletion and real-device thumbnail/icon-mask acceptance remain manual checks. Use a disposable test video when confirming deletion from Queue and from My Videos; deleting removes the video and invalidates its links. Processor/storage cleanup remains deferred as described above. Full later-stage redesign/splash/live ads/subscriptions remain outside this increment.
