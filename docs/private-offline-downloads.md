# Private offline downloads - 30 October 2026

## Scope and safe defaults
Private in-app downloads are now implemented behind actual verified Week/Monthly access. Free and Day do not receive download access. Plans retain INR13/24-hour Day, INR39/7-day Week with 5 distinct videos, and INR99/month auto-renewing with 30 distinct videos per verified billing period. Checkout is still unconditionally disabled; no production/local premium override or invented transaction has been added. Default unconfigured membership remains Free.

Ready video details now include Download. Guests are directed to sign in; Free/Day open the existing plan preview. An eligible verified account starts the real downloader and sees its queue in Profile -> Downloads. Creator uploads, owner playback, link watching and sharing remain unchanged. Profile Downloads is an actual account-scoped list, replacing the former placeholder. It supports foreground progress notifications, pause/resume/retry, confirmed local removal and offline Watch. Removing this copy never deletes the uploaded video and never refunds a previously consumed allowance.

Offline quality selects the highest processed rendition at or below 720p. It does not upscale unavailable source qualities. There is no Gallery/export URI. A normal foreground in-app Media3 HLS player provides native controls, portrait playback, landscape fullscreen and double-tap ten-second seeking. Offline playback does not initialize ads: it requires a still-valid paid offline lease. Online player behavior remains unchanged. Offline-specific resume checkpoints, speed/quality menus, device download management across accounts and storage-retention cleanup are not claimed by this increment.

## Quota and verified transfer protocol
V13 is additive; V1-V12 are unchanged. private_downloads records one allowance slot for each account / hashed verified Google order period / distinct video. User-row locking makes reservation decisions atomic across service instances and devices. Charged slots remain counted for that period even after local deletion or cancellation. New periods have independent allowances; no rollover is invented.

private_download_clients holds each device transfer separately, using an opaque client UUID supplied in X-Vidoora-Download. Cancelling one device does not cancel another device's active copy. Explicit resume rotates the client transfer identity and durably retains prior cancellation requests, so a delayed cancellation cannot cancel the new attempt. Late client chunks/lease responses and server delivery records are fenced against changed transfer/content identities. Unused reservations expire after thirty minutes without successful transfer activity. Cancel/failure release is retried through encrypted local records; if the device is offline, reservation expiry is the fallback. Pausing may temporarily hold a slot until cancellation is delivered or its reservation expires.

The server builds a bounded VOD inventory from private processed HLS, binds it to an opaque content-generation hash, and checks ownership/current share capability and block/deletion state. Each asset uses the authenticated backend; no R2 URL is exported. The server records per-asset SHA-256 after responding. Completion must present exactly the complete inventory and matching hashes already observed by the server; a client completion boolean cannot charge a quota slot or issue a lease. Response/DB timing can require a completion retry, which is idempotent. Same-period retries and re-downloads reuse the video's allowance slot.

Reserve and lease completion resolve fresh Google-verified acknowledged grants through the trusted runtime source, independently of client plan claims. Asset reads also require current download benefits, and existing playback authorization/quality rules remain. Fresh private authorization is checked after potentially slow provider calls. Paid status alone never grants video-ID-only access to another uploader's video.

## Encryption and offline leases
All metadata (including title, capability and lease) and HLS assets are AES-256-GCM encrypted with a non-exportable Android Keystore key. AEAD binds each file to account, local download UUID and asset path. Atomic encrypted metadata and encrypted files live in noBackupFilesDir/private_offline_v1; WorkManager Data contains only opaque account/job IDs. Files remain inaccessible to a different logged-in account or after logout. Authenticated responses arriving after an account switch cannot be persisted. This is private in-app encryption, not a DRM claim for compromised/rooted devices.

A lease lasts at most twenty-four hours, never beyond actual verified plan expiry or the incoming share capability's expiry. The client anchors its validity to elapsed time before the lease request, boot identity and wall-clock rollback checks; network delay cannot extend it. Reboot, unknown boot identity, elapsed-time rollback, wall-clock rollback or expiry require online validation. Every offline playback open attempts online validation; transport/provider failure can use only an existing valid lease. A definite access denial invalidates the local lease. Reconnection during foreground playback triggers validation again; expiry/account changes stop playback. Offline devices cannot receive immediate deletion/sharing/refund revocation; their existing bounded lease is the limit, not a promise of instantaneous revocation.

Per-asset plaintext exists only in bounded memory. Asset limit is 16 MiB; inventory is at most 10,001 files, metadata at most 4 MiB, each active offline payload at most 2 GiB. The downloader checks for a 64 MiB free-space safety reserve and caps visible account records at 60. Corrupt/missing chunks are fetched again on resume without double-counting stored bytes. Opening the same video with a replacement valid share link updates its encrypted capability instead of creating a duplicate record. Transfer cancellation and local removal fence late writes. Partially transferred files stay encrypted during network retries.

## API
All endpoints require a live authenticated unsuspended account, no-store responses and the X-Vidoora-Download opaque transfer UUID. X-Vidoora-Share is required for non-owner video access and is never placed in a URL or logs.

- POST /api/v1/downloads/video/{videoId}: reserve/reuse the account-period-video slot and return the bound inventory.
- GET /api/v1/downloads/{id}/asset?path=...: authorized, bounded private asset transfer.
- POST /api/v1/downloads/{id}/complete: verified full transfer / fresh lease validation with hashes.
- DELETE /api/v1/downloads/{id}: cancel only this device transfer; preserve previously charged allowance.

