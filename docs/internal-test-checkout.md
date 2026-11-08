# Internal-test checkout — 1 November 2026

The user approved implementing checkout after confirming that the existing Play-installed version still showed Coming soon. This is a new app version, not a backend-only change. No public release or actual charged purchase is authorised.

## Verified external configuration

The earlier read-only Google API check authenticated the saved Vidoora service account and read product vidoora_viewer_access. Active base plans: day-pass/P1D/INR13, week-pass/P1W/INR39 and monthly-ad-free/P1M/INR99. Each also enabled 173 other countries; the app accepts only the fixed INR offers. The user reports two licence testers. A screenshot confirmed the version1 internal track is active. These are not executed purchase tests.

## Implementation

- PLAY_CHECKOUT_ENABLED is false by default and true only in playInternal. Ordinary debug, billingTest and release checkout stay disabled.
- Backend PLAY_INTERNAL_TEST_CHECKOUT_ENABLED defaults OFF. It requires verified reads, recovery and test receipts enabled. With this flag, non-test Google receipts are rejected before acknowledgement or access. This rejects actual charged receipts but cannot prevent a user authorising a real charge in Google's sheet: testers must select a licence-test instrument.
- The plans screen connects the existing Billing9.1 gateway only after authenticated server configuration. Buttons display the approved live Google price and use its base-plan offer token. Missing, changed-price, non-INR, trial and discount offers remain unavailable.
- Account UUID binding, encrypted durable receipt outbox, server verification/acknowledgement and server-derived entitlement remain the access path. A store callback never grants premium.
- An existing active/pending Play purchase blocks new checkout. Plan replacement, top-ups and cross-plan upgrades are intentionally unavailable. Reopening/restoring recovers interrupted callbacks. Screen disposal closes the BillingClient.
- Login, backend/config errors, membership loading/active status, foreground state and SDK readiness gate checkout. Confirmation explains licence-test instruments. Restore and Payments remain accessible for recovery.

## Local launcher

The workspace outputs Start-VidooraBillingBackend.ps1 now sets PLAY_INTERNAL_TEST_CHECKOUT_ENABLED=true in its temporary process environment and reuses the existing DPAPI receipt key and private Google file. Stop/restart that launcher after code update. Do not regenerate the receipt key or include the JSON/DPAPI files in source or app artifacts. The API still runs on the laptop over USB loopback; no online deployment was performed.

## Distribution and test procedure

Build a signed playInternal AAB with versionCode2. The previous Play version1 cannot expose checkout. Upload to the existing internal track; do not start a public/closed rollout. Install the update from Play while signed into the licence-test account, connect USB, keep the configured backend running and sign into Vidoora. The expected plan actions open Google's sheet only after the test warning. Cancel if no test instrument appears. User must authorise purchases manually.

Then verify pending/declined/approved/cancelled, durable restore, account mismatch, verified benefit delivery, expiry and private download quotas. Do not call these tests passed before execution. Refund/RTDN lifecycle work and always-on deployment remain outstanding before public purchases.

## Validation

Pending actual compiler/test/build results. No device purchase or new bundle upload is claimed by this document.

Reference: https://developer.android.com/google/play/billing/integrate (checked 1 November 2026).

## Executed validation and artifact

- Full verification command: :server:test :app:testDebugUnitTest :app:compilePlayInternalKotlin with the class-isolated offline test init script, JDK17, in-process Kotlin, no daemon/configuration cache/file watching and one worker. BUILD SUCCESSFUL in 8m21s.
- Android: 167 tests passed, zero failures/errors/skips. UI tests cover default-disabled plans, store-price callback, missing offers and active/server-disabled gates. Existing encrypted receipt/outbox and media tests also ran. They do not execute a Play purchase overlay.
- Backend: 124 passed, 11 opt-in integrations skipped, zero failures/errors; plus 2 additional InternalCheckoutConfigTest cases passed in a separate successful run. Combined unique backend evidence: 126 passed, 11 skipped. This includes rejection of non-test receipts without acknowledgement and acceptance of test receipt parsing. Tests use synthetic provider fixtures, not actual paid purchases.
- Generated ordinary release BuildConfig has PLAY_BILLING_ENABLED=false and PLAY_CHECKOUT_ENABLED=false. playInternal has both true, package com.vidoora.app.
- Signed build: scripts/Build-VidooraPlayInternal.ps1 -VersionCode 2 completed successfully in 3m1s, including lintVital and signing validation. Package com.vidoora.app, versionCode2, target36; debuggable/testOnly absent (default false).
- Bundle copied to C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-internal-v2.aab. SHA256: 2EE02CBFCFCBF1E4645526A8C9A0FF42364882A084A80B2A6C8AF329361A8C32. jarsigner verified the signature; public upload certificate SHA256: 4C:DE:CD:39:44:80:BC:E5:EE:2C:97:05:95:21:23:78:47:48:ED:D3:C7:C6:AE:04:DE:98:A9:8C:D3:55:41:95. Self-signed/no-timestamp/ZIP streaming-order warnings remain as in version1. Private credential-filename scan found zero key/env/DPAPI files in the AAB.
- Source changes: app build gates, MembershipPlansScreen, new CheckoutMembershipPlansScreen, PlayBillingGateway, observable recovery config and NavGraph; server runtime/config/test-only receipt verification/recovery metadata; default-OFF env example; focused tests and documentation. No schema or user data reset, cloud hosting, Play upload, charged purchase, new device install or physical billing test was performed.
- Logs/XML are in workspace outputs/internal-checkout-verification.txt, checkout-gate-tests.txt, checkout-v2-signed-build.txt, checkout-android-results and checkout-server-results. Actual Google API purchase acknowledgement and benefit delivery remain to be tested after installing version2 and restarting the updated billing launcher.
