# Vidoora

Android-first video platform. Current scope includes reliable uploads, private FFmpeg/HLS processing, owner playback and guest link-only playback, private libraries and creator account management, with remaining verification gaps tracked. The upload limit is **1 GiB (1,073,741,824 bytes)**. A separate backend processor produces private HLS; cloud deployment, browser playback, live monetization and payouts remain future milestones. Viewer-only Google demo ad integration is documented in docs/viewer-test-ads.md; release ads are disabled.

## Actual architecture

`app/` is the Android application: Kotlin, Compose/Material 3, navigation, MVVM, Hilt, Ktor, encrypted token storage, Room and WorkManager. Screens cover splash/session restoration, registration, login, welcome home, profile, upload form/queue, My Videos and a Media3 HLS player. My Videos remains owner-only. Home automatically opens complete pasted links to video details; explicit Watch shows a consent-gated fullscreen demo interstitial before playback for every viewer, including uploaders watching from My Videos. After ten minutes actually watching foreground content, a dedicated fullscreen video-ad break resumes the same position. No in-player entry pre-roll or app-wide cooldown. See docs/watch-interstitial.md. Live ads/subscriptions remain disabled or unfinished. No public discovery exists. Viewers do not need a login or guest-account step. Non-owner playback requires a complete active video-scoped share capability on every details/thumbnail/playlist/segment request. Home/History/Upload/Profile are the main tabs. Upload is the creator hub; its upload form and creator data require login. Watch history is currently an explicitly labelled coming-soon surface. Profile holds account ID, plan status, payments, support and settings. The player uses a portrait 16:9 viewport, a landscape fullscreen button and double-tap left/right seeking by 10 seconds. See docs/phase-14-guest-viewing.md for behavior and verification.

`server/` is one Ktor/Netty service using JWT, BCrypt (cost 12), PostgreSQL, Exposed, HikariCP, Flyway and the AWS SDK for R2. The existing V1 migration defines 13 tables. Only users, videos, upload sessions and processing jobs have Exposed implementations. Completed uploads queue jobs for the separate `:server:runProcessor` FFmpeg/HLS worker. Processing completion is PROCESSED, not public publication; Android playback uses private backend media routes with authenticated owner preview or active share-capability access; R2 remains private.

`gradle/` contains the version catalog and wrapper. The Android module uses AGP's built-in Kotlin, KSP and the Hilt plugin. JVM bytecode targets Java 17. See the version catalog for the tested dependency versions.

`feature/auth/login/LoginScreen.kt` at the project root is an old duplicate outside the app source set. The active login screen is under `app/src/main/java/`.

### Upload flow

1. A signed-in user selects a video and supplies title, description and visibility.
2. The app checks available disk space and streams the source to `filesDir/upload_drafts/{taskId}.draft`, enforcing the 1 GiB limit during copying. A temporary copy needs additional device storage equal to the selected file size plus a safety reserve.
3. The backend creates a 24-hour session with 5 MiB parts and presigned R2 PUT URLs valid for one hour. It checks user status, ownership and visibility.
4. Room version 3 atomically records the task, owner and byte ranges. WorkManager uses unique KEEP scheduling and a Hilt worker factory. The worker refreshes pending URLs, seeks into the persistent draft and persists each real provider ETag and progress.
5. After a lost PUT response, the worker resends the same immutable byte range to the same part number. It never fabricates an ETag. A separate HTTP client sends storage bytes without API bearer credentials.
6. Completion requires every part, ordered numbers, matching provider sizes and ETags, and the correct owner/video/session. A database row lock serializes completion, refresh and abort. Session state, video state and one processing job commit together.
7. If storage completion succeeds before a lost response or failed DB commit, a retry checks the uniquely named object's size and finishes the database transition. UPLOADED is not publicly playable.
8. Pause and logout persist state before stopping work. Cancellation persists CANCELLING, retries the backend request and keeps the draft until acknowledgement. Completed/actively completing uploads cannot be cancelled through the UI.

Transient requests use WorkManager exponential backoff, bounded to seven attempts per scheduling cycle. FAILED tasks require manual retry. CANCELLING remains visible for retry if automatic cancellation attempts exhaust. WorkManager survives ordinary process death; Android force-stop prevents execution until the app is reopened. Queue reconciliation runs when a session is restored or established.

## Local setup

