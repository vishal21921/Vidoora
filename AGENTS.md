# Codex instructions — Vidoora

## Mission
Maintain and incrementally build Vidoora, an Android-first video upload, in-app streaming and eventual creator monetization platform. The full agreed vision and roadmap are in `docs/VIDOORA_MASTER_PLAN.md`. Read it before proposing new work.

## Workspace and sources of truth
- Work **inside the existing repository**; do not scaffold a replacement app.
- Read `README.md`, `docs/phase-3.3-audit.md`, `.env.example`, Gradle settings, migrations and relevant code before editing.
- The master plan records discussion and reported progress; **code and reproducible tests determine what exists**.
- Resolve any mismatch explicitly. Do not invent passed tests, deployed services, credentials or implemented features.

## Current phase boundary
- Current authorized focus: **private libraries, link-only sharing, viewer-controlled uploader blocking/unblocking, owner library search/edit/pagination/sorting/filtering, player quality selection, private My Videos thumbnails, confirmed owner-only video deletion, and private folders**, clarified through 27 October 2026. Phase 17 local Resume watching is approved: encrypted bounded numeric checkpoints, guest/account separation, explicit Resume/Start over, fresh server access before a saved-position prompt, and no link/title/token history or offline access bypass. Phase 16 automatic foreground processing-status updates are approved: bounded owner-only status snapshots, preservation of library views/loaded pages, lifecycle-aware backoff, actionable processing states and queue-to-details navigation. No fake conversion percentages, automatic publication or background Android polling. Phase 15 secure refresh sessions are approved: database-backed single-use refresh rotation, current-session server logout, coordinated Android token writes and encrypted durable offline revocation retry. Existing access JWTs retain their 15-minute lifetime; no claim of immediate access-token revocation. Phase 14 is explicitly approved: viewers watch without login using complete active share links; login remains required for uploading/account management; minimal Home/Upload/Profile navigation, automatic pasted-link opening, portrait playback, landscape fullscreen and double-tap ±10-second seeking. No public discovery, public publication, or video-ID-only viewer access. Keep the remaining Phase 3.3 checks documented; do not infer production readiness.
- Phase 4 processing, owner playback, link-only sharing (guest read access approved 27 October) and Phase 7 block/unblock controls and Phase 8 owner-library search, title/description editing and pagination are approved. Phase 9 Auto/supported-rendition player quality selection and Phase 10 private thumbnails (including bounded older-video backfill) are approved. Playback speed already exists and was confirmed working; do not add a duplicate. Phase 11 owner-only video deletion with confirmation, immediate access revocation and durable scoped storage cleanup is approved. Phase 12 My Videos newest/oldest sorting, status and private/shared filters are approved; they must compose with owner-only search and pagination. Phase 13 private single-level folders, folder create/rename/remove (keeping videos), owner video moves and folder-aware library filtering are approved. Do not delete existing user uploads during development; destructive integration checks must use generated isolated fixtures. The user withdrew reporting on 26 October 2026: no Report option or report submission API. Preserve existing records and migration history. The earlier public-discovery/publication approval is withdrawn by the latest user direction. Ads, earnings, payouts, cloud spending and public deployment require further scope approval. Keep R2 private; processing alone never publishes content.
- Keep the implemented upload limit **1 GiB** unless instructed otherwise. Android-only playback remains the goal.

## Workflow for each task
1. Inspect code and describe the existing behavior and affected files.
2. State a short plan, risks, test strategy and any decision that needs approval.
3. Make minimal safe changes; preserve architecture and working functionality.
4. Add focused tests; run feasible Gradle tasks and inspect results.
5. Distinguish mock/H2/Robolectric tests from real R2/PostgreSQL/device tests.
6. Report exact changed files, commands, pass/fail/skip, limitations and next step.
7. Stop at phase boundaries and wait for approval.

## Security and infrastructure
- Never commit or print secrets, access tokens, R2 credentials, presigned URLs or private user data.
- Use environment configuration, a dedicated test bucket and controlled cleanup for integration tests.
- Never silently reset a database, delete production objects or run destructive migration scripts.
- Verify backend authorization, ownership, state transitions and idempotency.
- Treat upload files as durable until server-acknowledged completion/cancellation and confirmed cleanup.
- Keep cost and scalability implications explicit; early budget target ~₹0–₹1,000/month is a constraint, not a capacity guarantee.

