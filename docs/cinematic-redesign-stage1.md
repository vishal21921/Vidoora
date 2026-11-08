# Vidoora cinematic redesign — Stage 1

Date: 29 October 2026. Scope approved in the attached complete UI/UX redesign brief: Stage 1 inspection and shared design system only. Stage 2 branding/splash and stages 3–6 screen layouts require separate approval. This document supersedes the older light/indigo UI direction for future approved UI stages, not any functional behavior.

## What was inspected
AGENTS.md, master-plan decisions/history, README, actual Android theme/navigation/screens/dialogs/ViewModels, native player/gesture/ad boundaries, launcher/theme resources, and both attached images. Existing Android app and Kotlin/Ktor architecture are retained. Earlier audit milestones are historical; current source includes the guest link-only player, owner management, upload durability and demo ad flows described in the later master-plan entries.

The attachment set contains TWO images, not three: a 953×635 collage of twenty designs and a 2048×2048 square logo illustration. There is no separate full-resolution cinematic splash image. The last collage row is cropped at the bottom and text is too small for exact pixel/text measurements; use the visual hierarchy and the written brief, not image artifacts.

## Screen-to-source map
Paths below are under app/src/main/java/com/example/vidoora/. Every requested screen has an existing implementation or branch; none requires inventing a backend feature.

| Reference | Existing implementation | Later layout stage |
|---|---|---|
| 1 Splash | feature/splash/SplashScreen.kt; SplashViewModel; MainActivity and res/values/themes.xml | 2 |
| 2 Guest Home | feature/home/HomeScreen.kt and VideoLinkField | 3 |
| 3 Upload | feature/upload/UploadFormScreen.kt | 3 |
| 4 Signed-in Profile | feature/profile/ProfileScreen.kt, user branch | 3 |
| 5 Guest Profile | same ProfileScreen, null-user branch | 3 |
| 6 Login | feature/auth/login/LoginScreen.kt and AuthComponents.kt | 4 |
| 7 Create account | feature/auth/register/RegisterScreen.kt and AuthComponents.kt | 4 |
| 8 My Videos | feature/playback/VideoLibraryScreen.kt; VideoThumbnail | 5 |
| 9 Expanded filters | inline filter section in VideoLibraryScreen.kt | 5 |
| 10 Upload queue | feature/upload/UploadQueueScreen.kt and UploadTaskCard | 5 |
| 11 Ready details | feature/playback/PublishingScreens.kt VideoDetailsScreen; VideoDetailComponents.kt | 5 |
| 12 Processing details | same details state branches and VideoProcessingStatus | 5 |
| 13 Portrait player | feature/playback/VideoPlayerScreen.kt and native PlayerSurface | 6 |
| 14 Fullscreen player | same screen's FullscreenVideo/PlayerSurface; GesturePlayerView.kt | 6 |
| 15 Resume dialog | existing prompt in VideoPlayerScreen.kt, backed by WatchProgressSession | 6 |
| 16 Manage folders | feature/playback/FolderScreens.kt FoldersScreen | 5 |
| 17 Edit details | feature/playback/EditVideoDialog.kt | 6 |
| 18 Delete confirmation | feature/playback/VideoDetailComponents.kt DeleteVideoConfirmation | 6 |
| 19 Blocked uploaders | feature/playback/SafetyScreens.kt BlockedUploadersScreen | 5 |
| 20 Unavailable | feature/playback/VideoDetailComponents.kt VideoUnavailableScreen | 6 |

Other existing UI is preserved: OpenVideoLinkScreen, creator login prompt, move-to-folder and folder rename/removal dialogs, upload errors/empty queue, sharing/private states and ad privacy controls. Home/Upload/Profile remains the complete bottom-tab set. No public recommendations/feed, reports, fake thumbnails, earnings or subscription screen is added.

## Current visual gaps
Upload, queue, library filters and folders still have generic Material layouts; the earlier reference pass primarily covered Home/auth/Profile and details/safety. Password fields are masked but lack a visibility toggle; Stage 4 can add local presentation state without changing auth validation. The current blocked-uploader DTO has displayName/id only, so the collage's @handles must not be fabricated or added to an API in a UI-only stage. Existing thumbnails are owner-only; do not grant guest image access or fill missing previews with sample photos. Player controls, background execution and upload/processing percentages must remain real.

