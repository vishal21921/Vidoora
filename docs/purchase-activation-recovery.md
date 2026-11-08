> Download follow-up: see [private-offline-downloads.md](private-offline-downloads.md). The runtime downloader and Profile Downloads are now implemented behind verified paid access. Earlier statements that all download code is absent are superseded; checkout and real store/device verification remain unfinished.

# Purchase activation and recovery — 30 October 2026

## Implemented scope
Authenticated receipt submission now persists encrypted receipts before contacting Google. The server owns the verification/acknowledgement and activation workflow; Android never supplies a paid flag, plan, price or expiry. V12 adds account-bound recovery attempts, fenced five-minute worker leases, observed-order records and linked-token ownership tombstones. Existing V1–V11 migration files are preserved.

Android saves delivered Play receipts in the existing encrypted preferences before scheduling network work. Logout preserves the encrypted outbox but workers only drain the currently authenticated account. HTTP acceptance transfers retry responsibility to the durable server queue. WorkManager inputs contain no receipt/account secrets. Startup/login resumes draining; connected-network work uses backoff, with a fifteen-minute safety sweep registered after receipt delivery.

Google-verified acknowledged receipts invalidate the read-side membership cache and resolve actual benefits before reporting activation verified. Pending payments never grant benefits or receive acknowledgement. Provider outages retry without marking a previously verified payment failed. Known successful receipts reconcile every fifteen minutes; inactive receipts are checked daily for up to ninety unsuccessful attempts (manual Restore can restart verification). Payment success and current activation are separate fields. Expired access remains enforced by the existing short-lived Google-derived membership resolver, not the history UI.

Stable opaque UUID references identify initial recovery attempts. A new *observed* Google order creates a separate reference; duplicate observed order IDs are hashed and deduplicated across receipts. Raw tokens/order identifiers are absent from API records and UI. Profile → Payments supports refresh, account-scoped pagination, retry, Copy ID and Get help. Get help pre-fills the existing user-reviewed support email draft; nothing is sent automatically.

This is observed receipt/activation history, **not a complete payment-provider financial ledger**. It does not invent charged amounts, recover unobserved historical orders, issue refunds or claim RTDN/voided-purchase reconciliation. An original attempt reference remains usable for support even if a duplicate receipt is merged into an existing observed-order row.

## Safe defaults and configuration
`PLAY_PURCHASE_RECOVERY_ENABLED=false` by default. Enabling it also requires `PLAY_MEMBERSHIP_READ_ENABLED=true` and the trusted product/base-plan mappings, private Google service-account credentials file and stable base64 32-byte AES receipt key described in play-billing-receipts.md and verified-viewer-benefits.md. Keep credentials/key outside source control and never paste them into chat. Preserve the key across restarts; changing it makes existing encrypted receipts unreadable.

Default builds report recovery unavailable rather than fabricate records. The isolated billingTest variant can Restore purchases only with package com.vidoora.app, server recovery enabled and valid configured products. Normal debug/release do not initialize Play Billing for this screen. Every checkout button remains unconditionally disabled, including billingTest; purchasesAvailable remains false.

New authenticated no-store endpoints:
- GET /api/v1/payments/config
- GET /api/v1/payments?cursor=<account-scoped UUID>
- POST /api/v1/payments/receipts with only a token (8 KiB request bound)
- POST /api/v1/payments/<reference>/retry

Users must exist and be unsuspended. Submitted tokens do not claim ownership until Google binding verification. Receipt ownership and linked/replaced-token tombstones prevent a different account claiming the same purchase chain. Admission limits and retry backoff bound abuse. Provider calls occur outside DB transactions; stale worker leases cannot overwrite a later result. The application owns and cancels the recovery polling coroutine on shutdown. Payment HTTP logging excludes request/response bodies.

## Remaining release gates
Private downloads (Week 5 / Monthly 30), durable quota reservations, encrypted offline media/licences and expiry checks are not implemented. History remains its explicitly labelled placeholder. Real Play product setup, internal-track licence testing, lifecycle/refund/RTDN handling, always-on backend delivery and production credentials remain required before accepting real money. No actual Google Play purchase, live R2 test or physical-device verification is implied by synthetic tests.