## Project structure
Expected modules: Android `:app`, Ktor `:server`. Verify actual source and migrations. Do not assume planned processing, playback, feed, monetization or moderation features exist just because tables/dependencies exist.

## Communication
- User prefers **one clear step at a time**, planning before coding and professional production-bound engineering.
- Ask for missing external configuration securely; never request credentials pasted into chat.
- When the user asks to proceed to the next phase, first summarize the gate evidence and obtain explicit approval for scope.

















## Approved UI refinement — 27 October 2026
The user approved a professional responsive UI pass before new features. Keep Home/Upload/Profile, guest link entry, account gates and all working media/upload controls. Use a consistent accessible light/dark palette, bounded readable widths and scrollable keyboard-safe forms; do not equate previews or unit tests with physical-device verification. See docs/ui-refinement.md. Creator storage overview remains deferred.

## Reference UI first group — approved 27 October 2026
The user supplied generated screen references and approved beginning implementation with Home, bottom navigation, login/registration and Profile. Keep behavior and minimal tabs; apply the reference layout with scalable text/scrolling rather than fixed image coordinates. See docs/reference-ui.md for first-group implementation, emulator verification and remaining reference screens. No public feed, invented statistics or new backend features.

## Video details/safety references — approved 27 October 2026
The user explicitly requested the six reference layouts for private/shared/processing Video details, deletion confirmation, Blocked uploaders and unavailable video, with less explanatory text. See docs/video-details-reference-ui.md. Preserve actual permissions, confirmation actions, status polling, loading/errors and retry behavior; no fabricated usernames, previews or processing percentages. Thumbnail reads remain owner-only. This scope does not authorize unrelated upload/player-feature or backend changes.

## Viewer-only test ads — approved 29 October 2026
The user approved the discussed viewer ad flow: complete shared link -> details -> explicit Watch -> eligible preloaded interstitial -> playback; portrait banner separated from controls. No Home/paste banner, owner/creator-management ads, app-open/exit interstitials or timed mid-video interruptions. Ten minutes is a cooldown at a later natural transition, with a two-impression process-session cap and at least two in-app interactions. Google demo ad units only; release requests remain explicitly disabled. UMP must permit requests; never force consent to make ads appear, and do not interrupt playback with late consent forms. Account review/actual live rollout, content eligibility/privacy/store disclosures and payouts remain separate scopes. See docs/viewer-test-ads.md for exact configuration, sources, tests and acceptance limits. Existing private media authorization always applies before and after an ad.

## Viewer-entry correction — approved 29 October 2026
The user clarified that ad eligibility is based on the viewing flow, not ownership. My Videos/Profile/upload-queue details are management-only: no Watch and no ad initialization. Complete pasted/incoming links open an explicit viewer details route, eligible for viewer ads even when the signed-in viewer uploaded that video. Existing consent, cooldown/session cap, unavailable-ad immediate continuation, demo-only units and release OFF remain. This supersedes the earlier owner-preview ad exemption and direct management playback UI. Backend owner permissions are unchanged; this is an Android navigation/product-flow restriction, not an ads-based authorization boundary.

## Dedicated video ads and restored owner Watch — approved 29 October 2026
The latest user direction supersedes the earlier management-only Watch restriction and app-wide interstitial cooldown/cap. Restore Watch for server-verified uploaders in My Videos. Guests/non-owners still require complete active share links; backend details and every HLS asset retain authorization. The user explicitly selected dedicated IMA/VAST video ads for a pre-roll on each viewing session and content-timeline breaks every ten minutes in long videos. Use Google demo tags only with UMP request permission and release OFF. Do not use ordinary interstitial timers during content. No ad on upload/profile management; watching one's own video uses the same video-ad flow. Ads cannot be guaranteed or made into a private-media access boundary. Paid ad-free subscriptions, billing, live video-ad inventory/Ad Manager setup and payouts are separate unfinished work. See docs/video-ads.md; the older viewer-test-ads.md describes superseded interstitial stages.

