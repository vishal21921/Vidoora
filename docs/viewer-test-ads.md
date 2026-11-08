# Viewer-only AdMob test integration — 29 October 2026

## Approved behavior
The user approved viewer monetization after discussing frustration and Google placement guidance: shared link -> video details -> explicit Watch -> eligible preloaded interstitial -> playback. One banner can accompany portrait playback, separated from player controls. No Home/paste-field banner, creator-management ads, app-open/exit ads, or timed mid-video interruptions. Ten minutes is a cooldown for a later explicit Watch transition, not a timer that interrupts a video.

This is a TEST integration. Debug ad units are Google's public demo units. Release sets VIEWER_TEST_ADS=false, so application code does not initialize UMP/GMA or request/show ads. The SDKs/providers remain bundled; this is not an ads-free binary or a store-ready signed release. No live ad units, earnings, creator payouts, mediation, hosting or account approval have been configured or claimed.

## Implementation
- app/build.gradle.kts: Google Mobile Ads conventional Android SDK 25.5.0 and UMP 4.0.0, matching the checked official guides. Debug enables demo requests; release explicitly disables them. A validated public vidooraAdmobAppId property defaults to Google's sample app ID. Neither build accepts a live ad-unit ID.
- AndroidManifest.xml: app-ID placeholder and DELAY_APP_MEASUREMENT_INIT=true. The SDK merges its own permissions/providers, including advertising-ID support; privacy/store disclosures must be reviewed before any public release.
- feature/ads/ViewerAdPolicy.kt: two recorded in-app interactions before an interstitial, ten-minute persisted wall-clock cooldown, maximum two impressions per process session. Count only an SDK on-show callback; missing/failed ads continue immediately without spending the limit. A malformed/future timestamp or persistence failure skips ads. No video/account/link identifiers are saved in the app frequency store.
- ViewerAdsController.kt: UMP update before requests, canRequestAds as the authority, deferred initialization, main-thread load/show, expiring preloaded interstitials (55 minutes) and event-driven failure backoff. No ad timer or background ad worker. Cached replies are generation-fenced after navigation/privacy changes. Double callbacks/taps continue once; stale navigation/account callbacks are cancelled. Activity launch changes refresh UMP. Pending consent forms are deferred once Watch is tapped, preventing a late form over playback; privacy choices remain explicitly accessible when required.
- ViewerAdComponents.kt: lifecycle-managed SDK views and viewer scopes. Portrait banner only after access/media readiness, while resumed, outside fullscreen/errors. At least 24dp vertical separation, labelled reserved ad area, no overlap or app-controlled refresh timer. Narrow areas under 320dp omit the banner. Failure removes the slot; SDK views are destroyed when no longer eligible. An optional callback reports only load success/error code for native verification, never identifiers.
- NavGraph.kt: pasted/external links now open details; capability remains memory-only. An external app launch does not count as an in-app interaction, so its first Watch may skip an interstitial. Pasting a valid link plus Watch supplies two actions. Before an ad, VideoDetailsViewModel.watch freshly validates the existing details endpoint; after an ad, the player performs its existing independent access check. Viewer entry is explicit in navigation and requires a remembered complete link. My Videos and upload-queue details are management entries without Watch or ad initialization, even if that video has a previously remembered capability. Everyone watching through a link, including its uploader, is eligible for the same viewer ads. The player rejects missing link capabilities at navigation; existing fresh server checks remain.
- PublishingViewModels.kt: bounded fresh Watch access check with stale capability/revision guards; no new backend endpoint, database or R2 permission.
- WatchProgressSession.kt/VideoPlayerScreen.kt: viewer eligibility travels with verified details and survives Start over. Banner starts only with ready media; old fullscreen/quality/speed/seeking/progress behavior remains. Portrait content scrolls to keep controls reachable.
- ProfileScreen.kt: Ad privacy choices only when UMP requires that entry, for guests and creators. A privacy control is not an ad placement.
- HomeScreen.kt: copy now says video details open automatically; Watch explicitly starts playback.

