# VIDOORA — START HERE: Project Handover

Updated: 7 November 2026 (Asia/Kolkata). This is the consolidated continuation record requested by the owner. It contains no passwords, API keys, signing secrets, customer database dumps or share capabilities.

## 1. How to continue in a new Codex account or chat

Open the existing project at `F:\app\app\Vidoora`. Give the new assistant this file and say:

> Continue Vidoora from docs/VIDOORA_CURRENT_HANDOVER.md. First read AGENTS.md, this handover, README.md and the relevant actual source. Distinguish implemented code, executed verification, owner-reported phone checks and pending external approvals. Keep existing working features and private-video authorization. Summarize the next necessary task before changing code. Do not activate real ads, real-money purchases, new paid services or publish a release without reviewing the concrete configuration with me. Never request passwords or keys in chat.

The application source lives in the project folder; this handover is context, not a replacement for the source. Older documents contain historical stages such as Coming soon or unpublished. Their later follow-ups and current code supersede those old statements. This document records the latest known state, not a fresh audit of every historical claim.

Different Codex accounts do not automatically inherit this chat, file access or cloud sessions. Reopen the project and provide this handover. Back up the project and private credentials separately before replacing the computer or Windows profile.

## 2. Product and fixed decisions

- Android private-video uploading and link-only viewing; no public discovery feed. Guests can open complete shared links and watch without login. Login is for accounts, plans and creator management.
- Only invited creators upload, normally a small selected group (owner expects about 10–40 creators). Creator uploading is free; viewer subscriptions are separate. Viewer payment must never automatically grant uploading.
- Creator invitation available during registration/Profile; admin can generate a single-use invitation valid five minutes. Admin can revoke uploading without deleting existing videos; redeeming a valid invitation can restore creator access.
- Public numeric user IDs begin at 10000001; underlying database UUIDs remain internal. Owner/admin public ID: 10000007. Never grant admin to a different account without explicit authorization.
- Bottom navigation: Home, History, creator Upload hub where permitted, Profile. Keep minimal dark cinematic UI, short text, safe status-bar insets and compact thumbnail/detail cards. Rectangular buttons with roughly 10–12dp corners.
- Private R2; every media/thumbnail/link read must retain owner or active share authorization. Disabling sharing/deleting revokes subsequent online access. Do not replace this with public bucket access.
- Maximum video upload remains 1 GiB. No reports feature (owner withdrew it). Block/unblock remains.
- Support email: vidoora.support@gmail.com. Support requires login and opens a user-reviewed email draft; the app does not silently send it.

## 3. Completed or tested features

### Accounts and uploads
Email/password registration/login, secure refresh sessions/logout, Gmail-backed password recovery, generic recovery response for unknown accounts, numeric support ID, invitation-based creator access, folders, My Videos search/sort/filters, editable metadata/custom thumbnail, owner-confirmed deletion, uploader blocking/unblocking.

Resumable multipart upload with queue, pause/resume/cancel/retry, account-separated queues, background progress notification, interrupted-network recovery and durable deletion cleanup. Queue/My Videos deletion is coordinated. Android queue cards are compact. Owner reported successful large uploads, resume, cancellation and account separation.

A separate FFmpeg processor makes private HLS renditions and thumbnails. Queued/processing/ready states and real processing progress are implemented; do not fake progress or promise a conversion time for arbitrary videos. Sharing is enabled after processing. Processing completion is not public publication.

### Viewing and links
Portrait player, fullscreen/orientation controls, double-tap ±10 seconds, quality/speed controls, resume watching, reconnect/retry from prior position and private offline HLS downloads for eligible members. Watch History records watched videos with thumbnail/detail cards and account separation.

Firebase HTTPS share links open the app when installed/associated, with a Play Store fallback. Shorter signed share capabilities are implemented. Retain the full fragment/capability; video ID alone is not viewer authorization. No browser video playback or guaranteed deferred deep link after installation is claimed; an installing viewer may need to return to the original link.

### Membership and payments
| Plan | India price | Term | Ads | Quality | Download allowance |
|---|---:|---|---|---|---:|
| Free | ₹0 | No paid term | Eligible viewer ads | Up to 480p | 0 |
| Day Pass | ₹13 | 24 hours, prepaid | Ad-free | Available higher qualities | 0 |
| Week Pass | ₹39 | 7 days, prepaid | Ad-free | Available higher qualities | 5 |
| Monthly Ad-Free | ₹99 | Monthly auto-renewing | Ad-free | Available higher qualities | 30 per billing period |

Higher qualities depend on processed source renditions; do not promise 1080p on every file. Offline downloads are private/account-bound in app, not Gallery exports, and subject to quota/expiry/online validation. Admin has server-authorized ad-free/high-quality viewing and effectively unlimited download allowance; never implement this through an editable local premium flag.

