# Video details and safety reference UI — 27 October 2026

## Scope and references
The user explicitly requested the private/shared/processing Video details pages, delete confirmation, Blocked uploaders and Video unavailable screens from six new attached references. This supersedes deferring those screens in the first reference-UI report. Layout and copy are updated; existing sharing, blocking, deletion, processing and access behavior remain. No fake image, username, processing percentage or new public preview access is added.

## Implementation
- PublishingScreens.kt: centred Back/title/Refresh header; 16:9 preview, title/creator and compact wrapping status/privacy badges; concise processing copy and indeterminate progress; full-width Watch; a grouped sharing card and Edit details/Move to folder rows; separate destructive outlined Delete action. Owner/guest checks and existing callbacks, errors, polling and dialogs are retained. Blocking remains a signed-in non-owner action only.
- VideoDetailComponents.kt (new): reference header, owner-only DetailThumbnail, status badges, sharing card, management rows, stacked Keep video/Delete permanently confirmation, and centred unavailable/deleted states. The confirmation never dispatches deletion until explicit permanent confirmation. Existing ViewModel still enforces state/ownership and handles upload conflicts.
- PublishingViewModels.kt/NavGraph.kt: expose and pass the existing bounded PrivateThumbnailLoader to details. It is invoked only for an owner with PROCESSED state. Real previews are requested once per relevant state/composition; absence returns a neutral placeholder. Processing videos use a spinner/placeholder, not a fabricated source image. No server DTO/endpoint/migration change or cross-account disk cache.
- SafetyScreens.kt: reference-style large heading/subtitle, thin list dividers, initials avatars and compact neutral Unblock controls. Confirmation, loading/error/empty state and cursor pagination remain. The API provides display names but no usernames, so handles from the mockup are deliberately omitted.
- WatchProgressSession.kt: adds a presentation-only unavailable flag for confirmed 401/403/404 or a successful details response that cannot play; existing access requests, denied-progress rules and messages remain. Other server/network failures leave the flag false and retain retry behavior.
- VideoPlayerScreen.kt: uses the centred unavailable screen only for the confirmed preflight-unavailable state before media starts. Native media errors, retry logic, quality/speed, fullscreen, gestures and progress saving remain unchanged. No suggested login bypass or private metadata appears in an unavailable state.
- ic_ui_refresh/edit/folder/unavailable/video.xml: local Android vectors. No new dependency or remote assets.

## Verification
Final executed verification: debug APK and Android test APK built successfully. 94 Android Robolectric/mock/coroutine regression tests passed with zero failures/errors/skips. Seven native Compose UI tests passed on the final build in the separately named AVD at font_scale=1.0/light mode (OK (7 tests), 39.893 seconds). Exact app source hashes match the verification copy in both directions, zero mismatches/extra files. Existing compiler annotation/deprecation warnings remain. Earlier six-case runs passed before the final readiness guard; they are not counted as additional distinct tests. The Android regression suite includes updated WatchProgressTest assertions distinguishing denied/not-ready video from transient server errors. Seven new synthetic native Compose tests cover private/shared/processing layout and callbacks, explicit deletion confirmation, unblock confirmation, unavailable Back and a stale sharing flag with unplayable media. Back/Refresh are asserted visible on the blocked list. They never use an actual account or invoke an API mutation. UI fixture screenshots intentionally show neutral missing-preview placeholders, not real uploaded images.

Commands in the existing Android verification copy:

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

The first native run used Pixel_6/API 37.2 and passed six tests at actual font_scale=2.0/dark system mode (the fixture Material theme was explicitly light). An isolation mistake was then found: -datadir alone did not override explicit data/cache paths in the original AVD configuration. The emulator reused the existing AVD and received the debug/test APK installs and temporary display changes, without uninstalling/clearing its app data. Display settings were restored to font_scale=1.0, normal density and light mode, and the emulator stopped. No physical phone, real API mutation, account deletion or user upload was touched. This was disclosed in chat. Final verification uses a separately named AVD configuration with explicit private data/cache paths and no SD card under the chat outputs; disk paths are checked before claiming isolation. The final hardware-qemu.ini was inspected: data/cache paths are under outputs/details-ui-isolated/avd-home/VidooraUiCheck.avd, with avd.name=VidooraUiCheck and no external SD card. The instrumentation class is com.example.vidoora.VideoDetailsUiTest. Native runner output is outputs/details-ui-native-tests-final.txt; final synthetic screenshots are outputs/details-ui-final/ui-verification/*.png. They were reviewed for the reference hierarchy, concise copy and visible controls. Final APK is outputs/Vidoora-details-ui-debug.apk. The temporary AVD was stopped afterward. No real upload was deleted or account unblocked. Backend/PostgreSQL/live R2/FFmpeg suites are not rerun for this Android presentation change. Real owner thumbnail delivery still needs phone confirmation against the running API; device/tablet/TalkBack/RTL and all remaining screens are not claimed fully verified.

## Phone check
Run the updated app from Android Studio without uninstalling/clearing data. Open a processed video in My Videos: preview, status badges, Watch, sharing card and grouped management actions should match the reference. Check both private and shared states. Open the Delete dialog and choose Keep video to inspect safely. Check Blocked uploaders; do not unblock real accounts unless intended. Try a disabled/expired link to check unavailable. Keep backend/continuous processor running for actual upload/playback; no backend restart or credentials change is needed for this UI change.



Native command (only after installing both APKs into the separately named test AVD):

```powershell
adb -s emulator-5582 shell am instrument -w -r -e class com.example.vidoora.VideoDetailsUiTest com.example.vidoora.test/androidx.test.runner.AndroidJUnitRunner
```