## Watch interstitial clarification — approved 29 October 2026
The latest user clarification replaces the in-player entry pre-roll: explicit Watch (including My Videos) -> fullscreen AdMob demo interstitial -> dismissal/failure/bounded-unavailability continuation -> freshly authorized player. No owner/login exemption and no inherited app/account cooldown. Later, count ten minutes of actual foreground content playing, excluding pauses/buffering/background/ad time and seek distance; use a dedicated SDK video-ad break in fullscreen, then restore content position/prior fullscreen state. Never insert ordinary AdMob interstitials into active playback. Upload/profile management stays ad-free. Keep consent gating, demo inventory and release OFF. Ad delivery is not guaranteed; paid ad-free subscriptions, billing and live inventory are not implemented by this increment. See docs/watch-interstitial.md; it supersedes entry-pre-roll/content-cue timing in video-ads.md.

## Cinematic UI redesign, Stage 1 — approved 29 October 2026
The attached redesign brief authorizes Stage 1 ONLY: inspect/map all twenty existing screens, shared cinematic dark design system, controlled reusable components/previews and icon/splash asset assessment. Preserve all business logic, navigation, APIs, ad/subscription behavior, uploads, auth, player lifecycle and security. Do not implement Stage 2 branding/splash or stages 3–6 screen compositions until separately approved. The supplied collage and square logo are visual references, not code or fake production data. Dark palette and shared action/card/header styling are the current foundation; see docs/cinematic-redesign-stage1.md for map, exact files, artwork limitations and executed verification. No new logo generation is authorized.

## Queue deletion, compact cards and launcher icon — approved 29 October 2026
The user explicitly approved swipe-left queue deletion with confirmation and matching My Videos cleanup, compact thumbnail/details cards, icon-only Back/Refresh, 12 dp buttons, and launcher packaging from F:\app\app\vidoora_logo.png. Preserve owner checks, durable DELETING retries, server-acknowledged local cleanup and originals outside managed drafts. This specific increment supersedes the prior Stage 1-only restriction for these files; the remaining staged screen redesign and splash remain deferred. See docs/queue-deletion-ui.md for behavior, affected areas, verification and limits.

## Compact cards, editable thumbnails and splash — approved 29 October 2026
The latest user request authorizes compact one-line Queue/My Videos cards, Share labeling without file sizes, thumbnail duration overlays, owner custom-thumbnail selection, capability-authorized link thumbnails, and a two-second minimum splash with a moving 30%-width bar. This supersedes earlier owner-only thumbnail-read and deferred-splash UI restrictions for this increment. Keep R2 private, authorize thumbnail GET with the same active video capability/owner checks as playback, require authenticated ownership for bounded/sanitized JPEG edits, and preserve sharing opt-in, deletion retries, upload controls and real session restoration. See docs/compact-thumbnails-splash.md. No other later redesign stage, deployment or live-ad/subscription work is implied.

## Fixed viewer plans and private offline direction — approved 30 October 2026
The user requires Free (ads, up to 480p, no downloads), ₹13/24-hour Day Pass (ad-free, available higher qualities, no downloads), ₹39/7-day Week Pass (ad-free, higher available qualities, five downloads) and ₹99 monthly auto-renewing (ad-free, higher available qualities, 30 downloads per billing period). Prices/benefits must remain unchanged. Practical downloads/resolution decisions were delegated: private account-bound in-app downloads, no Gallery export, idempotent quota reservations/retries, no rollover, offline validation at most 24 hours and never past plan expiry, fresh online sharing/entitlement checks, fail closed on reboot/clock rollback. Existing ownership/share privacy and creator upload functionality remain. All new UI/navigation/support/history items remain planned, not presumed implemented. See docs/viewer-membership-plan.md for exact scope, source facts and implementation gates. Current change is the tested pure policy foundation only: it does not enable billing, free-quality restrictions, paid ad suppression or offline playback. The user has Play Console but Vidoora has no app entry/artifact yet. Never grant premium from a local flag or a mock purchase in production; do not publish/upload or change the application ID/signing identity without concrete user authorization.

## Play draft identity — 30 October 2026
The approved com.vidoora.app Play draft now exists, unpublished. This supersedes the earlier statement that no entry exists. billingTest is an isolated debug-derived variant under com.vidoora.app; ordinary debug stays com.example.vidoora. Only both merged manifests were verified; billing, store products and live purchases remain unfinished. See docs/viewer-membership-plan.md. Preserve existing development data; no publishing/signing/product changes are implied.