Play product `vidoora_viewer_access`; base plans `day-pass`, `week-pass`, `monthly-ad-free`. Owner configured all three as Active and configured India prices. Owner reported successful licence-test purchase/activation, ad suppression, higher quality, expiry returning to ads/480p, offline playback, Week 5/Monthly 30 quota enforcement, monthly renewal and Play cancellation/expiry. These were TEST purchases, not evidence of real-money settlement or production payment approval.

Server-side Google verification/acknowledgement, encrypted receipt recovery, purchase restore and account payment records exist. Production financial/refund/revocation/RTDN reconciliation still needs a fresh review; do not infer it is complete from successful test purchases.

### Admin
Creator/video analytics, per-uploader directory and videos, search/filter/date ranges, views/watch time, compact cards, copy-link controls, uploader-access removal/restoration, five-minute invitation generation, upload-job health, subscriptions/payments with status filters and transaction/user references, ad SDK activity/impression reports.

Ads Analytics shows recorded requests/impressions/activity, not verified AdMob earnings. It is not an authoritative fraud/audit or settled-revenue report. Payment earnings are not connected to a real financial-report source; missing amounts must stay unavailable, not fabricated ₹0. Payment labels include Today/Yesterday/7 Days/30 Days/All; the custom 30 Days business period is the 15th–14th in Asia/Kolkata. Do not describe this custom window as proof of Google's settlement calendar.

Upload Health shows queued, processing, failed/stuck jobs, not the completed-video library. Completed shareable videos are found through My Videos or Admin Video Analytics → uploader.

Service Health shows successful API/database connectivity, processor heartbeat, queued/processing/failed counts, expired leases and last verified backup. RECENT heartbeat is within 90 seconds; missing/stale evidence is Unknown/overdue. Backup marker means pg_dump succeeded and pg_restore --list read the archive, not a restore drill or a daily schedule.

## 4. Latest verified release

- Android package for Play: `com.vidoora.app`. Ordinary development debug is `com.example.vidoora`; do not change Play package identity.
- Latest prepared signed bundle: versionCode **16**, `Vidoora-internal-v16-service-health.aab`.
- SHA-256: `D66DBE7127488AF1459C0DEC3354976E2067BECE8642A725A42BA8B8627B5B7D`.
- File: `C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-internal-v16-service-health.aab`.
- Signature verified. 183 Android unit/Robolectric tests passed on installed Java 17. Initial Java 25 run hit nine SQLite runtime errors; Java 17 rerun passed.
- Real isolated local PostgreSQL VideoAnalyticsIntegrationTest passed; two AdminRevenueTest unit tests passed. No live R2 fixture test rerun for v16.
- Backend release **20261107-service-health** deployed after checksum verification and protected PostgreSQL backup. Live checks verified HTTPS, new admin health reads, fresh processor heartbeat, backup marker, no-store, rejection of unauthenticated/non-admin requests and existing benefits.
- Current schema migration: **V19__service_health.sql**. Do not edit already-applied migrations; add new ones.
- Owner subsequently said: “i checked this version everything is under control.” Record v16 PHONE ACCEPTANCE AS OWNER-CONFIRMED, not agent-observed device automation.
- Agent did not upload v16 to Play. Owner has managed previous internal/closed-track uploads and reported testing/release progression. Current live public listing/track approval and installed version must be rechecked in Console before making claims.
- v16 includes admin Service Health, retained library/history/analytics positions, thumbnail loading feedback and concise detail success snackbars.

## 5. Actual backend and service links

The backend is **Kotlin Ktor running on a Google Cloud Compute Engine Linux VM**, not on the laptop, Firebase Functions, Cloud Run or Cloud SQL. It uses PostgreSQL on that VM. A separate systemd processor runs FFmpeg. Nginx fronts the API with HTTPS. Closing the laptop or losing Codex Premium does not by itself stop these cloud services. Cloud account credits, billing status and resource health remain the owner's responsibility.

