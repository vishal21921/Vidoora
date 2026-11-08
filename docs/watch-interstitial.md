# Vidoora Watch interstitial and actual-viewing breaks — 29 October 2026

## Current behavior
Explicit Watch first performs the existing fresh details access check, then requests/shows a fullscreen Google demo AdMob interstitial. Dismissal or failure continues once into the existing independently authorized player. No ready inventory can wait up to eight seconds while the details action is disabled; timeout/no-fill/consent failure continues without displaying a late ad over video. Login state and video ownership confer no ad exemption. Watch remains in My Videos; upload/profile management never displays ads. There is no app-wide cooldown/session impression cap. Consent/request permission and inventory availability still apply; actual ad delivery cannot be guaranteed.

The player initially has NO IMA pre-roll. After ten minutes actually playing foreground content, it pauses at the content position, enters fullscreen for a dedicated IMA demo video-ad break and restores its previous fullscreen mode when content is ready again. The clock excludes paused, buffered, background and ad intervals, and seeking/speed do not credit content-timeline distance. Within ten seconds of the content ending, no new break is requested. A fresh player has a fresh local watch clock. SDK ad failures/no fill can return to content rather than blocking it indefinitely. Ordinary interstitials are never placed by the player timer.

## Code
- feature/ads/WatchAdGate.kt: SDK-independent single Watch state machine, loading deadline, one show/continuation, no late/stale callbacks and cancellation across navigation/account changes.
- ViewerAdsController.kt: UMP-gated demo interstitial (1033173712) preloaded on eligible details, cache age under 55 minutes, on-demand first-click load and foreground show guard; eight-second load deadline, native dismiss/failure callback. No real ad IDs or account/video fields in ad requests. Existing demo banner/privacy controls remain.
- ui/navigation/NavGraph.kt: fresh access -> interstitial -> player; disables action while waiting; cancels pending callbacks on entry/identity disposal. Player still checks access again and every HLS asset retains server authorization.
- VideoWatchAdClock.kt: bounded monotonic elapsed-viewing state, scoped to a player rather than account/app time.
- VideoAdSession.kt/VideoAdSchedule.kt: no wrapping on ordinary entry; only an explicitly armed break creates an IMA loader and single-break demo VMAP. Each break gets a new local ad-state ID. Private HLS still uses AuthenticatedMediaDataSource and ad media uses a separate standard factory, without credentials/capabilities.
- VideoPlayerScreen.kt: lifecycle/player-event clock updates and one-second due check; content-only checkpoints, saved position before a break, prior fullscreen restoration, SDK skip UI and ad-seek prevention. Production codec settings are unchanged.

This does not implement subscription billing, paid entitlements, live units, Ad Manager inventory or earning guarantees. Release remains VIEWER_TEST_ADS=false. A future verified ad-free entitlement must suppress both request paths; a no-fill event is not a subscription state.

## Verification
Debug and instrumentation build succeeded; 112 unit tests passed (zero failures/errors/skips). Seven new Watch gate tests cover ready/load/no-fill/timeout/duplicate/identity and show-failure behavior. Six replacement clock/VMAP tests cover actual elapsed intervals, pauses/background/ad exclusion, seek/ending behavior, independent players and bounded clock changes. An additional opt-in native interstitial check was compiled separately. Native outcomes are recorded below after execution. No backend/PostgreSQL/R2/FFmpeg suite or physical-device install was performed.

Evidence: outputs/watch-interstitial-build.txt and watch-interstitial-instrumentation-build.txt. Build command uses Java17, :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest with the existing phase5-test-home.init.gradle, in-process Kotlin, --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain. Only :app:assembleDebugAndroidTest was repeated for the added native test.

Native checks use a separately named VidooraWatchAdVerify AVD, explicit private disk paths and emulator-5582. Google demo inventory/public sample content only. The video SDK smoke explicitly arms a test break (without spending ten real minutes) and asserts an ordinary entry returns unwrapped content. The interstitial smoke observes the SDK AdActivity and attempts only Back/close, never an ad creative click. These tests are opt-in and skip when UMP cannot permit requests; a fallback pass must not be described as delivered ads. Physical-device ten-minute behavior and real private HLS with ads remain acceptance checks.

## Sources and limits
- https://developers.google.com/admob/android/interstitial
- https://developers.google.com/admob/android/privacy
- https://support.google.com/admob/answer/6201362
- https://developer.android.com/media/media3/exoplayer/ad-insertion

The first placement is an ordinary fullscreen interstitial; the ten-minute fullscreen break uses dedicated video-ad handling to respect active-playback placement rules. Demo ads earn no publisher revenue. The release APK was not rebuilt for this Android logic correction; its source ad flag remains OFF and the earlier unsigned release must not be treated as containing this latest correction.

## Executed native results — 29 October 2026
The final isolated-emulator run completed OK (4 tests), 96.886 seconds, with no skips. Both restored owner details/Watch/sharing UI checks passed. The video SDK smoke first asserted ordinary entry returned the unwrapped content source, explicitly armed a test break, and recorded consentAllowed=true; adStarted=true; contentReady=true; playerError=0. It uses Android software codecs for the previously documented headless Goldfish limitation, without changing production decoder settings.

The interstitial smoke recorded consentAllowed=true; fullscreenActivityObserved=false; continuations=1. Thus no actual fullscreen interstitial delivery is claimed by this run: bounded unavailable-inventory continuation passed. Filtered Ads logs contained an HTTP timeout; the exact cause of absent inventory is not established. Pure unit tests separately exercise successful ready/load/show/dismiss callbacks, stale-load cancellation and repeated Watch requests. No SDK creative was clicked.

Evidence: outputs/watch-interstitial-native-tests.txt, watch-interstitial-source-parity.json, watch-interstitial-evidence/watch-interstitial-smoke/outcome.txt and watch-interstitial-evidence/video-ads-smoke/outcome.txt. All 152 source/test files and the app Gradle script match the verification copy. Debug/instrumentation builds and 112 unit tests passed. The debug artifact is outputs/Vidoora-watch-interstitial-debug.apk. A full ten-minute physical-device/private-HLS check and actual initial interstitial delivery are still pending; the clock tests use a simulated monotonic time source and the native video smoke deliberately arms its test break early.

Next: keep the existing backend available, Run the updated source in Android Studio, open My Videos or a valid shared link and tap Watch. Verify the fullscreen interstitial (if served) precedes video, no in-player entry pre-roll appears, and ten minutes actually playing content triggers the dedicated fullscreen video-ad break and resumes at the same position. Pause/background/buffering must not advance the clock. Ads are demo-only, consent/inventory dependent; no live earnings or paid entitlement is implemented.
