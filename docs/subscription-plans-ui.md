> Download follow-up: see [private-offline-downloads.md](private-offline-downloads.md). The runtime downloader and Profile Downloads are now implemented behind verified paid access. Earlier statements that all download code is absent are superseded; checkout and real store/device verification remain unfinished.

> Current follow-up (30 October 2026): see [purchase-activation-recovery.md](purchase-activation-recovery.md). Authenticated durable receipt recovery and payment records are now wired, OFF by default; billingTest has gated Restore. Historical descriptions of those pieces as absent are superseded. Checkout, private downloads and real Play verification remain unavailable.

# Subscription plans UI preview — 30 October 2026

The user confirmed that the previous build showed 360p/480p and approved continuing with visible subscription UI. That is manual confirmation of the Free viewing stage, not evidence of a paid purchase or offline download.

## Visible changes

- Home has a compact Subscription plans card. Pasting/opening links still works automatically and watching remains available without login.
- Profile has the same card: below account information for signed-in users and below the login/create-account panel for guests. Existing My Videos, queue, blocked accounts, logout and privacy controls remain.
- Available portrait playback has the same shortcut below the banner area for Free viewers. It is hidden in fullscreen, during video ads, while playback access is unresolved, on errors, and for verified ad-free viewing. Opening/returning uses existing navigation and playback checkpoint/access behavior, not a new persisted link history.
- All entry points navigate to the single membership_plans route; the existing three main tabs remain unchanged. Back returns to the origin. Previewing plans does not require login or initialize ads.

The screen uses the current dark design system, icon-only Back/Refresh controls, 12dp card/button corners, bounded readable width, wrapping labels and a scrollable list. There are no fake popularity labels, active payment indicators, transactions or new logos.

## Exact preview catalogue

| Plan | Price | Validity/payment | Planned benefits |
| --- | --- | --- | --- |
| Day Pass | INR13 | 24 hours, one-time | Ad-free, available higher qualities, no downloads |
| Week Pass | INR39 | 7 days, one-time | Ad-free, available higher qualities, 5 downloads per pass |
| Monthly Ad-Free | INR99 | Monthly billing period, auto-renewing | Ad-free, available higher qualities, 30 downloads per billing period |

Free access is shown from the current member status (ads, up to 480p). If current status is loading, the screen shows Checking access rather than claiming a plan. A verified paid status can show its actual server expiry converted into the device's time zone; no fabricated countdown or renewal state is introduced.

The catalogue above is explicitly a preview of the agreed product specification. It does NOT query Play offers or claim those products are active. When checkout is later wired, visible purchase prices must come from Google Play, with the existing approved-price filter. The present screen has no BillingClient, purchase callback, token submission, transaction creation, local paid switch or entitlement write.

## Availability and safety

The screen prominently says Plans preview / Payments and downloads aren't available yet. Every Coming soon button is unconditionally disabled, including in billingTest. Existing purchasesAvailable=false and billing flags are unchanged. No fake Restore or Manage payment action is shown while it cannot complete its advertised task. Refresh reads the existing membership status API only; duplicate refresh taps are guarded.

No backend files, plan benefits, account identity, signatures, databases, storage objects, ads inventory or Play Console configuration were changed in this increment. Runtime paid access still requires the previous real verifier; this UI never grants it. Upload limits, private links and the Free quality restriction remain.

## Files

- app/src/main/java/com/example/vidoora/feature/membership/MembershipPlansScreen.kt: shared entry card, current access, plan preview cards and design previews.
- feature/home/HomeScreen.kt, feature/profile/ProfileScreen.kt, feature/playback/VideoPlayerScreen.kt: optional entry callbacks without changing their existing actions.
- ui/navigation/NavGraph.kt: one plans destination, Back and bounded Refresh handling.
- app/build.gradle.kts: Compose UI-test dependency for host-side semantic/layout checks.
- app/src/test/java/com/example/vidoora/feature/membership/MembershipPlansUiTest.kt: synthetic compact/large-font/navigation-callback/disabled-purchase fixtures. No real users, network requests, Play billing or ads SDK are used by these fixtures.

## Verification scope

Host-side Robolectric native-graphics/Compose checks use a synthetic 360dp by 740dp viewport and normal/150% text. View.draw snapshots inspect rendered content; they are not emulator or physical-device screenshots and do not prove full navigation, video playback or real billing. Native PixelCopy capture timed out in the initial harness, so capture uses a synchronous view canvas. A title-width assertion also needed a one-physical-pixel tolerance for intrinsic text rounding; actual glyph bounds, line ellipsis and height are still checked. Card titles were given their own full-width line so large text need not compete with the price column.

Final executed results and screenshot inspection are recorded below after verification completes. Earlier Free/paid policy tests are not presented as new payment test results.

## Remaining implementation

Authenticated receipt submission and purchase activation, pending/failed/success payment history, restore/recovery/reconciliation, the promised private download system, and actual Play product/license testing remain gates before enabling checkout. The proposed four-tab navigation/history, support centre, share-app/rating and transaction UI are still planned. No payment or store release is requested from the user by this preview stage.

## Manual acceptance

Use Android Studio Run for the existing debug variant; do not uninstall the current application. On Home tap Subscription plans, inspect all three cards, scroll to Monthly, and return using Back. Check the same entry on Profile and below an available portrait video. Purchases should remain Coming soon. These app-only changes do not require new credentials or backend configuration.


## Executed final results

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileBillingTestKotlin :app:compileDebugAndroidTestKotlin --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

Executed with Java17 in the source-identical verification copy:

Android: 142 passed, 0 failures/errors/skips
Source/build parity: 250 files, 0 mismatches
BUILD SUCCESSFUL in 2m 48s

This includes all four new Compose/Robolectric UI cases plus the existing 138 Android tests. Normal and 150%-font layout, disabled purchases, loading truthfulness, Home/Profile entry callbacks and Back/Refresh callbacks were checked. Four host-rendered PNGs (plans-top, plans-large-font, home-plans-entry, profile-plans-entry) were inspected visually; they show readable dark layouts with wrapping benefits. These are synthetic Robolectric native-canvas images, not physical-device/emulator captures or live navigation/payment evidence.

The first UI run failed on PixelCopy force-redraw timeouts and a title width assertion. The capture harness was changed to synchronous View.draw; the card title has its own line. The second run exposed fractional intrinsic text-width rounding (171px measured width with a fractional-width overflow flag); the final check allows at most one physical pixel, checks actual line bounds/height and rejects ellipsis. The final complete suite above passes; the earlier failures were not counted as passes.

Debug APK assembled; billingTest and native-test Kotlin compiled. No app was installed, no native instrumentation was executed, and no real Google/API/payment/R2/PostgreSQL test was run. No backend source changed, so the prior backend suite was not repeated for this app-only UI increment.

The optional development APK is outputs/Vidoora-subscription-preview-debug.apk; normal Android Studio Run is the recommended way to test the existing development installation. Payment and download actions remain unavailable.

APK validation: apksigner verification succeeded; certificate SHA-256 ccc58de6a6c480204cb8b93e6574dc012e15d07e2d5f799f6f75b137e81a1cae matches the earlier archived development builds. No uninstall or installation was performed.