| Service | Recorded identity / link | Purpose |
|---|---|---|
| Backend VM | https://console.cloud.google.com/compute/instancesDetail/zones/asia-south1-c/instances/vidoora-test-backend?project=vidoora | VM status and browser SSH |
| Cloud project | `vidoora`; zone `asia-south1-c`; VM `vidoora-test-backend` | Hosted API, database, processor |
| API origin | https://8.234.92.62 | Current v16 API origin; verify certificate/origin on future changes |
| Public basic health | https://8.234.92.62/health | Basic API response only; no private database browser |
| Private web analytics | https://8.234.92.62/analytics | Existing authenticated creator/admin analytics dashboard; not all newer native admin screens are necessarily mirrored here |
| Cloud Shell | https://shell.cloud.google.com/?project=vidoora | Management terminal; session may require authorization and encrypted SSH key passphrase |
| Cloud Billing | https://console.cloud.google.com/billing?project=vidoora | Owner checks credits, trial expiry and costs here |
| Cloudflare R2 bucket | https://dash.cloudflare.com/85defbfe1ddfa535547648a91ce9ecb1/r2/default/buckets/vidoora-test-upload-verification | Private raw/processed objects; bucket name still contains test |
| Firebase Hosting | https://console.firebase.google.com/project/vidoora-links/hosting | Separate links-only Spark project `vidoora-links` |
| Share site | https://vidoora-links.web.app | App links/install fallback, not backend database or browser player |
| Google Play Console | https://play.google.com/console/ | Choose Vidoora (`com.vidoora.app`); review tracks, products, orders, listing and policy status |
| AdMob | https://apps.admob.com/ | Account/app approval and real ad configuration still require review |
| BillDesk | https://connect.billdesk.com/ | Use the owner's private onboarding session/status; no private onboarding token is stored here |

Sign in with the Google account/project already used for Vidoora. Account selectors may differ in a new browser; do not assume authuser=2 always refers to the same account.

PostgreSQL database: `vidoora_hosted_test`, hosted privately on the VM. It has no public dashboard URL. Use SSH and psql or a properly protected admin tool; NEVER expose port 5432 publicly just to browse data. Cloudflare shows video objects, not users, subscriptions or watch-history database rows. Native admin screens provide the implemented scoped analytics/management views; they are not a full database explorer.

Recorded VM service/path layout:
- `vidoora-api`, `vidoora-processor` systemd services.
- `/opt/vidoora/current` points to `/opt/vidoora/releases/20261107-service-health`.
- Private server config under `/etc/vidoora/`; do not print/copy its contents into chat.
- Deployment backup `/var/backups/vidoora/update-20261107-service-health/database.dump` (private).
- TLS renewal uses `vidoora-certbot.timer`; keep it enabled. IP certificates are short lived; monitor renewal. Do not bypass expired certificate browser warnings.
- Existing restricted IAP SSH workflow; do not open public SSH/database ports to work around a connection issue.

Read-only checks after connecting to the VM:
```sh
sudo systemctl is-active vidoora-api vidoora-processor
sudo systemctl list-timers vidoora-certbot.timer --no-pager
sudo -u postgres psql -d vidoora_hosted_test
```
Do not run database migrations manually, restart a busy processor or modify/delete customer rows merely to inspect them. Native Service Health can provide routine checks without SQL.

## 6. What remains when the app becomes publicly available

### Priority A: verify publication, approvals and real monetization
1. Verify the actual public Play listing/production approval in Console and from an ordinary eligible non-tester account. Expected package listing URL: https://play.google.com/store/apps/details?id=com.vidoora.app . A constructed URL alone does not prove publication/access. Do not label an internal/closed tester link public.
2. Supply the accessible listing to AdMob and BillDesk through their official requested review workflows. BillDesk application reference reported by owner: `2610312766`; last owner-reported status submitted/under review. Do not resubmit KYC or accept agreements without the owner's review.
3. Recheck current Google Play payment requirements and the specific purpose of BillDesk onboarding before implementing any separate payment gateway. Current app uses Google Play Billing; BillDesk is not automatically a drop-in replacement or proof of permission to bypass Play Billing.
4. AdMob approval/live ads remain unfinished. Code/builds currently use Google demo ad configuration in playInternal; ordinary release viewer-test ads are OFF. Review account/app approval, real App ID/ad units, consent/UMP, app-ads.txt where applicable, privacy/Data Safety/store disclosures, current placement policies and test-device configuration before a live build. Do not promise an impression or require an ad to load before private-media access.
5. Preserve subscription ad-free checks at every ad entry. Owner requested Watch-entry and maximize-entry ad behavior; recheck actual implementation and current policy eligibility before enabling live placements. Timed video breaks use the separate dedicated video-ad design, not arbitrary ordinary interstitial interruption of content.
6. Current internal-test checkout/server configuration accepts test receipts under explicit flags. Do not merely flip a flag for real charging: verify Google API permissions/credential availability, production receipt path, acknowledgement/retry/idempotency, active products/prices, cancellation/expiry/refund handling and paid entitlement delivery end-to-end.
7. Real financial revenue reporting is NOT connected. Ad activity counts and test purchase statuses do not prove money earned or settled. Add provider reports only as a separately reviewed integration; keep missing revenue unavailable.