## Verification
Executed results are recorded below. PostgreSQL tests use a new loopback-only workspace fixture and generated disposable schemas, never the application's vidoora_db. H2/JVM/Robolectric results must be distinguished from real Google Play/device testing.

### Executed evidence

Source-identical workspace copy: 267 app/server source and Gradle files matched the real project. Final automated suites: **154 Android JVM/Robolectric tests passed**; **113 backend tests passed, 10 opt-in tests skipped**. Separately, **2 real PostgreSQL fixture integration tests passed** (zero skips/failures): PurchaseRecoveryPostgresIntegrationTest and PlayPurchasePostgresIntegrationTest. No real Google Play purchase, R2 test, emulator or physical-device test was performed.

The PostgreSQL fixture used installed PostgreSQL 16.15 with a fresh workspace data directory, loopback 127.0.0.1:55439 and generated schemas. Migration testing seeded V11, migrated V12 and verified ownership backfill. Tests dropped only their generated schemas. The fixture listener was confirmed absent after verification; the application's database/service was not started or modified.

Executed Gradle tasks in sequence with Java 17, in-process Kotlin, no daemon/configuration-cache/file watching and one worker:

```powershell
# Full final suites and build checks
.\gradlew.bat :server:test :app:testDebugUnitTest :app:assembleDebug :app:compileBillingTestKotlin :app:compileDebugAndroidTestKotlin --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
# Separate opt-in PostgreSQL fixture run, before the final suites
.\gradlew.bat :server:test --tests com.vidoora.backend.PurchaseRecoveryPostgresIntegrationTest --tests com.vidoora.backend.PlayPurchasePostgresIntegrationTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

The fixture-only run supplied VIDOORA_POSTGRES_TEST=true, DATABASE_URL=jdbc:postgresql://127.0.0.1:55439/postgres, DB_USERNAME=recovery_fixture and an empty local trust-fixture password. Do not use those values for the application or production. Normal developer tests can use the same tasks without the workspace-only offline Robolectric init script when dependencies are available.

Initial verification caught and corrected a missing serialization import, an older support-screen test call, a mock HTTP client lacking the production JSON header and an encoding-sensitive UI assertion. No failed run is counted as a pass. Existing Exposed deprecation and local Android analytics-directory warnings remain.

The debug APK's signature was verified against the existing Android debug certificate SHA-256 ccc58de6a6c480204cb8b93e6574dc012e15d07e2d5f799f6f75b137e81a1cae. No app was installed, uninstalled, cleared or uploaded to Play.

### Main affected files

- Server membership: PurchaseRecovery.kt, PostgresPurchaseRecovery.kt, PaymentRoutes.kt, MembershipRuntime.kt, PlayPurchaseVerifier.kt, PlayPurchaseRepository.kt, PlayPurchaseService.kt.
- Server wiring/configuration: Application.kt, plugins/Routing.kt, infrastructure/DatabaseFactory.kt, application.conf, .env.example; additive V12__purchase_recovery.sql.
- Android: PaymentRecoveryClient.kt, PaymentRecoveryUi.kt, PlayBillingGateway.kt, SecureStorageManager.kt, KtorClientFactory.kt, NavGraph.kt, AccountScreens.kt.
- Focused tests: PurchaseRecoveryTest, PaymentRoutesTest, PurchaseRecoveryPostgresIntegrationTest, PlayPurchasePostgresIntegrationTest, PlayPurchaseVerificationTest, ReceiptOutboxTest, PaymentRecordsUiTest and updated AccountStructureUiTest.

### Checking this build manually

Restart the saved backend launcher, keep its terminal open, then run the normal debug app from Android Studio. Profile -> Payments should explicitly say recovery is not configured until trusted server/Play setup is completed. It must not display invented purchases or unlock premium access. Support can open a draft without sending it. No new credentials or payment setup are required for this local UI check. Private downloads are the next implementation scope; accepting real payments stays blocked until their delivery and store tests are complete.

Final Gradle invocation: BUILD SUCCESSFUL (4m 7s). assembleDebug, compileBillingTestKotlin and compileDebugAndroidTestKotlin completed. Instrumentation tests were compiled only, not executed. Verification logs and PostgreSQL XML evidence are retained in the workspace outputs directory.
