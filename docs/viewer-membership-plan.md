# Approved viewer membership specification — 30 October 2026

## Fixed product structure

The user approved the original image's prices and benefits, rejected postponing downloads, and delegated practical resolution/download details. Do not change prices or remove the approved benefits without asking.

| Plan | Price | Validity | Renewal | Ads | Streaming | Successful downloads per paid period |
|---|---:|---|---|---|---|---:|
| Free | ₹0 | Unlimited free use | None | Yes | Up to 480p | 0 |
| Day Pass | ₹13 | 24 hours | No automatic renewal | No | All supported/source-available qualities | 0 |
| Week Pass | ₹39 | 7 days | No automatic renewal | No | All supported/source-available qualities | 5 |
| Monthly Ad-Free | ₹99 | Actual monthly store billing period | Auto-renewing | No | All supported/source-available qualities | 30 |

Ad-free covers Watch interstitials, portrait banners and ten-minute dedicated video-ad breaks. Uploading and creator management remain free of ads/plan charges. The viewer's plan applies even when watching their own uploaded video. Guests can watch with ads; purchases, restore and account-bound downloads require login. A subscription never grants access to another creator's private video without its complete active share capability. Existing source/rendition availability still limits quality; never advertise a 1080p option if only 720p exists.

## Download decisions under delegated discretion

- Private in-app offline playback, not Gallery/file export. Do not present this as DRM or protection against a rooted/compromised device.
- Week quota five, monthly quota 30 per verified billing period; no rollover. One successfully downloaded distinct video consumes one allowance. Retry/resume/re-download of that same authorized video in the same period reuses its slot. New periods have their own allowance.
- Reserve a slot durably/atomically across devices while transferring so parallel requests cannot exceed the quota; failed/cancelled transfers release reservations. Completion, cancellation and retry must be idempotent. A mere client completion claim cannot charge or release server quota without the agreed verified transfer protocol.
- Preserve the existing private sharing/deletion/blocking model. Fresh online authorization is required before downloading and before issuing/refreshing an offline playback lease. Completed files must be encrypted in app-private storage and segregated by account; logging out must make that account's offline files inaccessible.
- Offline access lasts at most 24 hours after validation and never beyond verified plan expiry. The final downloader must anchor an encrypted lease to monotonic time plus boot identity; reboot, unavailable boot identity or elapsed-clock rollback requires online revalidation. Devices offline cannot receive immediate sharing revocation: clearly disclose the bounded offline window. Denial on reconnection withdraws access.
- Free playback must eventually be limited in both the HLS master and every higher-rendition manifest/segment route; a player-only toggle is insufficient. Do not alter processing quality or original uploads to achieve a viewing restriction.

## Product interface scope already discussed

Home / History / Upload / Profile navigation. Upload becomes the creator dashboard, retaining upload, queue, My Videos/folders and blocked uploaders. Profile owns the account/support user ID, plan/expiry, purchase history, restore/manage subscription, support, app share and rating. Home/Profile/video viewing entry can all open the same plans page, and paid users should see actual plan/expiry instead of repeated upgrade promotions. History requires a separately bounded, private, account-separated implementation with current media authorization; current numeric resume checkpoints are not watch history.

Each purchase attempt needs a durable app support reference. Show payment state separately from entitlement activation state, because payment may be successful while activation is pending. Never expose purchase tokens as public support IDs. Support opens a user-reviewed email draft to a configured support address, with optional user-selected screenshots; no automatic sending. App sharing/rating must use a real published listing, not fabricate a working listing before publication.

## Current implementation versus plan

Implemented in this increment: new pure server domain model `modules/membership/ViewerPlans.kt` and `ViewerPlanPolicyTest.kt`. The model records prices, ad-free/quality/download benefits, account-bound verified-grant resolution, pending/refunded/revoked/expiry denial, quota reservation/reuse decisions and bounded monotonic offline-lease rules. Nine targeted tests passed on the initial version; the final result is appended below.

These classes are deliberately NOT wired into live playback. Existing viewing quality and ad behavior have not been changed. There is currently no Play Billing client, Google purchase-verification adapter, purchase/entitlement database, transaction UI, operational quota repository, encrypted media downloader/player, or new navigation/history UI. The policy tests do not prove those future components exist. Do not label a plan as paid from a local boolean, a plan button or a screenshot, and do not turn on a production purchase path with fake verifier results.

## External setup and next implementation sequence

The user has an existing Play Console developer account with two other apps, but no Vidoora app entry/artifact yet. A public production launch is not required to prepare billing.