### Priority B: operational readiness
- Review automatic database/config backup schedule, retention, access permissions and a real isolated restore drill. v16 only records a verified deployment archive; no automatic daily schedule is claimed.
- Check VM disk/CPU/memory, queue age and processing capacity with representative videos. No guarantee for 2,000 users or 200GB/day has been established. Media may still flow through the backend unless a separately verified edge configuration is active; an empty media-edge build property means existing API playback. Do not call a planned Workers edge deployed.
- Check TLS renewal, service auto-restart, disk headroom, request limits and monitoring/alerts. Do not assume Google trial credits mean unlimited free infrastructure. Check expiry/budget with the owner; no current credit balance recorded.
- Reconcile hosted privacy policy/account deletion/store disclosures against actual ads, analytics, downloads, account data and support behavior. Avoid fabricating a deletion URL or capabilities that are not implemented.
- Keep invitation limits and admin authorization. Do not permanently delete existing uploads or customer data during development. Test destructive workflows with generated isolated fixtures only.

### Explicitly deferred by owner
An admin Cloud Usage screen for R2 storage/object count and Google Cloud costs/credits was discussed, then deferred to a later version. NOT implemented and NOT permission to create billing exports/credentials or paid services now. Proposed later: restricted backend metrics access, delayed-cost labels, Last updated, unknown instead of ₹0. Exact remaining trial credit must not be invented from subtraction.

No further feature has been selected for implementation after this handover. Stabilization and store/monetization approval are the next priorities.

## 7. Local paths, building and secrets

Project: `F:\app\app\Vidoora`.
Main instructions: `AGENTS.md`; current handover: `docs\VIDOORA_CURRENT_HANDOVER.md`.
Architecture references: README.md and docs/VIDOORA_MASTER_PLAN.md (historical, compare current code).
Latest detail: docs/service-health-viewer-polish.md, docs/admin-ad-analytics.md, docs/creator-analytics.md, docs/creator-invites-account-improvements.md, docs/internal-test-checkout.md, docs/private-offline-downloads.md.

Installed working Java: `C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2`.

Build next Play bundle (choose a versionCode not already used in Play, at least 17 after v16; inspect Console first):
```powershell
Set-Location 'F:\app\app\Vidoora'
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\scripts\Build-VidooraPlayInternal.ps1 -VersionCode 17 -ApiBaseUrl 'https://8.234.92.62/' -ShareHost 'vidoora-links.web.app'
```
This is still the internal-test variant, NOT a ready-made public monetization release. Do not use default loopback/internal settings for a hosted release. Do not regenerate signing keys just because the assistant account changed.

Private local locations (paths only; contents are secret):
- `C:\Users\chauh\AppData\Local\Vidoora\Signing\vidoora-upload.p12` and `upload-password.dpapi`; key alias `vidoora-upload`.
- `C:\Users\chauh\AppData\Local\Vidoora\Secrets` for the Google Play service-account JSON. Inspect only filenames/required configuration locally; do not paste JSON/private keys in chat.
- `C:\Users\chauh\AppData\Local\Vidoora\integration-credentials.clixml` for Windows-encrypted local integration values.
- Cloud Shell encrypted SSH key: `/home/vishalchauhan638637/.ssh/google_compute_engine`.

DPAPI-encrypted files are tied to the Windows user/profile. A different Codex login on the SAME Windows profile is not the same as migrating to a new Windows account/computer. Maintain the original keystore and independently saved password in a private encrypted backup/password manager. Do not include secrets, the keystore or database dumps in a public repository, this handover ZIP or chat attachments.

GitHub backup setup began on 8 November 2026: https://github.com/vishal21921/Vidoora.git, development branch dev, preserving the existing initial commit. Verify the latest remote commit before assuming a subsequent local edit was pushed. Main is not updated by this backup task. Keep a private source backup excluding generated build/cache files and separate encrypted backups of necessary signing/server configuration. This handover bundle is DOCUMENTATION ONLY, not a full source/credential/database backup.

## 8. Evidence and handover files

Workspace: `C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs`.
- v16-verification.json (phoneUiCheck updated to owner-confirmed; no new test execution implied).
- v16-server-verification.log; v16-verification.log (contains the earlier timestamp-assertion failure; use final server log for corrected pass).
- v16-signed-build.log; v16-signature-verification.txt.
- v16-backend-verification.jpg.
- v16-release-notes.txt.
- Vidoora-internal-v16-service-health.aab (keep separately; not included in small handover ZIP).

Portable handover folder: `outputs\Vidoora-Handover`; ZIP: `outputs\Vidoora-Handover-2026-11-07.zip`.
The canonical editable copy remains in the project at `docs\VIDOORA_CURRENT_HANDOVER.md`. Update that file after future releases/decisions so the next session does not mistake old records for current implementation.