HTTP logging excludes the download endpoints. No new credentials are required for the default local build. Operational verified access uses the existing private Google/membership configuration; do not paste Google keys, receipt keys or purchase tokens into chat. No real user database, media or Play products were changed by this task.

## Verification and remaining gates
Focused storage and backend checks ran before the final suites. Final exact evidence is recorded below. PostgreSQL verification uses generated fixture data and an isolated temporary schema, never vidoora_db. JVM/H2/Robolectric and mocked HTTP checks do not prove native decoding, actual Google purchases or physical-device offline playback.

Before checkout can be exposed: configure real Play products and licence testers privately, complete verified test purchases/restore and actual-device download/airplane-mode/reboot/account-switch tests, and establish reliable HTTPS backend delivery. No real purchase or R2 integration test is claimed here. Existing History remains a separate explicit placeholder. Full refund/RTDN financial reconciliation remains unfinished as documented for purchase recovery.

Manual sequence once a verified test plan is available: restart the saved backend launcher, run from Android Studio, open an authorized video and Download; inspect progress and Pause/Resume; Watch offline in airplane mode within the lease; confirm logout/account switch blocks access, reboot requires validation, plan expiry denies access, link revocation denies the next online validation, and local removal preserves the uploaded original. Never manually edit a paid flag or real subscription database row to simulate a purchase.

## Executed verification

Final source-identical workspace checks:

- 165 Android JVM/Robolectric tests passed, zero failures/errors/skips. This includes encrypted metadata/file recreation, tamper and traversal rejection, account separation/logout, boot/clock/expiry denial, stale transfer fencing, replacement capabilities, byte-count-safe repair, mocked HTTP interrupted transfer/resume, generation-specific cancellation and responsive large-font/guest Downloads UI checks.
- 123 backend tests passed, zero failures/errors; 11 explicit opt-in tests skipped. New checks include Week5/Monthly30, concurrent quota admission, new billing periods, server-observed full-inventory completion, forged/incomplete proof denial, per-device cancellation, used-slot retention, stale reservation release, private access/benefit denial and share/plan expiry lease bounds.
- One separate PostgreSQL 16.15 fixture integration test passed with zero failures/skips. It migrated a generated disposable schema through V13 and ran concurrent reservations across service instances, durable slot reuse, completion/cancellation and charged-versus-unused quota checks. This was generated synthetic data and mocked storage/verified-grant fixtures, not a real Google purchase or R2 object transfer. The test dropped only its generated schema. Temporary loopback fixture listeners were confirmed closed after their helper processes ended; vidoora_db was untouched.
- Debug APK built and its signature verified against the existing debug SHA-256 ccc58de6a6c480204cb8b93e6574dc012e15d07e2d5f799f6f75b137e81a1cae. No install, uninstall, app-data reset, Play upload or publishing was performed.
- 280 app/server source and module Gradle files matched the real project and verification copy. Normal debug remains com.example.vidoora; billingTest retains the separate com.vidoora.app identity. Prices, release gates and signing configuration are unchanged.

Executed command in the workspace verification copy (Java 17, one Gradle worker and existing offline Robolectric init script):

```powershell
.\gradlew.bat :server:test :app:testDebugUnitTest :app:assembleDebug :app:compileBillingTestKotlin :app:compileDebugAndroidTestKotlin --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

Separate real PostgreSQL fixture command used VIDOORA_POSTGRES_TEST=true, a fresh loopback workspace cluster and dummy fixture-only credentials, then preserved its XML before the full suite:

```powershell
.\gradlew.bat :server:test --tests com.vidoora.backend.PrivateDownloadPostgresIntegrationTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

The init script is a workspace-only offline test dependency setting; ordinary repository developers can omit it when dependencies are available. Never substitute the application's real database for this disposable fixture procedure. Verification logs/XML and a debug APK remain in workspace outputs.

Initial verification found and corrected a constructor initialization issue, a Ktor test helper API mismatch and a cancellation timestamp-precision edge. Windows fixture startup/process waiting needed a harness correction; no failed or blocked run was counted as passed. Existing Exposed/Clipboard/Media3 deprecations and local Android analytics-directory warnings remain; a coroutines mapLatest opt-in warning is also present.

### Main affected source

- Server modules/downloads/PrivateDownloads.kt and DownloadRoutes.kt; additive V13__private_downloads.sql and equivalent H2 schema registration.
- PlaybackService.kt / VideoShareTokens.kt for opaque content binding and a verified capability deadline; MembershipRuntime.kt / Application.kt / Routing.kt for real verified-grant wiring and protected download routes.
- Android feature/downloads/OfflineStore.kt, DownloadRepository.kt, OfflinePlayer.kt and DownloadScreens.kt; NavGraph.kt, PublishingScreens.kt and AccountScreens.kt connect the real list/details actions; KtorClientFactory.kt excludes download logs.
- Backend PrivateDownloadsTest, DownloadRoutesTest and PrivateDownloadPostgresIntegrationTest; Android OfflineStoreTest, DownloadTransferTest and DownloadScreensTest.
- README, AGENTS, master plan, membership and account/payment UI documents record the updated implementation boundary. .env.example documents that verified membership is required without adding a mock paid switch.

Instrumentation tests were compiled only. Native Media3 decoding, actual-device airplane-mode/reboot flows, real Play purchases and live R2 downloads remain unexecuted. The next external integration step is Play product/licence-test configuration; purchase buttons deliberately remain Coming soon until that test path and benefit delivery are verified.

Final Gradle result: BUILD SUCCESSFUL in 5m 33s. Debug assembly, billingTest Kotlin compilation and Android instrumentation-test Kotlin compilation completed. The latter is compilation evidence only, not a device execution.