1. Create Vidoora as a free-to-install draft app in the existing Play Console. No public release is needed. Before uploading the first billing-enabled test bundle, agree on the permanent application ID and signing arrangement; do not silently rename the current `com.example.vidoora` development install or discard its data.
2. Implement the Play Billing client, service-side verification/acknowledgement and entitlement/purchase persistence. Exact Play product/base-plan identifiers and server access must be configured privately. Day/week are non-auto-renewing passes; monthly renews. Select supported Play product configurations that retain those agreed commercial terms. UI purchase prices must come from Play, with configured INR prices matching the approved table.
3. Verify store receipt authenticity, account association, pending/paid/acknowledged state, expiry, cancellation, refund/revocation and idempotency. No client-generated paid grants or purchase tokens in logs/URLs. Provide restore/status retry before asking users to contact support.
4. Connect ad suppression and backend/player quality enforcement to the verified entitlement. Avoid shipping an upgrade paywall with no working purchase/restore path.
5. Implement the durable quota/download/lease system and private offline playback against that entitlement. Test all quota, expiry, account-switch and revocation behavior with isolated fixtures.
6. Implement the discussed navigation, history, profile, transactions/support surfaces and expiry presentation, then verify end to end on an internal testing track with license testers.

Google recommends secure backend verification and acknowledgement: https://developer.android.com/google/play/billing/security . App/draft setup: https://support.google.com/googleplay/android-developer/answer/9859152 . Billing tests and license testers: https://developer.android.com/google/play/billing/test . Test-track purchases may charge real money if the tester is not configured as a license tester; use the test purchase setup before testing payments.

No Google/R2 credentials, real payments, existing user media, production database or store listing were changed by this increment.

## Executed foundation verification

Command in the source-identical verification copy:

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :server:test --tests com.vidoora.backend.ViewerPlanPolicyTest --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Final result on 30 October 2026: BUILD SUCCESSFUL in 1m 19s; nine targeted tests passed, zero failures/errors/skips. The Kotlin compiler daemon connection failed and Gradle's fallback compilation succeeded; this was an infrastructure warning, not a passing test invented from a failed build. The earlier initial run also passed nine tests. This final run adds rejection of unknown/negative boot identity.

Only these new pure policy tests were executed for this isolated foundation addition. No live purchase, download, entitlement integration, Android device test, full backend-regression rerun, schema migration or production configuration is claimed. Existing application runtime paths are unchanged. The model/test files in the project match the verified copies.

## Play draft and isolated billing build — 30 October 2026
The user approved permanent Play identity com.vidoora.app and confirmed draft creation. The unpublished Vidoora draft exists (app ID 4972930527798401708). No artifact, product, release, or payment has been submitted.
A new billingTest build type uses com.vidoora.app with label Vidoora Billing Test. Existing debug remains com.example.vidoora. Both manifest processing tasks executed successfully in the verification copy (38s). This verifies variant/manifest configuration only; it is not an APK build or a live billing test. The variant inherits debug configuration, including demo ads, and is not a production release.
BillingClient integration, server purchase verification/persistence, verified ad/quality enforcement, download runtime, products, license testing and signing remain unfinished. No credentials are needed in chat. The build identity preparation does not activate paid plans.

## Billing adapter/client implementation status — 30 October 2026
The former policy-only status is superseded by the staged implementation documented in [play-billing-receipts.md](play-billing-receipts.md): Android Billing gateway, Google subscriptions API adapter/verifier and encrypted PostgreSQL receipt journal with V11 migration. These components are not yet connected to purchase routes or plans UI. No paid benefit has been activated, no live purchase has run, and downloads/quality/ad enforcement still need runtime integration. Current test evidence and limitations are recorded in that document.

## Current benefit integration
See [verified-viewer-benefits.md](verified-viewer-benefits.md) for the implemented read-side server status, Free480 enforcement and Android ad/quality gates. This supersedes earlier statements that these runtime connections do not exist. It does not make checkout, paid activation/recovery, offline downloads or the proposed membership UI complete.

## Visible plans preview
Home/Profile/Free portrait playback entry points and a single read-only plan screen are now implemented; see subscription-plans-ui.md. This supersedes earlier statements that no membership screen exists. Checkout, payment activation/history/recovery, real Play testing and private downloads remain unavailable. Static preview prices are the approved product specification, not fabricated live Play offers.


## Private offline downloads - approved 30 October 2026
The user approved continuing after purchase recovery. Private account-bound encrypted HLS downloads, durable atomic Week5/Monthly30 quota reservations, device-specific cancellation, server-observed transfer proofs and bounded offline leases are now implemented. Profile Downloads is connected; ready details expose Download. Source-available offline quality is capped at 720p, with no Gallery export or private-access bypass. Reboot/clock rollback/expiry require online validation; offline revocation is bounded by the lease, not immediate. See docs/private-offline-downloads.md for precise limits and executed evidence. This supersedes older descriptions of downloads as entirely absent. Checkout remains unconditionally disabled pending real Play/licence/device testing and reliable HTTPS backend delivery; do not infer production readiness or live-purchase success. Existing History and refund/RTDN financial reconciliation remain unfinished.
