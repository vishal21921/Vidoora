# Admin ad activity and five-minute creator codes — 7 November 2026

User-approved scope: admin Ads Analytics matching the supplied dark compact reference; one-line payment heading and top-right status badges; admin generation of single-use five-minute creator invitations. Existing ad placements, consent and membership exemptions are unchanged.

## Ad activity

Android reports REQUEST, LOADED, IMPRESSION, CLICK, FAILED and SHOW_FAILED callbacks for existing demo interstitial/banner units. Only SDK onAdImpression callbacks record impressions; loading or pressing Watch does not. Banner SDK refresh loads are treated as a new request cycle when their callbacks arrive; these request/load counts are operational estimates, not AdMob's request/match report. Dedicated IMA breaks are outside this interstitial/banner report.

The report is **test-only**, shows no invented earnings and does not write any creator earnings ledger. AdMob reporting/revenue credentials are not configured. Client callbacks are not independently verified revenue, audience or fraud-proof measurements. Historical ads before this update cannot be recovered.

GET /api/v1/analytics/ads uses fresh database ADMIN/non-suspended authorization and no-store, with India-local Today, Yesterday, 7 Days, 30 Days and custom dates inside a rolling 90-day window. Totals show both formats, while the format selector filters breakdown, graph and recent activity. Missing hourly/daily graph buckets are zero-filled. Devices means pseudonymous app installations with reported impressions, not people. Reinstalls can change this count. No device-level records are exposed in the report.

POST /api/v1/analytics/ads/event supports guests and accounts. Payloads are limited to 1 KiB, require UUID request/device keys and strict format/event enums, and deduplicate by request/event. Follow-up events must match a recorded request/device/format within two hours. Server receipt time is authoritative. A purpose-separated HMAC of the first-party random encrypted installation key is stored, with no raw installation IDs, advertising IDs, account IDs, video links, emails or IP addresses in event rows. Process-local limits are 600/minute per peer address and 6,000/minute globally; network proxies may share that allowance. Android uses a 128-item best-effort queue, ordered bounded retries and no playback dependency. Offline/process death/overflow can omit telemetry. This is not durable billing accounting.

Migration V18 adds ad_sdk_events and creator invite generator metadata without changing existing migrations. Events older than the reporting window are removed when an admin report is read. No claim of background scheduled retention is made. Review/update the hosted privacy policy and Play Data safety disclosure for this first-party app-interaction telemetry before releasing the tracking build; existing policy text may be outdated.

## Creator invites

Settings → ADMIN → Creator invite code. POST /api/v1/analytics/creator-invites re-reads and locks the current ADMIN account, generates 24 secure random bytes (32 URL-safe characters), stores only its SHA-256 digest, and returns the plaintext once with server expiry exactly 300 seconds after creation. Responses are no-store; DTO toString values are redacted. Ten codes maximum per admin per five-minute window, serialized by an account row lock. Existing CLI-generated invitations retain their existing expiry; this does not rewrite already-issued codes.

The member redeems through existing registration or Profile → Become a creator. Existing row-lock redemption enforces expiry, revoked and single-use states atomically. Paid viewing plans still grant no upload rights. Removal/redeeming a fresh invite preserves existing creator data. UI hides generated codes on backgrounding and marks copied clipboard content sensitive. Creating another code does not revoke prior unexpired codes. There is no promise of recovering a plaintext code after the response is lost.

## Verification

### Toolbar and minimal content correction — 7 November 2026

The user's phone screenshots exposed the custom Row toolbar drawing into the status bar. The admin toolbar now uses Material TopAppBar with default safe window insets and standard height, matching Upload Health, with only the title reduced to 16sp and constrained to one line. Payments, Ads Analytics and Creator invite code share it.

Latest direction supersedes the ads revenue card: Ads Analytics shows ad activity only, without monthly/daily earnings, Estimated Earnings or explanatory paragraphs. Its four metrics are total impressions, interstitial impressions, banner impressions and ad requests. All means available retained activity (90 days). Test-inventory labeling remains. Creator code UI retains Generate, code, expiry countdown, Copy and concise errors, without the explanatory paragraphs. Server role checks, expiry and single-use enforcement are unchanged.

Payments shows Today, Yesterday, 7 Days, 30 Days and All. The 30 Days label maps to the custom 15th–14th period via days=-1; internal period dates are no longer displayed. Today uses days=1 and Yesterday days=-2 with exclusive midnight bounds in Asia/Kolkata. Legacy days=30/90 API requests retain their rolling semantics for older builds. The earnings cards remain on Payments, show null income as a dash and have no visible date-range or explanatory footer. No money is inferred.

### Custom revenue cards — approved 7 November 2026