Use Android Studio with the SDK configured in your untracked `local.properties`. Install the platform/build tools required by the app build (currently compile SDK 37). The Gradle daemon criteria request Java 25; Java 17 is the backend toolchain. Android Studio's bundled JBR 25 can run the wrapper. Set JAVA_HOME in your local terminal if Java is not on PATH. In PowerShell, quote dotted `-P` options.

Create a local PostgreSQL database and a limited database user. Configure process environment variables before starting the backend:

| Variable | Purpose |
| --- | --- |
| JWT_SECRET | Random secret with at least 32 characters; never use a checked-in example |
| DATABASE_URL | JDBC URL, for example `jdbc:postgresql://localhost:5432/vidoora_db` |
| DB_USERNAME | Database user |
| DB_PASSWORD | Database password; required |
| PORT | Optional; defaults to 8080 |
| R2_ENDPOINT | Account R2 S3 endpoint |
| R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY | Bucket-scoped credentials held only by the backend |
| R2_RAW_BUCKET_NAME | Private original-upload bucket |
| R2_PROCESSED_BUCKET_NAME | Private HLS output bucket for the separate processor |

Set secrets through your local secret manager or a masked terminal prompt. For example, `$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Database password' -AsSecureString)).Password`. Do not paste secrets in chat, commit them, print environment variables, or embed them in Android. The application does not automatically load `.env` files. `DATABASE_URL` must be a JDBC URL, not an ordinary postgres URI.

Start the backend from the project directory:

```powershell
.\gradlew.bat :server:run
```

Flyway applies existing migrations. Startup fails when database connection/migration or required secret configuration fails. There is no automatic fallback to a temporary database. H2 is confined to explicit test configuration. Mock storage must be explicitly enabled with R2_MOCK_MODE=true and does not accept/store real video bytes; it is unsuitable for Android end-to-end upload verification.

The current root `gradle.properties` sets `vidooraApiBaseUrl=http://127.0.0.1:8080/` for the physical phone used in verification. Connect the phone with USB debugging authorized, keep the backend running, and run this in Android Studio Terminal after connecting (repeat after a restart/reconnection if needed):

```powershell
& 'C:\Users\chauh\AppData\Local\Android\Sdk\platform-tools\adb.exe' reverse tcp:8080 tcp:8080
```

This development setup requires the laptop to remain on. SQL Shell checks database records; it does not start the backend. The existing local Start-Vidoora launcher is outside the repository and saves credentials encrypted for the same Windows user/computer. Start it after a laptop restart; do not copy its credential file into this repository.

For an emulator, override `vidooraApiBaseUrl` to `http://10.0.2.2:8080/` and rebuild. Without any override, that is the build script fallback. For a physical device over LAN, use a reachable laptop address. Override the URL without editing source:

```powershell
.\gradlew.bat :app:assembleDebug '-PvidooraApiBaseUrl=http://192.168.1.10:8080/'
```

Debug development permits cleartext traffic. Use HTTPS and restrict cleartext before production. Android 13+ users should allow upload notifications in app settings. Denying notifications does not itself grant permission to bypass platform foreground-service restrictions.

