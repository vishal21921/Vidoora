> Current follow-up (30 October 2026): see [purchase-activation-recovery.md](purchase-activation-recovery.md). Authenticated durable receipt recovery and payment records are now wired, OFF by default; billingTest has gated Restore. Historical descriptions of those pieces as absent are superseded. Checkout, private downloads and real Play verification remain unavailable.

# Verified viewer benefits — 30 October 2026

This stage connects the approved plan policy to server HLS reads and Android ad/quality gates. Prices remain unchanged. Checkout, receipt submission, actual paid activation/recovery, private downloads and membership UI are still unavailable.

## Runtime behavior now

`GET /api/v1/membership` accepts optional access JWT authentication. Guests get Free; authenticated responses are scoped to their own JWT subject and check the actual account, including suspension. Responses are no-store and never contain receipts, purchase tokens, order IDs or storage keys. Invalid JWTs retain the existing authentication challenge. `purchasesAvailable` is false.

With default configuration, all viewers (including uploaders watching their own videos) receive Free: ads eligible under the existing consent/demo flow, maximum 480p, no downloads. Upload and creator-management actions remain ad-free. The backend filters whole variants out of master playlists and denies every direct rendition playlist/segment above 480p before reading R2. If no permitted rendition exists, it refuses the stream; it never silently chooses a higher quality. Source-supported lower resolutions remain available. Processing outputs and R2 objects are unchanged; no re-encoding/deletion occurs.

Ownership, active complete share links, deletion and uploader blocking always authorize the video before any plan lookup. The backend rechecks those permissions after membership resolution as well, so stopping sharing during a slow provider lookup wins before the storage read. A paid plan never makes a private video public or replaces a share capability. The new QUALITY_LIMIT response is 403 only after video authorization; unauthorized videos retain generic unavailable behavior. Existing no-store media responses remain.

## Verified paid read path

The read-only Google-backed resolver is opt-in (`PLAY_MEMBERSHIP_READ_ENABLED=false` by default). It uses the previous real Google adapter/verifier and encrypted V11 journal; journal rows alone cannot grant access. Each fresh cache lookup verifies the stored token with Google and requires a matching account, acknowledged paid receipt, known product/base plan, valid store state and actual expiry. Test receipts still require explicit private opt-in. Unknown/unverified/provider failures yield Free; caller cancellation is propagated.

Paid results are cached per account for at most 60 seconds and never beyond store expiry. Source verification is coalesced per account, the cache is limited to 256 accounts, and Google reads have four concurrent slots and a 25-second refresh budget. Free/error results from the verified resolver are cached for five seconds. Expiry and server clock rollback invalidate cached entries. Refund/revocation visibility can therefore lag by up to the remaining 60-second verified lease; this is bounded caching, not an immediate-revocation guarantee. No Google request/OAuth/refund/renewal behavior has been live-tested in this stage. RTDN, background reconciliation, durable acknowledgement retries and production load acceptance remain gates before checkout is enabled.

GooglePlayPublisher now awaits its HTTP future cancellably: abandoning a refresh cancels that HTTP future rather than waiting on a blocking Future.get. Credential-library I/O and real Google response timing remain unverified externally.

`MembershipRuntime` reads the HOCON membership section only when read mode is explicitly enabled. Then it requires a private trusted service-account file, the stable separate Base64-encoded 32-byte AES receipt key, one real Play product ID and its configured Day/Week/Monthly base-plan IDs. Bad enabled configuration fails startup with a sanitized message. Default mode loads no keys and makes no Google requests. This flag does not enable purchases or invent receipts. Do not enable it just to pretend an account is premium.

## Android behavior

A Hilt membership repository fetches the status API while the app is resumed, with a separate one-second lease-expiry/account-change tick. It uses a memory-only monotonic lease anchored before the HTTP request; network delay cannot extend benefits. Maximum lease length is 60 seconds, and the account in the response must match the current local session namespace. Reboot/process restart, rollback, unknown/contradictory response or account switch cannot reuse paid state. Status is not an authorization bypass; the server still controls every media asset.

The shared ad controller checks this current status before loading/showing Watch interstitials, portrait banners and ten-minute video breaks. Initial verification does not initialize ads. Normal refreshes preserve a still-valid lease, avoiding a banner reload on every status poll. A paid/unknown transition clears cached loads; a waiting Watch load can finish into freshly authorized content without showing a late ad. Ads already presented by AdMob cannot be forcibly dismissed through its SDK; there is no purchase UI during them in this stage.