## Shared foundation implemented in Stage 1
- Color.kt: named VidooraColors and VidooraSpacing tokens. Near-black #070B17, navy surfaces #0D1528/#142039, bright #F8FAFF text, muted #9BAAC5, coral/pink/violet identity and semantic success/warning/error colors. Legacy unused color aliases retained for source compatibility.
- Theme.kt: cinematic dark is now the default; explicit darkTheme=false and dynamic-color opt-in remain. Full Material color roles, surface levels and 12/18/24dp component shapes. System-bar icon appearance follows the selected Compose theme, without changing activity/navigation/session logic or the launch theme XML.
- Type.kt: sans-serif hierarchy with readable 28sp page headings, 22sp titles, 16/14/13sp body and 14/13/12sp labels; system font scaling retained.
- DesignComponents.kt: VidooraGradientButton, VidooraOutlinedButton, VidooraCard, VidooraTextField, VidooraTopBar, VidooraStatusChip, VidooraLoadingState and VidooraEmptyState. Pure controlled callbacks/values; no ViewModels, backend calls, validation rules, password state or navigation inside them. Minimum 48dp interactive targets, 52dp action buttons, wrap-capable labels and Material ripple feedback. Loading is indeterminate and disables the shared action rather than fabricating percentages.
- ReferenceComponents.kt: existing PrimaryAction/ReferenceCard/ReferenceHeader APIs delegate to shared components, preserving all callers and callback signatures. UiIcon, the temporary old circular brand placeholder and FormError remain. No alternate logo is drawn; Stage 2 replaces the placeholder with approved artwork.
- DesignSystemPreviews.kt: synthetic component gallery with narrow/normal/landscape/large-text/light previews, absent from app navigation. Existing Home/login size previews remain. This is a component review surface, not a new app screen or fake production data.

Bottom navigation and video-card/dialog-specific layouts are deliberately reused rather than duplicated into speculative abstractions; they will adopt the tokens in their approved stages. There is no premature rewrite of the twenty screen layouts.

## Reference differences and accessibility
The supplied bright coral #FF7849 and pink #FF315B have white-text contrast approximately 2.61:1 and 3.59:1, below 4.5:1 for normal text. They remain identity/feedback accents. Prominent button gradient stops are darkened to #B83E23 → #C11F4F → #8022C4 (white contrast approximately 5.60:1, 5.87:1, 7.13:1). Automated checks sample interpolated gradient colors as well. Cards remain restrained rather than glowing. Text and controls scale rather than copying the collage's tiny text or fixed image dimensions.

Global theme/shared control colors apply now; exact reference screen composition is still pending stages 2–6. The default is dark even on a light-system device, matching the requested direction. The explicit light scheme is supported, not a new settings feature. Existing screen-specific hardcoded status colors, old bottom-bar composition and native launch theme remain staged work; Stage 1 is not a claim of universal device/accessibility completion.

## Branding asset assessment for Stage 2
The supplied square contains the V mark, a baked dark rounded tile/backdrop, glow and a small generator/star artifact at the bottom right. It is not a transparent adaptive foreground or a ready monochrome asset. Do not use the whole square unchecked as an adaptive foreground, trace a different V, carry the star into branding or stretch the small collage splash panel.

Preferred inputs before Stage 2: a clean transparent PNG or SVG of the SAME V mark (without the outer tile/star), and the separate full-resolution cinematic splash artwork/background. A flat monochrome silhouette export is useful for themed icons. If only the current raster is available, discuss controlled asset preparation that preserves the supplied mark before integration; no new logo generation is authorized. Adaptive mask/safe-zone previews and native SplashScreen API setup belong to Stage 2. The current launcher resources are still the generic pre-existing Android icon. No artwork, manifest, native splash resource or SplashViewModel has been changed in Stage 1.

## Preservation and verification
Protected-source hashes were captured before editing for all Android main sources/resources outside ui/theme and all server/src files. They are compared after the change; no business logic, ad/subscription path, navigation, upload engine, auth, Room/schema, processing, API or security rule is intentionally modified. The app Gradle script/dependencies are unchanged.

Build and actual test/native screenshot results are appended after execution. Compile previews do not prove all device sizes; synthetic gallery screenshots are not complete twenty-screen acceptance. No backend/PostgreSQL/R2/FFmpeg integration or private user mutation is performed for this UI-only stage. Stage 2 remains unstarted until approval.

Build verification: debug and instrumentation APKs built successfully; 115 unit tests passed with zero failures/errors/skips, including three accessibility contrast checks. The existing 112 regression tests remain passing. Initial compile checks caught an incorrect preview icon name and a native test bounds-helper mismatch, both corrected. The initial native run passed two tests; its large-text assertions completed, but the final PixelCopy screenshot timed out. The test screenshot helper was switched to native UiAutomation capture, and only :app:assembleDebugAndroidTest was rebuilt (42s). Production UI source and business code were not changed for this capture correction. Final native results/screenshots follow after execution.