## Tests

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test
```

Room migration and upload worker tests run locally with Robolectric (API 28), an actual Room database and Ktor mock transports. They explicitly use Robolectric's legacy SQLite mode because the native SQLite runtime failed on this Windows environment. Legacy mode is deprecated and should be revisited when the native runtime works in CI. Backend reliability tests use H2 and a fake storage implementation, including provider sizes, lost-response and concurrent-completion cases. They do not demonstrate live R2 compatibility or PostgreSQL integration. Test resources contain only test secrets and explicitly select H2/mock storage.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Connected tests need an Android device/emulator. Manual device checks must also cover process kill/relaunch, pause/resume, offline cancellation, logout/account switch, notification/foreground-service behavior, and uploads near the size limit.

If the local Kotlin daemon cannot write its temp directory, use `'-Pkotlin.compiler.execution.strategy=in-process'`. Sandbox SDK-access failures are environment issues and must not be reported as passing builds.

## Secure live R2 verification

Create an isolated **vidoora-test-*** private bucket and a bucket-scoped R2 object read/write token. Do not use a production bucket. Populate R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY and R2_TEST_BUCKET privately in the terminal running Gradle. The harness also needs multipart listing, abort, GET, HEAD and DELETE permission on that test bucket.

Explicitly enable the test in that terminal:

```powershell
$env:VIDOORA_LIVE_R2_TEST = 'true'
.\gradlew.bat :server:test --tests com.vidoora.backend.LiveR2IntegrationTest --rerun-tasks
Remove-Item Env:VIDOORA_LIVE_R2_TEST
```

Without the opt-in variable, this test is **skipped**, not passed. The harness runs the real Ktor routes against a test H2 database, uploads a synthetic two-part fixture with presigned PUTs, completes it twice, downloads it and compares SHA-256, then verifies cancellation of a second multipart upload. It deletes only object keys created by that run and attempts to abort its own sessions. A passing harness still needs Android-device-to-backend verification and a PostgreSQL migration/transaction check.

Keep raw buckets private. Configure an abort-incomplete-multipart lifecycle rule (for example, one day) to cover provider sessions created before a process crash or lost initiation response. Backend expiry sweeps run at startup and hourly. Failed provider cleanup stays retryable. Completed objects that have an uncertain database outcome are reconciled on completion retry or expiry cleanup; originals are not blindly deleted.

Room 2-to-3 migration preserves task/part data but quarantines old tasks lacking an owner. Users must reselect these videos; assigning them to whichever account signs in next would be unsafe. No destructive Room migration fallback is enabled. Unreferenced local drafts older than a day and terminal-task drafts are cleaned on authenticated startup; expired PAUSED/FAILED drafts are removed with an explanatory error. Routine process death retains active drafts. Cache eviction no longer affects new drafts.

## Remaining constraints and next milestone

As of 14 October 2026, the saved live R2 JUnit report records one executed passing test; real PostgreSQL startup/migration and an eight-part Android upload have evidence. Manual pause/resume, cancel, reopening, background, account-isolation and network-recovery checks were reported successful by the user. Further user-reported checks cover paused force-stop recovery, backend restart recovery, internet-off cancellation, oversized-file rejection and changing notification progress. A read-only R2 listing found zero unfinished multipart uploads for one cancelled key, whose PostgreSQL session was ABORTED. The user confirmed saving a one-day lifecycle rule; timed cleanup was not observed. Local draft cleanup, active process/device restart, cancellation with backend unreachable, near-limit/storage/expiry and real PostgreSQL failure/concurrency checks remain open. Near-limit testing is pending because no suitable file is available. No test suite was rerun for this documentation update. See the audit for evidence sources and exact limits. Refresh tokens currently remain stateless/reusable until expiration: rotation does not revoke the old token, and logout is local. Rate limits, upload quotas, abuse controls, HTTPS-only release configuration, restricted CORS, production observability and real load/cost tests remain needed before public launch. Expired-upload cleanup must be monitored and bucket lifecycle rules independently verified.

Storage calls execute inside locked database transactions for simple single-service consistency. This consumes pool connections during slow storage requests; load-test before scale. Account/profile UI is basic, and there is no monetization or payout implementation. The separate FFmpeg worker is described in `docs/phase-4-processing.md`.

Budget planning must account for stored originals, redundant local copies, operation counts, future transcoding and delivery bandwidth. ₹0–₹1,000/month is an early-development target, not an unlimited-upload/viewer guarantee. No CDN is finalized. Phase 4 private processing was explicitly approved on 14 October 2026 with remaining Phase 3.3 gaps recorded. Owner playback was separately approved and implemented; public access and launch require further design, verification and approval.

See `docs/phase-3.3-audit.md` for the verification record and changed-file inventory.




See `docs/phase-4-processing.md` for processor setup, tests, resource limits and verification results.

## Owner playback milestone (14 October 2026; extended below)

Home → My Videos shows the uploader's recent videos and processing state. PROCESSED videos offer Watch using Media3 HLS. New authenticated library/playback routes serve private manifests/segments through the backend and recheck ownership and suspension on each request. R2 is not made public; PUBLIC visibility alone does not grant another account access. At that milestone there was no public feed or sharing. The 26 October extension below adds private libraries and link-only sharing; ads and view accounting remain unimplemented.

Restart the backend and rebuild/run the Android app to use the new routes/screens. Keep laptop/USB reverse active for local development. See docs/phase-5-owner-playback.md for access rules, bounded-buffer delivery costs, exact tests and remaining device checks.



## Historical publication and sharing (superseded by the link-only policy below)

Processing does not publish a video. My Videos → View video → Publish explicitly chooses Public (listed in Explore) or Unlisted (link required). Other viewers must sign in. Stop sharing withdraws future viewer access and invalidates unlisted links. Private/unpublished media stays owner-only, and the R2 bucket remains private.

The V3 migration defaults all existing records to unpublished; V1/V2 checksums and existing uploads are preserved. Development links use the vidoora:// scheme, not verified HTTPS App Links. Explore → Open link accepts pasted links, and the system share sheet sends them. The laptop backend must remain reachable; internet hosting and install fallback are not configured.

See docs/phase-6-publication-sharing.md for exact access rules, APIs, tests, migration behavior, changed files and phone acceptance steps. Older owner-only documentation records the previous milestone rather than the current publication policy.



## Current sharing policy: private and link-only (26 October 2026 correction)

The latest user direction overrides earlier public discovery/publication sections above. There is no Explore feed or Public option. Uploads are private by default. The uploader uses Profile → My Videos → View video → Enable link sharing → Share. Since 27 October, viewers can paste the complete active link on Home and watch without signing in; the earlier sign-in requirement is superseded. Stop sharing revokes subsequent access while retaining owner preview.

V4 withdraws legacy PUBLIC access/settings, preserves active UNLISTED shares, and changes the database default. Restart the backend, then update the Android app. R2 stays private; hosting/HTTPS links remain deferred. See docs/phase-6-link-only-correction.md for the precise migration, tested access rules, commands and limitations. Earlier public-feature reports are historical and no longer describe the current product policy.

## Shared-video safety: reporting and blocking (26 October 2026)

Shared-video details now offer Report video and Block uploader to non-owners with current link access. Reports are private pending records, not automatic takedowns. Blocking makes that uploader's videos unavailable to the blocking viewer on future details/playlist/segment requests. Home → Blocked uploaders supports Unblock; a valid enabled link is still required afterward. Private libraries, owner preview and private R2 remain unchanged.

Restart the API to apply the additive V5 report indexes and updated access checks, then Sync and Run the Android app. See docs/phase-7-reporting-blocking.md for API contracts, actual verification results, pending phone checks and limitations. Moderator review tooling, cloud hosting and monetization are not part of this increment.

## Current safety controls: Block and Unblock only

The user withdrew reporting after verifying blocking/unblocking on the phone. Report video and the report submission API are removed. Shared-video details retain Block uploader; Profile → Blocked uploaders → Unblock restores signed-in access only with a valid active link. Existing reports and migration history are preserved. Restart the backend and rebuild/run the app to apply the correction. See docs/phase-7-blocking-only.md; the preceding reporting description is historical.

## My Videos: search, edit and older uploads

My Videos now searches the signed-in uploader's titles/descriptions and loads older results with Load more (25 per page). Open one of your videos → Edit details to update its title and optional description. Return to My Videos to refresh the current search. Shared-link viewers cannot edit; existing sharing links/settings and private storage remain unchanged. Reports remain removed.

Restart the backend to load the new page/PATCH routes and apply the additive V6 owner-pagination index, then Sync and Run the Android app. The old mine endpoint remains compatible with older clients. See docs/phase-8-library-management.md for exact behavior, limits, test evidence and pending phone checks.

## Player quality selection

Watch video → Quality offers Auto and only the video's available device-supported rendition heights. Auto adapts normally; manual selection retains playback position and paused state. Playing: <height>p indicates the rendered resolution; buffered video may delay a switch. The choice is retained for this video through rotation/retry, with Auto fallback if unavailable. Authentication/private sharing and backend delivery remain unchanged.

Rebuild/run the Android app; this feature needs no backend restart or migration. See docs/phase-9-player-quality.md for tests, limitations and phone acceptance.

## Private thumbnails in My Videos

My Videos cards now load bounded owner-authenticated JPEG previews from private processed storage. New processing generates a preview; existing processed videos can use thumbnail-only backfill (runProcessor -PthumbnailBackfill=true). Missing/offline/pending previews show a placeholder without blocking video actions. A complete active share link can read only its authorized video thumbnail; it never grants library or thumbnail-editing permission. Uploaders can change a processed video thumbnail from Video details; see docs/compact-thumbnails-splash.md.

Restart the API, then rebuild/run Android after this backend change. See docs/phase-10-private-thumbnails.md for maintenance commands, actual tests, costs, and phone checks. R2 remains private; no Report option or public listing is restored.

## Owner-only video deletion

My Videos → View video → Delete video requires a permanent confirmation. Acceptance immediately hides the video and revokes all shared access; original/HLS/thumbnail cleanup is queued after a five-minute safety delay and retried by the running backend. The Android queue/details deletion flow automatically aborts active upload sessions before retrying owner deletion; an unconfirmed request stays retryable. Only the actual uploader can delete. Existing operational receipts/tombstones remain for idempotency; no restore bin is offered.

Restart the API for additive V8/new cleanup routes, then rebuild/run Android. Use a disposable upload for phone verification; development tests delete only their generated fixtures. See docs/phase-11-video-deletion.md for commands, exact tests, timing/retention limits and acceptance checks.

## My Videos sorting and filters

My Videos now combines search with Sort (Newest/Oldest), Status (All/Ready/Waiting-processing/Failed) and Sharing (All/Private/Shared by link). Changing a control restarts pagination; Load more/Refresh retain the selected view. Reset view restores defaults and clears search. Stopped sharing counts as Private; deleted and foreign uploads remain excluded.

Restart the backend to load the new API parameters, then rebuild/run Android. No migration or new configuration is needed. See docs/phase-12-library-filters.md for precise status meanings, tests and phone checks.

## Private folders

My Videos → Manage folders supports New folder, Rename and Remove. Open an owned video's details → Move to folder to organize it. The Folder selector combines All videos, Unfiled and your own folders with existing search/sort/status/sharing. One folder per video; removing a folder keeps its videos in Unfiled and preserves sharing. Other accounts cannot see or use your folder metadata.

Restart the backend to apply additive V9 and load folder APIs, then rebuild/run Android. No new credentials/storage configuration are needed. See docs/phase-13-private-folders.md for limits, actual checks and phone acceptance.



## Secure creator sessions (27 October 2026)

Registration/login now create a persisted refresh session. Each refresh token is single-use, has a random identifier and is stored only as a SHA-256 hash on the server. Rotation keeps the original absolute 30-day session expiry. Previously issued stateless refresh tokens cannot renew; creators should sign in once after this update. No account/video data is reset. Existing access JWTs retain their 15-minute expiry; logout prevents further renewal rather than claiming immediate revocation of already-issued access tokens.

`POST /api/v1/auth/logout` receives the refresh token in the JSON body, revokes only that login session and is idempotent. A rotated older generation can revoke its own session; it cannot renew it or affect another login. All auth responses are `Cache-Control: no-store`. Guest shared-link playback remains separate and does not create an account/session.

The Android app coordinates refresh/logout, commits replacement tokens before using them and checks current local identity at every actual network send. Offline logout clears the phone and keeps encrypted revocation material for a connected WorkManager retry; it does not claim the unreachable server has already been notified. The pending request survives normal app/process restarts and never clears a newer login. Android force-stop prevents background work until the app is reopened. A lost successful rotation response requires signing in again; spent tokens are not replayed through a grace window.

Restart the backend to apply additive V10, then update through Android Studio and sign in. Existing V1–V9 migrations and uploaded content are preserved. See docs/phase-15-secure-sessions.md for exact tests and phone acceptance.

## Automatic processing status (27 October 2026)

Upload Queue now distinguishes file transfer completion from playback readiness. After UPLOAD COMPLETE, choose Processing & sharing to open that video's details. Details automatically updates Waiting for processing → Processing video → Ready to share while visible. A ready video remains private until the owner explicitly enables sharing. Processing failure has guidance to inspect the processor log and, when appropriate, upload a valid source again. No conversion percentage or estimated finish time is fabricated.

My Videos checks only visible pending items, preserving loaded pages, cursor, sort, applied search, folders and filters. It uses an authenticated owner-only POST /api/v1/videos/mine/status snapshot, capped at 50 requested IDs, with the same server search/filter rules as the library. Rows that leave the selected filter or are deleted disappear from that view; they are not deleted by polling. Offscreen/unloaded videos and new matches in a Ready/Failed-only view are not discovered through these snapshots; use Refresh/return to the library for new results. Manual refresh and normal return behavior remain unchanged.

Checks normally run every five seconds while the relevant screen is resumed, stop on pause/navigation, and back off to at most once per minute after failures. Network failures retain cached state and show a retry notice. Guest/completed/failed details do not keep making status requests. Existing upload WorkManager and backend processing continue independently; keep both the API and continuous Start-VidooraProcessor running for local development. Closing the laptop still stops the local service.

Restart the API and Sync/Run Android Studio for the new endpoint/UI. No new migration or credentials. See docs/phase-16-processing-status.md for validation and pending phone acceptance.

## Resume watching on this device (27 October 2026)

Reopen an accessible video to choose Resume or Start over when a saved position is useful. Progress saves while playing (about every five seconds) and on pause/exit, using the existing encrypted preferences. It is local to this app installation; there is no cross-device sync or browsable watch-history screen. Guests share one device-local guest namespace, separate from each signed-in account. Token renewal does not change an account's namespace.

Only numeric position/duration/time values and hashed account/video keys are stored, never titles, video URLs or sharing capabilities. At most 100 records are retained across the installation. Records older than 30 days are ignored and pruned on later writes. No resume prompt is shown within the first five seconds or last five seconds, or for incompatible/unknown timelines. Completed playback clears that position; Start over clears only that video's checkpoint. Abrupt process termination may lose the last few seconds. Saving progress is best effort and a failure does not block authorized playback.

The player first checks the existing details API before looking up or offering saved progress. Media3 still checks the private playback routes for every asset; expired/revoked links, deleted videos and missing access do not become playable through a saved position. Guests must still provide their complete active link after app restart. The app does not save a link to reopen it automatically.

This is an Android-only change: no backend restart, new migration or credentials. Keep the API reachable, update using Android Studio without clearing app data, then test with a video longer than 30 seconds: watch at least 10 seconds, pause/Back, reopen and choose Resume. See docs/phase-17-resume-watching.md for implementation, actual verification and remaining phone checks.

## UI refinement
The Android UI uses consistent light/dark branding, centred bounded content, scrollable keyboard-safe creator forms and collapsible library filters. Home/Upload/Profile remain the main tabs. See [UI refinement](docs/ui-refinement.md) for affected screens, verification and the remaining device-size/accessibility checks.


Reference-based Home, navigation and account screens: see [reference UI](docs/reference-ui.md) for the implemented first group, emulator checks and remaining screen references.


Video details, safety-list and unavailable-state reference styling: [verification and limits](docs/video-details-reference-ui.md).






## Cinematic UI redesign foundation
Stage 1 establishes the dark coral/pink/violet shared theme and components; existing business logic is retained. Full screen-layout migration remains staged; the subsequently approved compact cards, adaptive launcher and two-second in-app splash are documented in the follow-ups below. Native launch-theme artwork remains separate. See docs/cinematic-redesign-stage1.md for the twenty-screen map, artwork requirements, changes and verification.

Upload queue swipe deletion and synchronized My Videos cleanup, compact media cards and adaptive launcher artwork are documented in docs/queue-deletion-ui.md. Failed deletion stays retryable until the server confirms removal.

Compact cards, owner-editable thumbnails, valid-link thumbnail access and the two-second minimum animated splash are documented in docs/compact-thumbnails-splash.md. Restart the backend and rebuild Android for thumbnail API changes; R2 remains private.


Viewer membership prices/benefits and the tested domain policy are recorded in docs/viewer-membership-plan.md. Live billing, entitlement persistence/enforcement and in-app downloads remain unimplemented; existing playback/ad behavior is unchanged.

### Google Play Billing work in progress
The separate billingTest build uses com.vidoora.app without replacing ordinary debug. A staged Billing 9.1.0 gateway, Google Play server verifier and encrypted receipt journal exist; checkout, entitlement APIs and paid benefits are not activated. See [billing integration status](docs/play-billing-receipts.md) before configuring products or testing payments. Never put Google service-account keys or purchase tokens in the app or chat.

### Verified viewer benefits
Free viewing is now limited to available renditions up to 480p on the backend, including direct media requests. A read-only server membership status connects verified paid leases to the Android ad gates and quality selector; Google-backed reads are OFF by default and checkout remains unavailable. See [verified benefit status](docs/verified-viewer-benefits.md) before private configuration or paid testing. Downloads and real paid activation remain unfinished.

### Subscription plans preview
Home, Profile and Free portrait playback now open a shared read-only plan preview with the approved INR13/INR39/INR99 terms. It clearly marks payments/downloads unavailable and disables checkout. Current access is server-derived. See [plan UI status](docs/subscription-plans-ui.md); real activation, restore, downloads and store testing remain unfinished.


### Four-tab account structure
Home / History / Upload / Profile is now the main layout. Creator tools live under Upload; Profile contains account identity/User ID, current plan/expiry, payment/download/support/settings surfaces and release-dependent share/rating controls. Support drafts target the user-provided vidoora.support@gmail.com. No email is sent automatically. History/payment/download data and live checkout remain unavailable. See [navigation/account structure](docs/four-tab-account-structure.md).


## Purchase activation/recovery — 30 October 2026
Approved continuation adds durable encrypted receipt delivery, server verification/acknowledgement and retries, account-scoped observed-order history with stable support references, and isolated billingTest Restore wiring. See docs/purchase-activation-recovery.md. Recovery is separately opt-in and OFF by default; checkout remains unconditionally disabled. Private downloads, full financial/refund/RTDN reconciliation, real Play licensing tests and always-on deployment remain unfinished. This supersedes earlier statements that no receipt API or payment-record service exists; those features are now implemented but unconfigured by default. Never treat synthetic fixtures as live purchase verification.


## Private offline downloads - approved 30 October 2026
The user approved continuing after purchase recovery. Private account-bound encrypted HLS downloads, durable atomic Week5/Monthly30 quota reservations, device-specific cancellation, server-observed transfer proofs and bounded offline leases are now implemented. Profile Downloads is connected; ready details expose Download. Source-available offline quality is capped at 720p, with no Gallery export or private-access bypass. Reboot/clock rollback/expiry require online validation; offline revocation is bounded by the lease, not immediate. See docs/private-offline-downloads.md for precise limits and executed evidence. This supersedes older descriptions of downloads as entirely absent. Checkout remains unconditionally disabled pending real Play/licence/device testing and reliable HTTPS backend delivery; do not infer production readiness or live-purchase success. Existing History and refund/RTDN financial reconciliation remain unfinished.


## Play internal testing preparation - approved 31 October 2026
The user approved internal testing/upload and explicitly requires Chrome for Play Console, not the in-app browser. A Chrome release draft is saved; no bundle upload or rollout is claimed. New playInternal is non-debuggable under com.vidoora.app with private upload signing, target36, demo ads only and a developer-device loopback backend. Ordinary debug remains com.example.vidoora. The latest user chose a new dedicated upload key after forgetting the old location; private local password/key creation is pending. See docs/play-internal-testing.md for secure helpers, fixed private key location, verification evidence and remaining account/tester/upload gates. No public production release, real purchase, live ads, remote hosting, other-app key/tester changes or fabricated paid access is authorized.

## Internal-test checkout - approved 1 November 2026
The user approved implementing the new internal billing checkout update. See docs/internal-test-checkout.md. playInternal alone enables the Android checkout gate; the server requires a separate explicit default-OFF internal-test flag and rejects non-test receipts in this mode. Preserve real server verification, encrypted recovery and fixed INR prices. No public rollout or real charged purchase is authorised; new compiled/uploaded/device evidence must be recorded as it occurs.

## Private media edge — 2 November 2026

Optional Android segment routing and a private Worker/R2 delivery handler are connected in source behind default-OFF configuration. Playlists retain API quality filtering; offline downloads retain their delivery-proof path. No hosted service or live edge verification is claimed. See docs/private-media-edge.md for configuration, authorization, rollback and remaining deployment gates.



## Creator/account update — 3 November 2026

See [creator invitations and account improvements](docs/creator-invites-account-improvements.md) for the approved invite-only upload gate, numeric IDs, remaining download counters, real processing progress, optional password recovery and Firebase link setup. Source/build verification does not imply cloud rollout; see that document for deployment and email/device gates.

### Admin Service Health and viewer continuity (7 November 2026)
See docs/service-health-viewer-polish.md for the admin-only service/processor/backup indicators and retained library/history/analytics positions. The report reflects a point-in-time read; missing backup/heartbeat evidence remains Unknown.

## Current continuation handover
Start with [docs/VIDOORA_CURRENT_HANDOVER.md](docs/VIDOORA_CURRENT_HANDOVER.md) for the consolidated 7 November 2026 state, v16 acceptance, infrastructure links and pending launch/monetization work. Earlier chronological descriptions in this README may be superseded there and in actual code.

## GitHub development branch
Repository: https://github.com/vishal21921/Vidoora ; working branch: dev. Source and documentation are versioned; local secrets, signing files, machine configuration and generated builds are excluded.