The player hides unavailable/high qualities, constrains Auto to the current limit and clears incompatible saved overrides. Changes of limit reload the authorized master at the current content position, preserving intended pause/play state. Verified ad-free status clears the ad-break clock and loader and rebuilds content without that wrapper; an active dedicated video break restores the prior fullscreen state. Paid watch time does not earn a pending free-viewing ad break. Already-buffered bytes cannot be revoked retroactively; the client responds to expiry on its foreground tick and backend checks continue on each new asset request.

The details Watch path waits for membership refresh when needed before deciding on an interstitial. It keeps the existing fresh video checks before and after that transition. No plan card, checkout button or download control is displayed yet. The returned download allowance is plan metadata, not a working reservation/downloader.

## Changed areas

- Server: ViewerEntitlements, MembershipRoutes, MembershipPlaySource, MembershipRuntime; PlaybackQuality plus PlaybackService/Routes; Application/Routing bootstrap, membership HOCON configuration and cancellable Google API requests.
- Android: ViewerMembershipRepository/ViewModel, NavGraph foreground polling/Watch transition, shared ad controller/components, player quality/expiry changes, video-ad loader/clock cancellation/reset.
- Verification: resolver, HLS/security/HTTP, monotonic/account-switch/mock HTTP, common ad gate and quality-selection tests. Existing native demo-ad fixtures now explicitly supply a synthetic Free membership status; they are not production paid-access switches.

## Private configuration later

No secrets are needed in chat. `.env.example` documents variable names only; Ktor reads process environment, not that file. Keep credentials and the receipt encryption key outside the project/APK and preserve the existing key if receipts already exist. Missing/unconfigured read mode simply means Free; it does not unlock a paid plan. Real Play products, licensing, key setup and receipt activation still need a separate guided step when payment delivery is complete.

## Next gate

Connect authenticated bounded receipt submission, activation status, stable support/payment-attempt records, cache invalidation and durable retry/restore/reconciliation. Implement the promised private download system before selling Week/Monthly benefits, then connect the plans/restore UI and conduct license-test purchases. Do not expose a buy button before those benefits can actually be delivered. No store artifact, real charge, private credential, live ad inventory or cloud deployment was changed here.

Executed verification will be appended after the final build. Code inspection and synthetic tests must not be described as live payment or device verification.


## Executed final verification

Executed in the source-identical verification copy with Java 17, in-process Kotlin and one worker:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:compileBillingTestKotlin :server:test --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

All PostgreSQL/R2/live processing/FFmpeg opt-in flags were explicitly false.

server: 106 passed, 9 skipped, 0 failures/errors
app: 138 passed, 0 skipped, 0 failures/errors
Source/build parity: 248 files, 0 mismatches
BUILD SUCCESSFUL in 9m 38s
Debug APK SHA-256: ac77de2572c074ada654a9e868e92f7b0bb20acc7c1366ea16475f7831f524e8

The Android count includes eight new membership/ads/quality tests (repository lease/network/account races, shared ad gate and clock reset, and free Auto/manual quality cap). The backend count includes ten new resolver/master/HTTP/privacy tests, including revocation during a suspended membership lookup. Backend tests use synthetic provider grants and H2/mock storage. All nine opt-in integrations were skipped, not passed. Native demo-ad fixtures compiled; no native tests were executed. No real Google API/OAuth/payment, live database migration/receipt, real R2 or physical-device paid playback has run.

A development APK was assembled and copied to workspace outputs/Vidoora-verified-benefits-debug.apk; no APK was installed or uploaded. It retains com.example.vidoora and billing OFF. The billing-test variant compiled separately with com.vidoora.app; no store release was created.

Earlier runs correctly failed: a new HTTP fixture auto-loaded the default test application, new mock clients lacked the production base URL/timeout plugin, and the revocation-race assertion initially counted the master's earlier publish-validation read. These fixtures were corrected (isolated application; production-equivalent HTTP setup; compare the storage-read baseline). The final full command above passed against the final source. Android metrics-preference, Exposed deprecation and annotation warnings remained non-fatal.

### Manual check after updating
Restart the existing local backend and update the development app using the new APK or Android Studio. Keep the existing installation/data. For an ordinary guest/signed-in Free viewer, open a valid shared video and confirm the quality choices stop at 480p (or lower if the source is smaller). Uploading/sharing controls should remain unchanged. A real paid/ad-free purchase cannot be tested yet: checkout is unavailable and Google-backed reads remain privately unconfigured by default.

APK validation: Android apksigner verification succeeded. Package is com.example.vidoora, minimum SDK26/target35. Its signing-certificate SHA-256 matches the earlier archived Vidoora-compact-thumbnails-debug.apk (ccc58de6a6c480204cb8b93e6574dc012e15d07e2d5f799f6f75b137e81a1cae). No uninstall/reset was performed. This verifies compatibility with that archived development build, not the identity of an uninspected phone installation.
