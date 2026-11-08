# Phase 14 — guest link viewing and simpler playback

Approved 27 October 2026. The user confirmed folders work, then replaced the proposed session-security scope with this increment. This document supersedes the older requirement for non-owner viewers to sign in. Session rotation/server logout was not implemented in this phase.

## Behavior

- The app opens Home for signed-out viewers. No guest-login button or guest account is required. Paste a complete active `vidoora://watch/<video-id>#<capability>` link; after a 400 ms quiet interval it opens the player automatically. There is no search/submit step. Invalid/incomplete input stays in the field with guidance. Replacing/clearing text cancels the pending open.
- Only Home, Upload and Profile appear in the main bottom navigation. Upload prompts for creator login/registration. A signed-in creator uses Profile for My Videos, upload queue and blocked-uploaders management. Existing folders, search, filters, metadata editing, sharing and deletion remain inside the creator flow.
- Shared videos open directly in a 16:9 portrait viewport. The native fullscreen button opens a black landscape fullscreen dialog; its exit button or Android Back returns to portrait. The Activity handles orientation/screen-size configuration changes without recreation, and the same ExoPlayer instance remains alive across fullscreen transitions. Leaving the player restores the previous orientation preference. System bars are hidden in fullscreen and can be revealed by swiping.
- Double-tap the left/right video area to seek backward/forward by 10 seconds, bounded by the timeline. Unknown/unseekable timelines are ignored. A short overlay confirms the direction. Buttons and the seek bar keep native touch/accessibility behavior. Seeking does not change pause state, speed, tracks or source.
- Existing Media3 speed controls remain; no duplicate speed implementation was added. Existing Auto/manual quality choices remain available in portrait. Signed-in viewers retain a Video details action for block/owner controls. Retry is available after playback errors, including fullscreen.

## Access boundary

Only details and HLS GET routes use optional JWT authentication. An anonymous non-owner must supply a valid active video-specific share token in the redacted `X-Vidoora-Share` header on every details/master/variant/segment request. Video UUID alone never grants access. Processing, owner status, sharing state, token expiry/version, deletion and suspension checks still apply. Invalid supplied bearer credentials are rejected instead of silently downgrading to guest. Signed-in block preferences still apply; anonymous users have no durable account-based block identity, so blocking is a personal preference rather than a global access ban.

All creator/account routes retain required JWT authentication: uploads, library, folders, previews, edit/move/delete, sharing changes, and block management. Capabilities never become access/refresh tokens, media URLs, saved navigation arguments or persisted link-input state. Input and link diagnostics redact their contents. R2 remains private. A recipient may pass an active link to another recipient; Stop sharing revokes future requests. Bytes already downloaded/buffered cannot be recalled. There is no public listing, public R2 access, new hosting, report feature, monetization or new migration.

## Changed source files

Android under `app/src/main/java/com/example/vidoora/`:

- `core/network/VideoShareAccess.kt`: complete-capability parsing helper.
- `feature/home/HomeScreen.kt`, new `VideoLinkEntry.kt`: minimal Home and bounded debounced memory-only link input.
- `ui/navigation/NavGraph.kt`: guest Home, protected creator destinations, three bottom tabs, direct pasted/deep-link player navigation and signed-in details access.
- `feature/profile/ProfileScreen.kt`: optional creator login and account-management destinations.
- `feature/playback/PublishingScreens.kt`, `PublishingViewModels.kt`: guest-compatible details and updated sharing copy; block controls remain signed-in only.
- `feature/playback/VideoPlayerScreen.kt`, new `GesturePlayerView.kt`: portrait/fullscreen lifecycle, native controller, double-tap seeking and feedback.
- `app/src/main/AndroidManifest.xml`: orientation/screenSize handling. Three `app/src/main/res/drawable/ic_nav_*.xml` vector tab icons.
- Tests: new `GuestLinkInputTest.kt`, `PlayerGesturesTest.kt`; extended `PlaybackDataSourceTest.kt`.

Backend under `server/src/`:

- `main/kotlin/com/vidoora/backend/modules/playback/PlaybackRoutes.kt`: optional authentication limited to read-only details/HLS.
- `main/kotlin/com/vidoora/backend/modules/playback/PlaybackService.kt`: nullable viewer for capability-protected read access.
- `test/kotlin/com/vidoora/backend/PlaybackSecurityTest.kt`: anonymous link/asset access, missing/wrong/expired/revoked/deleted/suspended cases, account-route denials and invalid-bearer handling.
- `test/kotlin/com/vidoora/backend/LibraryManagementIntegrationTest.kt`: anonymous access and revocation against isolated PostgreSQL fixture rows.

README, AGENTS and master plan updated. V1–V9 migration hashes are unchanged. Existing uploads, normal folders and R2 objects were not changed by this development work.

## Verification

Backend: 66 passed, 4 skipped, zero failures/errors. Full suite executed with the encrypted local DB password through `Run-LibraryVerification.ps1`. PostgreSQL uses generated validated isolated schemas and removes only its own fixtures. R2/FFmpeg/live-deletion suites were not opted in. The 36-test playback security class was also explicitly rerun without cached results.

Android final result: 69 passed, zero skips/failures/errors; debug APK built. Native gesture tests use Robolectric API 28, not a real phone. An initial new controller-click assertion failed because posted clicks had not been drained from the test main looper; the final run drains it before checking clicks. Fullscreen/rotation/visual layout and real guest HLS playback require phone acceptance. Final app/server source hashes match the verification copies. The built review APK is saved as outputs/Vidoora-guest-viewing-debug.apk; updating through the existing Android Studio project avoids changing the installed signing identity.

Actual commands use the established source-matching verification copies and Java 17 because of earlier Windows sandbox Gradle-cache restrictions; original Java 25 daemon/dependency settings are preserved. Builds ran with approved sandbox escalation. No new dependencies.

```powershell
# outputs/phase4-verification, Java 17; explicit uncached security run:
.\gradlew.bat test --tests com.vidoora.backend.PlaybackSecurityTest --rerun-tasks '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
# outputs: full suite including real PostgreSQL fixture, credentials loaded privately:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Run-LibraryVerification.ps1
# outputs/phase5-verification, Java 17:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Equivalent project commands (with the existing configured toolchains/SDK) are `./gradlew.bat :server:test` and `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest`; real PostgreSQL fixture tests require the existing private environment setup. Never paste or print credentials/share tokens to run checks.

## Phone acceptance

1. Restart the existing Start-Vidoora backend launcher, then Sync and Run this existing project in Android Studio. Keep app data; do not uninstall. Keep the laptop/API reachable and USB reverse active for local development. No API restart or phone installation was performed by the agent.
2. Obtain an enabled complete sharing link from a creator. Log out, return Home, and paste the link. Verify it starts playback without login, a guest button or Search.
3. Double-tap each side and test pause, scrubbing, quality and speed. Check the start/end bounds with a sufficiently long video. Enter fullscreen, verify landscape, then exit using both its button and Android Back. Confirm current position/play state/speed survive, including pause/resume and retry.
4. As a guest, Upload must prompt for login and Profile must expose no uploader data. Log in and check My Videos/queue/folders and account switching still work.
5. Stop sharing from the creator account; paste the old link while logged out. It must fail. A UUID-only, expired or wrong-video link must not play. Check a private video remains owner-only.
6. Check native layout/controller hit areas on the actual phone. On large-screen/new Android configurations the OS may override orientation requests; this release has not been device-verified there.

No live R2 or phone result is claimed for this increment. Earlier physical deletion-cleanup observation, near-limit upload/load checks and deployment remain separate pending work.