No private video URL, capability, title, account ID or email is attached by application code to an AdRequest. Google SDKs have their own documented collection behavior; this is not a claim that SDKs collect no device data.

## Configuration while the account is under review
No private credential is needed. The default Google sample app ID is ca-app-pub-3940256099942544~3347511713. Banner uses the demo adaptive unit ending 9214589741; interstitial uses the demo unit ending 1033173712. They do not earn money. Interstitial creative can be image/video/interactive; Google controls its close affordance and timing. A legitimate close continues playback; no ad click or reward is required.

If testing your own configured UMP messages later, add only the public APP ID to your untracked user Gradle file (%USERPROFILE%\.gradle\gradle.properties):

```properties
vidooraAdmobAppId=YOUR_ACTUAL_ADMOB_APP_ID_CONTAINING_TILDE
```

Replace the placeholder with the complete app ID; an ad-unit ID containing / is not accepted. This property does not enable live inventory. Do not place credentials/payment data in this file or chat. Consent messages are managed in your AdMob Privacy & messaging configuration. If UMP cannot permit requests, ads are skipped and video access/playback still works. No fabricated consent or forced geography is used.

Before live rollout: account/app readiness, actual ad units, privacy messages/policy and store disclosures, audience/content eligibility for user uploads, and appropriate app/store/hosting configuration need a separate reviewed change. Link-only/private storage does not exempt ad-supported content from Google's policies. Account approval alone is not proof that every uploaded video can be monetized.

## Executed verification
Final build on 29 October 2026 succeeded: debug APK, unsigned release APK and instrumentation APK built; all 105 Android unit/Robolectric/mock tests passed (zero failures/errors/skips). Release BuildConfig.VIEWER_TEST_ADS=false was inspected. All 145 app source/test files match the verification copy, with equal file counts and hashes; the app Gradle script also matches. Existing compilation/deprecation warnings remain; no build failure was suppressed.

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:assembleDebugAndroidTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

New tests: seven ViewerAdPolicyTest cases cover action threshold, persisted cooldown/clock changes, two-impression cap, no-fill/failure/exception continuation, duplicate taps/callbacks, cancellation/identity change and persistence failure. Three ViewerWatchAccessTest cases cover fresh capability headers, denied/stale responses, owner detection and transient failures. WatchProgressTest checks uploader viewer-ad eligibility and checks viewer eligibility survives Start over.

Native verification uses a separately named VidooraAdsVerify AVD with explicit private data/cache paths and no SD card; actual hardware-qemu.ini paths are verified. The existing phone/AVD is not installed/cleared. Seven VideoDetailsUiTest regressions use synthetic state; an opt-in ViewerAdsSdkSmokeTest contacts Google using demo inventory and records consent/initialization and banner load results separately. It never forces consent or claims a missing/failed ad was delivered. The native smoke is skipped in default instrumentation runs unless adsSdkSmoke=true is explicitly passed.

```powershell
adb -s emulator-5582 shell am instrument -w -r -e class com.example.vidoora.VideoDetailsUiTest,com.example.vidoora.ViewerAdsSdkSmokeTest -e adsSdkSmoke true com.example.vidoora.test/androidx.test.runner.AndroidJUnitRunner
```

No backend/PostgreSQL/R2/FFmpeg suite is rerun. Native banner load, native interstitial dismissal, real shared-video end-to-end delivery, physical device, full consent-form/privacy-choice interaction and landscape acceptance must be distinguished from the pure/mock policy tests; only observed results below are claimed.

## Phone acceptance
Keep the existing backend available and run the debug source from Android Studio without uninstalling/clearing data. Paste a valid shared link as guest, non-owner or uploader: details open, then tap Watch. No ready ad means immediate continuation; a ready eligible test interstitial closes into playback. Repeated taps, resume, seek, quality changes and elapsed viewing time must not create ads. Observe a portrait test banner only when consent/request conditions allow and media is ready; fullscreen hides it. Upload/library/profile management remain ad-free and have no Watch action. Enable sharing and paste the complete link on Home to watch your own video with viewer ads. Google controls fill: a skipped ad is an expected path. No backend restart is needed for the Android change.