Both admin reports now have the reference-style monthly earnings summary and Today/Yesterday/This Week cards. The custom monthly window starts on the 15th at 00:00 Asia/Kolkata and ends immediately before the next 15th (labelled through the 14th). For example 7 November is in 15 October–14 November. Week means Monday through today in that timezone. Exact dates distinguish this custom management cycle from Google payout periods. Actual subscriptions still use Google's verified expiry and billing period.

GET payment reports accept days=-1 for this custom-month transaction filter; existing 7/30/90/All filters remain. Ads add the matching date-range chip, counting from the current 15th through today. Revenue cards always describe the current custom cycle and current days/week, independently of list/activity filters. Active-plan counters remain a current entitlement snapshot.

The backend returns explicit disconnected revenue metadata with null money fields, because no AdMob revenue reporting or Google Play financial-report import is connected. Null displays as “—”, never ₹0, sample earnings, fixed-plan-price multiplication or demo/test transaction income. Ad revenue would be estimated reporting, and Play amounts gross sales; neither is presented as a payout or net profit. Amounts use exact integer minor units and currency-aware formatting. Provider reports may lag and require later secure integration; the UI does not claim realtime earned money.

Official reference: https://support.google.com/googleplay/android-developer/answer/137997 — Play normally pays orders from a calendar month around the following 15th. The requested 15th–14th view is a custom cycle, not Google's accounting definition.

Source changes and local test outcomes do not mean the migration/backend, hosted privacy policy or Play build has been deployed. Record executed checks and rollout separately.

Executed local verification: the complete Android debug suite passed 178 tests with no failures/skips. After card-spacing refinement, both focused native-render UI tests passed again and screenshots were inspected at 360 dp. The isolated real PostgreSQL VideoAnalyticsIntegrationTest passed, including migration V18, ad deduplication/device binding, role denial despite an ADMIN JWT claim, five-minute invite expiry, single-use redemption, generation limit and demotion checks. Android/backend compilation and server installDist passed. Physical-device and live deployment verification remain separate.

Signed com.vidoora.app versionCode 13 internal-testing AAB built successfully with the existing private upload key; signature verified. Not uploaded to Play by the agent.

Deployment preparation: distribution archived and uploaded to Cloud Shell. First SSH command stopped before VM changes because gcloud had no active credentials. Interactive Google Cloud SDK reauthentication is pending user completion. No claim of migration V18 or backend rollout on the hosted VM yet.

Live deployment 7 November 2026: release 20261107-ad-analytics applied after checksum verification and protected PostgreSQL backup. Cloud Shell session restart restored automatic credentials after failed CLI reauthentication; no network rules or account roles were changed. LIVE_CHECKS_PASSED verified the admin ad report, V18 invite metadata, non-admin denial for ads/invite creation and existing admin viewing benefits. DEPLOYMENT_VERIFIED confirmed API and processor active, public HTTPS healthy and unauthenticated protected routes denied. No synthetic ad impressions or usable creator invitations were generated on the live service. Actual phone callback collection and admin code redemption remain device acceptance checks after installing version 13. The bundle has not been uploaded to Play by the agent.

Custom-cycle verification 7 November 2026: full Android debug suite passed 180 tests without failures/skips. Two server unit tests passed for India-midnight 14th/15th rollover, year boundaries and leap February; isolated PostgreSQL analytics test passed including days=-1 and exclusion one second before the 15th. Native 360 dp screenshots for both revenue-card screens were inspected. Backend release 20261107-revenue-cards deployed after checksum verification and database backup. Live checks validated custom dates, unavailable/null income metadata, protected admin reads and existing benefits. API/processor active and public HTTPS healthy. Signed versionCode 14 bundle built and signature verified. No financial-report integration, charged-income verification, phone verification or Play upload by the agent is claimed.

Layout correction local verification: full Android debug suite passed 182 tests with zero failures/skips, including explicit 24px status-inset/standard toolbar height checks and a creator-code screen without automatic generation. Native 360dp previews of ads, payments and creator-code screens were inspected. Real isolated PostgreSQL analytics test passed Today/Yesterday separation and the existing custom-month checks; two revenue-date unit tests also passed. Signed version 15 build and hosted filter deployment are in progress; these results alone do not establish rollout.

Layout correction rollout 7 November 2026: signed com.vidoora.app versionCode 15 AAB built and signature verified. Release 20261107-admin-layout deployed after protected PostgreSQL backup and checksum verification. Live admin reads passed Today/Yesterday/7 Days/custom-month/All payment filters; protected admin route checks and existing benefits passed. API and processor active, public HTTPS healthy. No Play upload or physical-phone UI verification by the agent is claimed. The status-inset/standard-height behavior was verified in native Robolectric renders; the user's phone remains the final acceptance check.