## Play Billing receipt/client stage — 30 October 2026
The user approved proceeding after the isolated Play identity setup. Billing 9.1.0 gateway, real server-only Google subscriptions API adapter, account-bound verifier, encrypted PostgreSQL receipt journal/V11 migration and retry/acknowledgement service are now staged. They are not registered as purchase routes or attached to UI; no live credential/product/payment or paid entitlement is enabled. Ordinary debug/release billing gate remains false. Prices stay INR13/24h, INR39/7d, INR99/month, with the fixed quality/download benefits. Day/Week prepaid base plans and Monthly auto-renewing type are supported by the staged adapter; actual Play configuration remains unverified. Cached journal rows are never authorization. Do not expose checkout before verified benefit delivery/reconciliation exists. See docs/play-billing-receipts.md for exact scope, verification and remaining work.

## Verified ad-free/quality read path — approved 30 October 2026
The user approved continuing into runtime viewer benefits. Free is now capped at 480p by backend master filtering and per-rendition/segment authorization, including owner viewing; private video permissions remain independent and are rechecked after slow membership resolution. A no-store account-bound membership status API and short-lived verified Google read-side cache are integrated. Android uses only current memory/monotonic account-bound server status for all three ad gates and player quality; no local paid flag. Google read configuration is OFF by default, purchasesAvailable remains false, no receipt submission/paywall is exposed, and private downloads/payment activation/recovery/reconciliation remain unfinished. Never claim a live purchase or immediate provider revocation; verified caches are bounded to 60 seconds and actual store expiry. See docs/verified-viewer-benefits.md for exact behavior, tests, configuration and remaining gates.

## Subscription plans UI preview — approved 30 October 2026
The user confirmed the Free quality stage and approved continuing. Home, Profile and available Free portrait playback now link to one dark, responsive subscription-plan preview with exact INR13/24h Day, INR39/7d Week and INR99/month auto-renewing terms. All purchase buttons remain unconditionally disabled and clearly labelled Coming soon; payments/downloads are explicitly unavailable. Current access/expiry uses only the existing server-derived membership view. No fake premium, Restore, payment history or store offers are introduced. Guest link watching, existing three main tabs, private authorization and ads/quality rules remain. See docs/subscription-plans-ui.md for files, executed evidence and remaining payment-delivery/download/store gates.

## Four-tab/account UI structure — approved 30 October 2026
The user corrected the priority and explicitly requested completing Home/History/Upload/Profile before further subscription work. Upload is now the creator hub (new upload, My Videos, queue, folders, blocked uploaders), with the old authenticated form preserved as new_upload. Profile holds actual identity and copyable existing backend UUID, server-derived plan/expiry, payments/downloads, Support, Settings and release-dependent app share/rating. User supplied vidoora.support@gmail.com; support opens a user-reviewed email draft with user ID and optional transaction reference, never sends mail. History/payment/download data services remain clearly unavailable; do not fake records or turn numeric resume checkpoints into navigable history. Share/rating are disabled until a real public listing exists. This supersedes older three-tab/Profile-creator-tool layouts. Preserve working creator/media/auth behavior and the disabled checkout. See docs/four-tab-account-structure.md for exact routes, verification and remaining gates.


## Purchase activation/recovery — 30 October 2026
Approved continuation adds durable encrypted receipt delivery, server verification/acknowledgement and retries, account-scoped observed-order history with stable support references, and isolated billingTest Restore wiring. See docs/purchase-activation-recovery.md. Recovery is separately opt-in and OFF by default; checkout remains unconditionally disabled. Private downloads, full financial/refund/RTDN reconciliation, real Play licensing tests and always-on deployment remain unfinished. This supersedes earlier statements that no receipt API or payment-record service exists; those features are now implemented but unconfigured by default. Never treat synthetic fixtures as live purchase verification.


## Private offline downloads - approved 30 October 2026
The user approved continuing after purchase recovery. Private account-bound encrypted HLS downloads, durable atomic Week5/Monthly30 quota reservations, device-specific cancellation, server-observed transfer proofs and bounded offline leases are now implemented. Profile Downloads is connected; ready details expose Download. Source-available offline quality is capped at 720p, with no Gallery export or private-access bypass. Reboot/clock rollback/expiry require online validation; offline revocation is bounded by the lease, not immediate. See docs/private-offline-downloads.md for precise limits and executed evidence. This supersedes older descriptions of downloads as entirely absent. Checkout remains unconditionally disabled pending real Play/licence/device testing and reliable HTTPS backend delivery; do not infer production readiness or live-purchase success. Existing History and refund/RTDN financial reconciliation remain unfinished.