## Official sources checked
- https://developers.google.com/admob/android/quick-start
- https://developers.google.com/admob/android/privacy
- https://developers.google.com/admob/android/test-ads
- https://developers.google.com/admob/android/interstitial
- https://support.google.com/admob/answer/6201362
- https://support.google.com/admob/answer/6275345
- https://support.google.com/publisherpolicies/answer/11112688

### Observed native/network result — 29 October 2026
The final isolated-emulator run completed with OK (8 tests), 220.733 seconds: seven synthetic UI regressions plus one opt-in SDK/non-blocking-continuation smoke. Actual UMP/SDK state was allowed=true, initialized=true, privacyPending=false, privacyMessage=false. BannerLoaded remained null after the bounded 45-second banner wait: neither success nor failure callback arrived before capture. The native screenshot showed the reserved Advertisement area with no creative. Filtered SDK logs reported HTTP timeouts (60000 ms). This is not proof of delivered ads or a diagnosed cause of the incomplete request. Continuing remained usable before/after the request. No actual native interstitial display/dismissal, earnings, physical-device ad delivery or real shared-video end-to-end ad session is claimed. Policy/access tests simulate these callbacks separately.

Evidence in the chat workspace outputs: viewer-ads-build-final.txt, viewer-ads-native-tests-final.txt, viewer-ads-smoke-final/ads-sdk-smoke/outcome.txt and screen.png. Vidoora-viewer-test-ads-debug.apk is the built debug package using demo inventory. The release APK is unsigned and not published. No live IDs or credentials were needed. The next acceptance step is Android Studio on the user's existing device with a valid shared link (including an uploader watching their own video), then explicit Watch, observing either a demo ad or uninterrupted skip/playback. Do not treat an absent ad as confirmation that delivery worked.


## Link-only Watch correction — 29 October 2026
Approved clarification: no Watch button from My Videos/Profile or upload-queue management. Pasted/incoming complete links enter viewer details; uploader ownership no longer exempts this playback from viewer ads. Ads remain conditional on UMP, availability and frequency; a missing ad never blocks video. Management access never becomes viewer access merely because a capability was remembered earlier.

Changed Android files: ui/navigation/NavGraph.kt (explicit viewer query/default management route, capability check and guarded Watch), feature/playback/PublishingScreens.kt (Watch only on viewer entry), VideoViewingPolicy.kt (shared presentation/navigation condition), WatchProgressSession.kt (verified player startup is viewer mode regardless of ownership), VideoDetailComponents.kt (private management wording). Tests: WatchProgressTest.kt now checks uploader viewer eligibility; VideoDetailsUiTest.kt checks no Watch on private/shared management and a working Watch for uploader link-viewing. Existing backend owner authorization is unchanged; no server-side monetization/access restriction is implied.

Executed command in outputs/phase5-verification:
```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```
Build succeeded; 105 unit tests passed, zero failures/errors/skips. All 146 source/test files exactly match the verification copy. The debug APK was refreshed. Release was not rebuilt for this correction; its source ad-enable flag remains false. No new live ad/network, backend, PostgreSQL or R2 test is claimed. Native correction results are recorded below when finished.

Correction native result: OK (8 tests), 331.379 seconds on the separately named VidooraLinkVerify emulator. Private/shared management entries had no Watch action; owner link-viewing exposed Watch and invoked its callback once. Existing sharing, processing, unavailable, delete-confirmation and unblock-confirmation checks passed using synthetic data. This is UI/callback evidence, not actual media/ad delivery or full NavHost/backend end-to-end verification. Command: adb -s emulator-5582 shell am instrument -w -r -e class com.example.vidoora.VideoDetailsUiTest com.example.vidoora.test/androidx.test.runner.AndroidJUnitRunner. Output: outputs/link-viewing-native-tests.txt; build: outputs/link-viewing-build.txt. No physical-device install or account/storage mutation was performed.

Latest direction supersedes the interstitial and management-only stages in this report: owner Watch is restored and dedicated demo video ads replace the app-wide interstitial policy. See video-ads.md for current implementation and verification.