Native capture follow-up: the UiAutomation run passed all three semantic/UI cases (219.09s), but visual inspection found its four captures were blank; those are not accepted as visual evidence. Initial valid Compose captures were reviewed separately. The helper now uses Compose capture with a maximum of three bounded retries and a sampled-pixel check rejecting blank frames. Only instrumentation was rebuilt (4m32s). The final rerun targets the two gallery capture cases; the unchanged callback/editing case already passed. No production source changed in either capture correction.

## Stage 1 final results
- Debug APK and instrumentation APK built successfully. Final main-source run: 55s, 115 unit tests / zero failures/errors/skips. Java17 command in the established outputs/phase5-verification project:
```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```
- All three distinct native UI cases passed across the recorded runs: shared/legacy callbacks, editable value and busy/disabled state; dark gallery; 320dp/200% text gallery with bounds and reachable validation. The final two capture-dependent cases were selectively rerun with bounded Compose capture and blank-pixel rejection: OK (2 tests), 9.613s. The unchanged callback case passed in the earlier three-case assertion run. Test-only capture rebuilds did not change the main APK/UI source or rerun unnecessary business suites.
- The final four valid screenshots were visually inspected at normal size and large-text fields. They are saved under docs/assets/cinematic-stage1/. The failed/blank earlier capture attempts are retained as diagnostic outputs and are not visual proof. The initial PixelCopy timeout and blank native captures were corrected in the test harness, not hidden as passing visuals.
- 156 Android source/test files match the verification copy in both directions; app Gradle script matches. All 175 protected pre-existing Android main files outside ui/theme plus server/src files are byte-identical to the pre-change hashes. No backend, auth, upload, Room/migration, navigation, playback, ad/subscription or private-resource logic changed. No dependency or launcher/splash asset change.
- No physical-device install, real account/API mutation, live R2/PostgreSQL/FFmpeg suite, ad-network smoke or full twenty-screen acceptance was executed for Stage 1. No release APK or store submission was built/published. The emulator was separately named VidooraCinematicVerify with explicit private data/cache paths, port 5582 and no SD card; the existing AVD/phone were not installed/cleared or display settings changed.

### Modified/created Android files
All paths below are relative to app/src/:
- main/java/com/example/vidoora/ui/theme/Color.kt — brand/spacing tokens.
- main/java/com/example/vidoora/ui/theme/Theme.kt — default dark and explicit light schemes, shared shapes and bar icon appearance.
- main/java/com/example/vidoora/ui/theme/Type.kt — scalable typography.
- main/java/com/example/vidoora/ui/theme/ReferenceComponents.kt — existing APIs delegate to the shared controls.
- main/java/com/example/vidoora/ui/theme/DesignComponents.kt — controlled shared presentation components.
- main/java/com/example/vidoora/ui/theme/DesignSystemPreviews.kt — preview-only gallery and size declarations.
- test/java/com/example/vidoora/BrandAccessibilityTest.kt — three contrast invariants.
- androidTest/java/com/example/vidoora/CinematicDesignSystemTest.kt — three synthetic interaction/layout cases and bounded validated captures.

Documentation updated: AGENTS.md, README.md, docs/VIDOORA_MASTER_PLAN.md and this report. Verification screenshots are documentation assets, never embedded screen images in app resources.

### Reviewable screenshots
These show the COMPONENT FOUNDATION, not a completed Home/Upload/Profile layout migration.
- [Dark component board](assets/cinematic-stage1/dark-top.png)
- [Dark states](assets/cinematic-stage1/dark-states.png)
- [320dp with 200% text](assets/cinematic-stage1/narrow-large-text.png)
- [Scrolled large-text fields](assets/cinematic-stage1/narrow-large-fields.png)

### Evidence and next approval
Chat outputs: cinematic-stage1-build.txt, cinematic-stage1-instrumentation-rebuild-final.txt, cinematic-stage1-native-assertions-blank-captures.txt, cinematic-stage1-native-render-final.txt, cinematic-stage1-preservation.json, cinematic-stage1-source-parity.json. Built debug artifact: Vidoora-cinematic-stage1-debug.apk. Earlier failed/capture logs are retained separately.

Stage 1 is complete. Stage 2 has NOT started. Per the user's brief, request approval before branding/native splash work. The separate high-resolution cinematic splash was not attached, and a clean transparent export of the same mark is preferred for adaptive/monochrome layers; see the asset assessment above. Screen-specific layout migration and full device/keyboard acceptance continue only in their approved later stages.
