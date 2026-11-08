> Current follow-up (30 October 2026): see [purchase-activation-recovery.md](purchase-activation-recovery.md). Authenticated durable receipt recovery and payment records are now wired, OFF by default; billingTest has gated Restore. Historical descriptions of those pieces as absent are superseded. Checkout, private downloads and real Play verification remain unavailable.

# Play Billing integration — receipt and client stage

Implemented 30 October 2026 in the existing project. This is a staged integration, not a working subscription checkout or production monetization release.

## What exists

- `billingTest` keeps the approved `com.vidoora.app` identity, separate from the existing `com.example.vidoora` debug installation. `PLAY_BILLING_ENABLED` is false in ordinary debug/release and true only in the isolated billing-test variant. Google Play Billing 9.1.0 is included; no navigation destination currently constructs the gateway, so no purchase can be initiated from the app UI yet.
- `PlayBillingGateway` supports connect, exact base-plan offer selection, store-provided prices, checkout, restore, pending-payment messaging, duplicate in-flight receipt suppression, account binding and closing the client/session. It sends completed purchases to a supplied server callback; it never grants premium, consumes a purchase, or acknowledges on the device. It is not yet connected to an HTTP endpoint or plans screen. The future host must own one gateway, call restore on foreground, and close/recreate it when the account/session changes.
- The initial offer filter accepts only the approved INR prices and durations: Day INR13/P1D prepaid, Week INR39/P1W or P7D prepaid, Monthly INR99/P1M infinitely recurring. Unconfigured products, discounted/trial multi-phase offers, different prices/currencies or wrong recurrence are hidden. This deliberately does not invent international prices.
- `GooglePlayPublisher` is a real, server-only Google Android Publisher subscriptionsv2 GET and subscription acknowledgement POST adapter, fixed to com.vidoora.app. It loads a trusted service-account file, checks its credential type and Google OAuth endpoint, and uses the Google Auth library 1.54.0 for scoped tokens. Provider failures are sanitized; no receipt bodies, credentials, tokens or provider URLs enter exceptions. Responses are limited to 256KiB and the whole HTTP response has a 25-second bound. Redirects are disabled.
- `PlayPurchaseVerifier` checks the server-configured product/base-plan mapping, hashed canonical Vidoora account UUID, payment state, acknowledgement state, store expiry/start time and paid order identifier. Day/Week must be prepaid; Monthly must be auto-renewing-plan type (renewal may be switched off while paid access remains valid). Pending, paused, on-hold, expired, unknown and foreign-account receipts fail closed. Cancellation retains eligibility only until the store expiry; grace-period access follows Google state. License-test receipts require explicit server opt-in. Monthly expiry comes from Google, not a 30-day calculation.
- `PlayPurchaseService` orders verification -> durable account-bound receipt journal -> acknowledgement -> confirmed acknowledgement -> durable confirmation. It returns `VERIFIED_RUNTIME_NOT_ENABLED`, not an active-subscription claim. A retry uses the same token hash/support UUID. The receipt journal cannot itself authorize media or paid benefits.
- `PostgresPlayPurchaseLedger` uses short database transactions, a unique token hash, immutable account binding, encrypted token storage, a stable support UUID and explicit record states. The AES-256-GCM key must be separate from JWT credentials; random nonces and token-hash authenticated data reject ciphertext/row swapping. No Google network operation holds a database transaction open.
- V11 is an additive receipt-journal migration; V1-V10 are unchanged. H2 schema creation includes the equivalent table. No actual PostgreSQL migration has been executed by this task.

## Deliberately not activated yet

No purchase route is registered in Application/Routing, no credentials are loaded by startup, no products/base plans were created in Play Console, and no paywall is visible. Persistent pending/failed payment-attempt history, verified entitlement API, renewal/refund/revocation reconciliation/RTDN, purchase-status refresh, ad suppression, server HLS quality enforcement, download quotas/offline media, the four-tab navigation and membership UI remain unfinished. Existing ads/playback/uploads retain their previous runtime behavior. Receipt verification must not be connected to checkout until entitlement delivery is complete.

This stage uses configurable product/base-plan mappings. It supports the proposed prepaid-subscription form of Day/Week, but does not claim these products exist or that Play Console commercial configuration has been verified. Exact product IDs, supported base-plan durations and INR prices must be confirmed before enabling checkout. No automatic upgrades, plan stacking, top-ups or cross-plan replacements are offered yet.