## Play internal testing preparation - approved 31 October 2026
The user approved internal testing/upload and explicitly requires Chrome for Play Console, not the in-app browser. A Chrome release draft is saved; no bundle upload or rollout is claimed. New playInternal is non-debuggable under com.vidoora.app with private upload signing, target36, demo ads only and a developer-device loopback backend. Ordinary debug remains com.example.vidoora. The latest user chose a new dedicated upload key after forgetting the old location; private local password/key creation was confirmed by the user. The phone tester account is confirmed; actual bundle upload/rollout remains pending verification. See docs/play-internal-testing.md for secure helpers, fixed private key location, verification evidence and remaining account/tester/upload gates. No public production release, real purchase, live ads, remote hosting, other-app key/tester changes or fabricated paid access is authorized.

## Internal-test checkout - approved 1 November 2026
The user approved implementing the new internal billing checkout update. See docs/internal-test-checkout.md. playInternal alone enables the Android checkout gate; the server requires a separate explicit default-OFF internal-test flag and rejects non-test receipts in this mode. Preserve real server verification, encrypted recovery and fixed INR prices. No public rollout or real charged purchase is authorised; new compiled/uploaded/device evidence must be recorded as it occurs.

## Private media edge — approved 2 November 2026

User approved connecting private segment delivery to backend and Android. See docs/private-media-edge.md. Empty Android edge origin and server MEDIA_EDGE_ENABLED=false preserve existing playback. Keep manifests under API filtering, use fresh existing private/quality checks for every edge segment, and leave offline downloads on their delivery-proof path. This approval does not authorize live deployment, paid upgrades, public buckets, new capacity guarantees or migration/deletion of existing data.



## Creator/account improvements — approved 3 November 2026
User approved free invitation-only creators, simplified email/password registration with optional single-use creator code, numeric support IDs, remaining download allowance, measured processing progress, password recovery and verified HTTPS links with install fallback. Paid subscriptions never grant upload rights. See docs/creator-invites-account-improvements.md for exact implementation, migrationsV14–V16, Room4, tests and unfinished rollout/email/device gates. User explicitly chose a separate Firebase Spark project for links only; do not attach it to paid billing. Existing uploads/data and link privacy remain. Do not fabricate processing percentages/ETAs or claim live email/device/cloud rollout from source tests.

## Creator and admin analytics — approved 5 November 2026
User approved creator-scoped video analytics and a private web admin dashboard. Selected admin public ID 10000007. See docs/creator-analytics.md. Re-read DB roles for report authorization, exclude creator self-views, retain link/privacy checks, keep viewer identifiers pseudonymous and distinguish app-reported estimates from billing/verified-human statistics. No retroactive views or fabricated historical counts.

## Admin ads and invitations — approved 7 November 2026
User approved admin-only ad activity analytics, compact payment heading/top-right status badges, and single-use five-minute creator code generation. See docs/admin-ad-analytics.md. Count SDK impression callbacks, label current inventory test-only, never fabricate earnings or historical records. Fresh server ADMIN checks protect reports/code creation; invitations remain hashed, single-use and server-expiring. Preserve all ad consent and subscription exemptions.


## Service health and viewer polish — approved 7 November 2026
User approved loading/action-feedback/navigation continuity and admin Service Health. Preserve private account/link authorization; report only real request/database results, recent processor heartbeats and verified backup markers. Unknown/stale must never appear healthy. See docs/service-health-viewer-polish.md. No automatic repair, spending changes or daily-backup guarantee is implied.

## Continuation entry point — 7 November 2026
Read docs/VIDOORA_CURRENT_HANDOVER.md first for the consolidated latest release, owner-confirmed v16 phone acceptance, infrastructure links and outstanding approvals. Historical sections above describe earlier stages and must not override later implementation/evidence. Cloud Usage monitoring was explicitly deferred; live AdMob/BillDesk and real-money activation are pending review.