## Secure setup later

Nothing needs to be pasted into chat. Before enabling a real endpoint, create a dedicated least-privilege Google service account with Android Publisher access limited to Vidoora; keep the JSON file outside the repository and APK. Its path and a separately generated 32-byte receipt-encryption key must be supplied through private server configuration. The loader accepts a trusted local file only. Keep the encryption key stable/backed up privately; changing it without migration makes stored receipts unreadable. Do not enable HTTP request/response body logging for receipt submission.

Do not set invented environment variable names expecting the adapter to run: runtime configuration/bootstrap is not connected at this stage. `.env.example` remains documentation only. Public app/product identifiers are not credentials, but service-account JSON, OAuth bearer tokens, purchase tokens and the encryption key are secrets.

## Next implementation gate

Wire authenticated, bounded purchase/status APIs and fresh verified entitlement resolution with reconciliation. Connect all three ad surfaces and backend master/rendition authorization to current entitlements. Then attach the plans/restore UI and run isolated license-test purchases before enabling real payments. Keep complete active link/owner authorization independent of subscriptions. Private downloads remain a separate implementation gate after paid entitlement delivery.

## Primary references

- https://developer.android.com/google/play/billing/integrate
- https://developer.android.com/google/play/billing/release-notes
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/acknowledge
- https://docs.cloud.google.com/java/docs/reference/google-auth-library/latest/com.google.auth.oauth2.ServiceAccountCredentials

Executed verification is recorded below after the final build; do not interpret the code inventory above as live-test evidence.

### Store configuration checks verified against documentation
Google's current Play Console documentation lists 1-day and 1-week prepaid base-plan durations, so the approved Day/Week terms can use prepaid subscriptions without changing their lengths. Configure extension/top-up as disabled initially; the current service does not implement plan stacking or replacements. Monthly uses a one-month auto-renewing base plan. Actual products/base plans in this account still have not been created or tested.

Short prepaid plans must be acknowledged within half their duration (a one-day pass therefore within 12 hours); plans of a week or longer have a three-day acknowledgement window. Before checkout is exposed, add durable acknowledgement retry/foreground restore plus server reconciliation so temporary failures do not depend on the user manually reopening the app. The staged client alone does not satisfy this lifecycle requirement.

Sources: https://support.google.com/googleplay/android-developer/answer/140504?hl=en ; https://developer.android.com/google/play/billing/lifecycle/subscriptions .


## Executed final verification — 30 October 2026

Executed in the source-identical workspace verification copy, with Java 17, one Gradle worker, in-process Kotlin compilation and the existing offline Robolectric init script:

```powershell
.\gradlew.bat :server:test :app:compileBillingTestKotlin :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

All live PostgreSQL/R2/processing/FFmpeg opt-in flags were explicitly false for the final run.

server: 96 passed, 9 skipped, 0 failures, 0 errors
app: 130 passed, 0 skipped, 0 failures, 0 errors
Source/build parity: 238 files, 0 mismatches
BUILD SUCCESSFUL in 7m 14s

The backend passing total includes 14 new synthetic receipt/acknowledgement/encryption/body-bound tests and nine existing membership policy tests. The Android total includes three new account-binding/approved-price tests. The new PostgreSQL ledger integration test was skipped, not passed; it requires explicit opt-in and uses a fresh generated schema, validates concurrent claims/account binding/encrypted persistence, then drops only that isolated schema. No real Google Play API/OAuth, purchase, PostgreSQL migration/ledger SQL, R2 operation, device billing flow or native UI test was executed. The build compiled the billing-test variant; no new APK/AAB was assembled, installed, uploaded or published by this stage.

The first build caught a nullable Google access-token compile error and failed; that was fixed. A subsequent focused run passed 23 backend tests and two Android tests, with one PostgreSQL test skipped. The final full run above includes the later price filter and cancellation fix. Existing Exposed/Android annotation deprecation warnings and the unwritable Android analytics preference warning did not fail the build.

## Later read-side integration
The receipt/client stage above is historical. Read-only verified membership resolution, backend Free480 enforcement and Android ad/quality integration are now implemented in docs/verified-viewer-benefits.md. Checkout, paid activation/recovery/reconciliation, native paid acceptance and private downloads are still not ready. No purchase should be initiated yet.
