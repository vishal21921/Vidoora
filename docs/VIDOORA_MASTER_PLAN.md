# Vidoora — Product Vision, Technical Decisions & Development Roadmap

**Purpose:** Permanent project context for Codex and future contributors.  
**Last consolidated:** 2026-10-13  
**Existing workspace (reported):** `F:\app\app\Vidoora`  
**Source of truth:** This file describes the product vision and reported milestones. Actual repository source, migrations, tests, and deployment configuration determine what is implemented. Do not treat an agent's report as proof of live behavior.

## 1. The product we discussed

Vidoora is an **Android-first video uploading, sharing, in-app playback, and eventual creator-monetization platform**, inspired by DiskWala but intended to provide a better, more reliable user experience. The product should be professional, secure, economical to operate, and capable of growing beyond a prototype.

The primary user journey is: create account → select video → enter metadata and visibility → upload reliably → process the video → publish when ready and permitted → share a link → watch in the **Vidoora Android app** → eventually earn from legitimate monetized activity. Browser playback is **not** a current requirement. Shared links should lead into the Android app, with appropriate installation/deep-link handling to be designed later.

Our conversations initially considered all file types (videos and documents) and uploads up to **5 GB**. Later implementation narrowed to video uploads with a **1 GiB (1,073,741,824 bytes)** enforced limit. **Current scope is video; current limit is 1 GiB.** Supporting documents or increasing the limit is a **future product decision**, not authorization to change the existing implementation.

## 2. Product priorities and constraints

- Launch lean: early infrastructure spending target around **₹0–₹1,000 per month**, not a promise that all traffic fits that budget.
- Audience: initially India, potentially worldwide.
- Test reliability and load before public launch; long-term goal includes supporting many users, but capacity must be measured, not assumed.
- Keep video storage, delivery bandwidth, transcoding, requests, ads, and payout economics visible.
- Avoid expensive managed infrastructure or unnecessary microservices at prototype stage.
- Security, upload durability, correct accounting, and predictable user experience matter more than shipping an unverified feature quickly.
- **Android playback only** is the current product direction; do not build public web playback unless approved.
- Avoid hardcoded secrets, fake view counts, fake revenue, and fabricated integration-test results.

## 3. Core product experience (planned end-state)

### Creator
1. Register/login and maintain secure session.
2. Select a video, validate file type and size, add title, description and visibility (`PUBLIC`, `UNLISTED`, `PRIVATE`).
3. Upload through resumable multipart transfer with durable queue, progress, retry, pause, resume and cancel.
4. See upload and processing states, including actionable errors.
5. When processing/moderation requirements are satisfied, share an app link.
6. Later: creator profile, content management, analytics, earnings and withdrawal requests.

### Viewer
1. Open app or an app deep link to a video.
2. Watch inside Android using an adaptive video player; handle buffering and network changes.
3. Later: feed/discovery, sharing, reports, blocks and appropriate moderation.
4. Legitimate views and ad impressions are measured on the server, not trusted from arbitrary client counts.

### Platform operations
- Enforce ownership, visibility, safe upload sessions, storage lifecycle and abuse controls.
- Process original videos to streaming-friendly outputs and keep originals/derived assets organized.
- Moderate harmful or prohibited content and handle reports.
- Later: sustainable ad monetization and creator earnings with anti-fraud and payout controls.

## 4. Architecture chosen/discussed

### Android client
- Kotlin, Jetpack Compose, Material 3, Navigation, MVVM/Clean Architecture principles.
- Hilt for dependency injection, Ktor HTTP client, Coroutines/Flow.
- Room for local persistence; WorkManager for durable upload execution.
- Encrypted credential/token storage; authenticated requests to backend only.
- Media3/ExoPlayer **planned** for HLS playback; library presence is not a working player.
- Prefer responsive UI, real progress and clear state transitions; preserve existing functions.

### Backend
- Kotlin + Ktor 2.3.12/Netty, Kotlinx Serialization, JWT, BCrypt, PostgreSQL, Exposed ORM, HikariCP, Flyway, AWS S3-compatible SDK.
- Modular monolith initially (`:server`), not premature microservices.
- Backend is authoritative for auth, ownership, upload state, publication state, playback access, views, ads and earnings.
- Explicit environment configuration and safe migrations; production must fail on missing critical configuration rather than silently fall back to test infrastructure.

### Storage, processing, delivery
- **Cloudflare R2** for original uploads; direct client-to-R2 multipart PUTs via short-lived backend-issued presigned URLs.
- **FFmpeg** worker to probe/transcode video and produce **HLS** renditions, only after upload is verified.
- Candidate adaptive renditions: **360p, 480p, 720p**, subject to source resolution, quality and cost; do not upscale low-resolution originals needlessly.
- Store HLS playlists/segments in object storage. Delivery CDN candidate **Bunny CDN** was discussed but **not finalized**; choose only after cost, cache, access control and egress evaluation.
- Media3 ExoPlayer plays HLS **inside Android**; access/visibility and signed delivery strategy must be designed.
- Background worker can initially be simple/cost-conscious; design jobs for retry, leases, idempotency, timeouts, resource bounds and cleanup.

## 5. Data model (reported backend V1)

The first Flyway migration reportedly creates 13 tables:

`users`, `videos`, `upload_sessions`, `processing_jobs`, `video_renditions`, `playback_sessions`, `view_events_hourly`, `ad_impressions`, `creator_earnings_ledger`, `reports`, `moderation_actions`, `user_blocks`, `idempotency_records`.

These tables **do not mean all related product features are implemented**. Confirm columns, indexes, constraints, status values and transactions in the repository before making changes.

## 6. Existing API contracts (reported; verify in code)

Authentication:
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `GET /api/v1/users/me`

Uploads:
- `POST /api/v1/videos/initiate-upload`
- `POST /api/v1/videos/{id}/complete-upload`
- `GET /api/v1/videos/{id}/status`
- `POST /api/v1/videos/{id}/upload-parts/refresh`
- `POST /api/v1/videos/{id}/abort-upload`

Reported auth parameters: BCrypt cost 12, access token around 15 minutes, refresh token around 30 days. The Phase 3.3 report warns that server-side refresh-token revocation/rotation is not complete. Verify actual DTOs and session state handling.

## 7. Milestone history and evidence

### Phase 1 — Product and architecture planning
Discussed DiskWala-style concept, budget, Android-only playback, direct uploads, R2, backend, FFmpeg/HLS and eventual ads/payouts. These are product decisions/plans, not proof of implementation.

### Phase 2 / 2.5 — Backend foundation and hardening (reported)
Ktor backend, database, JWT auth, upload session creation, presigned multipart URLs, completion/status, ownership and idempotency groundwork. Tests were initially mocked for S3/R2.

### Phase 3.1 — Android foundation (reported)
App navigation, login/register, splash, home, profile, secure storage, Ktor client, Hilt, Room and foundational tests.

### Phase 3.2 — Resumable Android multipart upload (reported)
Video picker and metadata, 1 GiB limit, Room upload tasks/parts, WorkManager, multipart streaming, ETags, URL refresh, pause/resume/retry/cancel, upload queue and notifications. Earlier agent reports overstated verification and contained defects subsequently found by Codex.

### Phase 3.3 — Codex audit/hardening (latest report dated 2026-10-12)
Codex reported inspecting the workspace `F:\app\app\Vidoora` and correcting:
- App-private **persistent** upload drafts rather than cache-only storage; free-space checks and cleanup.
- Multipart range validation, full part coverage, ETag validation/order, provider ListParts/HEAD reconciliation and duplicate completion protection.
- Backend session-to-video binding, ownership checks, expired-session cleanup and safer production startup.
- Android account isolation, Room version **3** migration, safer WorkManager/Hilt configuration, cancellation/retry handling, token handling and backup exclusions.
- Tests, README, `.env.example` and `docs/phase-3.3-audit.md`.

**Reported verification:** `:app:assembleDebug`, `:app:testDebugUnitTest`, `:server:test` successful; **44 tests passed, 1 skipped**. These results come from the agent's report, not independent reproduction here.

**Not verified:** live Cloudflare R2 (opt-in integration test skipped due to missing credentials), real PostgreSQL migration/transaction path (backend tests used H2), Android emulator/device end-to-end upload, process kill/relaunch, foreground/background restrictions, near-limit file testing. The report notes Robolectric legacy SQLite mode workaround.

**Outstanding concerns from report:** refresh-token rotation/revocation and rate limiting; upload quotas; production HTTPS/CORS restrictions; pool occupancy from storage calls inside DB transactions; cleanup/lifecycle and costs; background execution/force-stop constraints. Git was not initialized at audit time. Do not claim Phase 3.3 is fully production-verified.

### Phase 3.3 next gate — Live verification (current immediate priority)
1. Initialize Git safely and commit baseline after checking ignored secrets/generated files.
2. Configure **isolated R2 test bucket** and secrets via private environment (never in chat or repository).
3. Run opt-in `LiveR2IntegrationTest` and verify object integrity and cleanup.
4. Configure real PostgreSQL and run migrations plus multipart transaction flow.
5. Run Android emulator/device end-to-end: login, upload, progress, pause/resume, retry, cancellation, app process restart, account switch, notification/background behavior.
6. Record commands, environment, passed/failed/skipped cases and remaining blockers.
7. Obtain explicit approval before Phase 4.

### Phase 4 — FFmpeg processing + HLS (PLANNED; not authorized)
- Claim/lease processing jobs safely, probe original, validate codec/duration/resolution, apply resource limits.
- Generate HLS master/media playlists and suitable renditions (candidate 360p/480p/720p), avoid unnecessary upscaling.
- Upload outputs to R2, persist `video_renditions`, transition states atomically, handle failures/retries/idempotency and cleanup.
- Decide operational worker deployment and cost model before implementation.
- No ad/payout work in this phase.

### Phase 5+ — Planned, sequence to approve separately
- Android HLS playback with Media3 and authenticated playback session/access rules.
- Video feed/discovery, video details, creator profiles, app links/deep links, sharing, visibility.
- Reporting, moderation, user blocks, content safety and abuse controls.
- Ads and valid view/impression measurement, anti-fraud and analytics.
- Creator earnings ledger, withdrawal/payout rules and compliance.
- Load testing, production deployment, observability, scaling and cost optimization.

## 8. Monetization discussion — concept, NOT approved economics

The user discussed DiskWala-style creator payouts and gave an **illustrative** stepped example: first 1,000 views = $1, next 1,000 = $2, next 1,000 = $2; total $9 for 5,000 views. This was an **example to understand payout behavior**, not a sustainable rate approved for Vidoora. Never implement fixed earnings from raw views or promise those rates. Actual earnings must depend on genuine monetizable activity, net revenue, anti-fraud controls, geographic ad yield, platform costs, payout thresholds, tax/legal compliance and a documented policy. AdMob was considered for future monetization; confirm platform policy and suitability before implementation.

## 9. Development operating principles

1. **Inspect the actual repository first.** Never trust a handover over code/tests.
2. **Plan before coding**: identify milestone, affected modules, API/schema implications, costs, risks and acceptance criteria.
3. **One phase at a time.** Do not implement Phase 4, playback, ads or payouts until explicitly approved.
4. Preserve existing functionality and architecture; no project recreation or wholesale rewrites.
5. Prefer small, reviewable diffs with tests; explain every changed file and migration.
6. Maintain exact Android/backend DTO and state-machine contracts.
7. Secure secrets, credentials, presigned URLs, user data and storage access; do not log them.
8. Differentiate unit/mock, H2, Robolectric, real PostgreSQL, live R2 and physical-device evidence.
9. Don't reset production databases, delete buckets, or perform irreversible external actions without explicit approval.
10. Keep infrastructure choices aligned with the early budget; estimate storage, egress, transcoding, API and ad costs honestly.
11. At the end of each milestone, report what changed, commands/tests actually run, what was not tested, blockers and proposed next step.
12. If a requirement is uncertain, ask before changing product behavior; do not silently invent features.

## 10. Codex workflow

Read `AGENTS.md`, this plan, `README.md`, `docs/phase-3.3-audit.md`, `.env.example` and the source tree. Summarize current verified state, reconcile contradictions and propose the **smallest next action**. The next action is **Phase 3.3 live verification**, not Phase 4. Keep this plan updated only for explicitly agreed product decisions; log implementation evidence separately in technical docs. Ask for approval at each phase boundary.

## 11. Current decisions and evidence update — 14 October 2026

The earlier milestone text above is historical, not the current verification status. See `phase-3.3-audit.md` for the evidence record: executed live R2 harness (H2 database), real PostgreSQL startup/migration and an eight-part physical-device upload, provider-listing cancellation cleanup, and user-reported pause/resume, cancellation, reopening, background/notification progress, account isolation, network recovery, paused force-stop, backend restart, backend-unreachable cancellation, phone reboot and oversized-selection rejection. Near-limit, insufficient storage, expiry, local draft cleanup and wider device/security/load checks remain pending.

The user explicitly approved Phase 4 FFmpeg processing and HLS on 14 October, accepting that remaining Phase 3.3 checks will continue later. Initial scope: separate single-job worker, PostgreSQL leases/retry/fencing, bounded local probe/transcoding, source-appropriate HLS up to 720p, private R2 outputs and processing state persistence. Processing completion does not publish content or grant playback. Android player, sharing, ads and payouts remain later approvals.

Cloud hosting was deferred by the user. Development still uses the laptop backend and USB reverse mapping. For Phase 4 local integration, private processed outputs may reuse the existing dedicated test bucket under distinct `processed/` attempt prefixes; production bucket/delivery decisions remain open. Never store signed or public delivery URLs as a substitute for future access-control design.

Phase 4 initial implementation evidence: 28 backend/processing tests executed and passed, one earlier live-upload test skipped in this run; local real FFmpeg and isolated live PostgreSQL/R2 processing tests passed. An existing queued video reached PROCESSED/COMPLETED with three private rendition records. Details, commands, limitations and missing-original handling are in phase-4-processing.md. This does not authorize playback or public deployment.

## 12. Secure Android owner playback — approved 14 October 2026

The user explicitly approved Android playback after checking the Phase 4 processor result and private HLS files. Initial scope: My Videos shows the signed-in uploader's latest 50 videos and processing state; only PROCESSED videos offer Watch. The app uses Media3 HLS and the existing refresh-capable Ktor client for all media requests. The backend rechecks owner and suspension state for every manifest/segment and denies other users regardless of a video's chosen visibility, because processing alone did not publish it. R2 remains private, with no raw-source route, signed URL exposure, query-string tokens or fake view accounting.

Initial delivery proxies bounded HLS assets through the authenticated backend, reusing local laptop/USB development. This adds backend bandwidth/memory/latency costs; direct/CDN delivery must be designed and measured before scale. This is owner playback, not a public feed, sharing or a monetization release. Ads, earnings and payouts remain unapproved.

Owner playback implementation evidence: latest Android run 32 passed, backend run 35 passed and 1 skipped; signed debug APK built. Real fixture master reads through PlaybackService use PostgreSQL/R2; actual phone player checks remain pending. See phase-5-owner-playback.md for commands, sandbox adaptations, delivery costs and approval boundaries.

## 13. Explicit publication and sharing — approved 26 October 2026

The user reported successful phone playback, sound, pause/resume, seeking and reopening, then authorized moving to publication/sharing. This is manual reported evidence, not an instrumentation result.

Scope: processed uploads remain unpublished until the owner explicitly chooses Public or Unlisted. Public appears in paginated Explore for signed-in users. Unlisted is excluded from discovery and requires a video-scoped share capability as well as normal sign-in. Private/unpublished videos remain owner-only. Stop sharing withdraws viewer access; republishing or changing visibility rotates the link generation so old unlisted links stay invalid. No existing video is automatically published by the additive migration.

Local development uses vidoora://watch/<video-id> links, with unlisted capabilities in the fragment. Media requests send the capability in a redacted header, not in playlist URLs or navigation arguments. The app uses Android's system share sheet, handles cold/warm link intents after login, and provides an Open link paste entry in Explore. Capabilities are held only in app memory and cleared on login/account change/logout; consumed incoming Intent data is cleared. Recipients still need a reachable backend. Verified HTTPS App Links, install fallback and cloud hosting remain unconfigured; custom schemes are not verified domain links and another app can register the same scheme.

R2 remains private and delivery still passes through the authenticated backend. No browser player, monetization, view/revenue counting or payout work is authorized. Moderation, quotas/rate limits, anti-abuse and public-launch operations remain pending before opening the service to an unrestricted audience. Test publication uses isolated schemas and synthetic objects, not automatic publication of user uploads.

Publication/sharing implementation evidence (26 October): Android 37 passed, backend 42 passed/1 skipped; debug APK built. V3 and public/unlisted/revocation flows executed using real PostgreSQL/R2 with isolated synthetic data. The actual local public schema remained V2 in a read-only check; no real video was automatically published. Phone publication/sharing acceptance remains pending. See phase-6-publication-sharing.md for exact commands and boundaries.

## 14. Current product direction: personal video storage and link-only sharing

The latest user clarification on 26 October 2026 supersedes Section 13's public discovery/publication direction. Vidoora must not show other creators' videos in a public feed or grant viewer access from a video UUID alone. The user confirmed pasted complete-link playback in a second signed-in account and that Stop sharing produced Video unavailable; these are reported manual results.

Current required model: uploads default to Private; My Videos/queue stay owner-only; the owner may enable link sharing after processing, then explicitly share the complete capability-bearing link. Every non-owner details/playlist/segment request requires sign-in plus a valid video-specific capability. No Public option or public listing is permitted. Stop sharing revokes future access; owner preview remains available. Existing public ID-only links/settings must be withdrawn; existing valid Unlisted shares should be preserved.

Home exposes Open video link directly, with no Explore feed. HTTPS hosting and clickable verified links remain deferred; pasted links are the agreed development workflow. No ads/payout/monetization or cloud spending is newly authorized. See phase-6-link-only-correction.md for migration and verification evidence. Earlier public-sharing discussion and test results are historical, not current access policy.

Link-only correction evidence: Android 38 passed; backend 43 passed and 1 skipped; APK built. Real PostgreSQL V4 migration and synthetic R2 link-only playback/revocation checks executed. Earlier migration files are unchanged, source hashes match verification copies, and phone cutover still requires backend restart plus app update. See phase-6-link-only-correction.md.

## 15. Reporting and blocking — approved 26 October 2026

After the user confirmed the final private/link-only phone flow (second-account playback with a complete link; Stop sharing makes it unavailable), they approved the next proposed scope: report shared videos privately, block the uploader's videos for the viewer, and manage/unblock those preferences. No public feed or ID-only access is restored.

The increment reuses existing reports/user_blocks tables, adds V5 report-query indexes, authenticated API controls and Android details/Home screens. Reports require current sharing access, remain pending, are deduplicated per pending reporter/video and capped at 20 new reports per account per rolling day. Blocking is a viewer preference enforced on future details/HLS requests; unblocking still requires an active complete link to play. No global takedown, moderator console or guaranteed review service exists yet. See phase-7-reporting-blocking.md for exact behavior, verification and device acceptance.

Phase 7 implementation verification: Android 41 passed (no skips/failures/errors), backend 48 passed and 1 older live-upload test skipped; APK built. The extended real PostgreSQL/R2/FFmpeg test executed report persistence/deduplication and block/unblock authorization. V5 was applied in isolated generated schemas; V1–V4 remain byte-identical. Source hashes match verification copies. The real API restart, app update and new phone safety checks remain pending; see the Phase 7 report for commands and limits.

## 16. Blocking-only correction — 26 October 2026

The user confirmed manual Block/Unblock behavior: the video becomes unavailable while blocked and visible/playable after unblocking. They then withdrew reporting because they will not manage reports. Remove report UI and report submissions; preserve the working block/unblock controls. This supersedes Section 15's reporting scope. Existing database records and V1–V5 migration history are retained without deletion. See phase-7-blocking-only.md for the correction and verification; reporting/moderation operations are not part of the current scope.

Blocking-only correction verification: Android 42 passed, backend 42 passed with 5 opt-in integrations skipped; zero failures/errors; APK built. Report endpoint removal/no-new-write, block/unblock/account isolation and publication route regressions passed locally. No real R2/PostgreSQL test was rerun for this correction. Source hashes match verification copies and all V1–V5 migrations are unchanged. Restart/update is pending on the phone; see phase-7-blocking-only.md.

## 17. My Videos management — approved 26 October 2026

After confirming report removal and the block/unblock update on the phone, the user approved three improvements: search their own uploads, edit title/description, and access older uploads beyond the previous latest-50 library limit. New owner-only paged API and metadata PATCH preserve link-only privacy, block preferences, processing/storage fields and existing sharing generations. Search is an explicit case-insensitive title/description substring, pages use creation date plus UUID, and editing does not grant public access. There is no video deletion, reporting, monetization or hosting in this scope. See phase-8-library-management.md for implementation, actual test commands/results, limits and pending phone checks.

Phase 8 executed verification: Android 47 passed; backend 49 passed and 3 opt-in FFmpeg/R2 tests skipped; zero failures/errors; APK built. Real PostgreSQL library fixture verified owner-only search, 61 uploads across pages with timestamp ties, metadata editing and unchanged valid sharing access; V6 index migration applied in isolated schemas. V1–V5 unchanged; source hashes match verification copies. Normal API restart, app update and phone acceptance remain pending. See phase-8-library-management.md.

## 18. Player quality selection — approved 26 October 2026

The user confirmed Phase 8 options were visible, not full save/search/load-more phone acceptance, and approved the next proposed scope: Auto and manual selection among a video's actual supported HLS renditions. Media3 video overrides change quality without restarting playback, preserve audio/settings and revert to adaptive Auto. Saved preference is per video across rotation/retry, falling back to Auto if unavailable. All media requests retain the authenticated backend path; no backend/schema/storage configuration is added. See phase-9-player-quality.md for implementation, executed tests and pending phone switching checks. No reporting, deletion, public feed, hosting or monetization is authorized here.

Phase 9 executed verification: 52 Android tests passed, zero skips/failures/errors; debug APK built. Actual Media3 format/track parameters and a Player-interface test verified supported choices, exact overrides, audio preservation, Auto fallback and absence of seek/source replacement/pause mutations. Source hashes match the verification copy. Native phone switching/rotation/retry checks remain pending. No backend or live integration suite was rerun for this Android-only change. See phase-9-player-quality.md.

## 19. Private library thumbnails — approved 26 October 2026

The user reported quality selection works, clarified that playback speed already exists and works, then approved thumbnails in My Videos. Scope: bounded private JPEG generation during processing, safe leased older-video backfill, actual-owner-only image delivery and Android card previews/placeholders. No public image URLs, shared-link thumbnail access, reporting, deletion or hosting is added. V7 is additive; existing originals/HLS, share settings and playback remain intact. See phase-10-private-thumbnails.md for files, actual verification/maintenance evidence, costs and pending phone checks. Earlier Phase 8 UI visibility still does not establish all search/edit/load-more phone acceptance.

Phase 10 executed verification: Android 55 passed; backend 54 passed/1 skipped; zero failures/errors; APK built. Real FFmpeg, isolated PostgreSQL V7 and R2 JPEG generation/delivery/backfill executed, including lease fencing, delayed retry, owner-only access and private response bounds. A separate authorized maintenance pass applied V7 to the normal schema and prepared thumbnails for all three existing processed videos; read-only aggregates confirmed 3 ready/0 pending/schema 7. Source hashes match verification copies; V1–V6 unchanged. API restart, app update and phone thumbnail/account-isolation checks remain pending. See phase-10-private-thumbnails.md.

## 20. Owner video deletion — approved 26 October 2026

After the user reported thumbnails appeared, they approved the proposed Delete video scope: actual uploader only, explicit permanent confirmation, immediate shared-access revocation, and safe cleanup of original/HLS/thumbnail assets. The implementation uses tombstones, cancels worker ownership, rejects active-upload deletion until queue cancellation/finish, fences completion replays, and schedules durable scoped storage cleanup after a five-minute safety delay. API startup polling/retries require the backend running. Minimal tombstones/operational receipts remain; no restore bin or account-erasure claim is made. See phase-11-video-deletion.md for exact behavior, scope validation, actual tests and pending phone checks. No existing user upload is deleted during development. No public feed, reports, monetization or deployment is added.

Phase 11 executed verification: Android 57 passed; backend 61 passed/1 skipped; zero failures/errors; APK built. Real PostgreSQL/R2 synthetic deletion verified revocation, owner isolation, safety delay, worker cancellation/lease fencing, simulated failure/retry, removal of target original/HLS/preview/abandoned outputs and survival of neighboring fixture assets. Completed-upload replay cannot recreate the deleted video/job. V8 ran in isolated schemas only; V1–V7 unchanged; source hashes match verification copies. No existing user upload was deleted or normal API restarted/migrated by the agent. Phone confirmation and real-time cleanup acceptance remain pending. See phase-11-video-deletion.md.

## 21. Library sorting and filters — approved 26 October 2026

The user confirmed the Delete option worked and the old shared link became unavailable, then approved Newest/Oldest sorting, Ready/Waiting-processing/Failed status filtering and Private/Shared-by-link filtering in My Videos. These combine with owner-only search and pagination, reset cursors on criteria changes and retain the selected view on refresh/return. New cursors include a view fingerprint; legacy default-view cursors remain compatible. No migration, public discovery, report UI, storage write, deletion action or hosting is added. See phase-12-library-filters.md for files, API details, executed checks and pending device acceptance. Physical cleanup of the manually deleted video remains unverified separately from the reported access revocation.

Phase 12 executed verification: Android 59 passed; backend 60 passed/4 opt-in integration tests skipped; zero failures/errors; APK built. Real PostgreSQL verified oldest/newest pagination with ties and combined status/sharing/search views. View-context mismatches, invalid filters, deleted/foreign exclusions and Android stale-page cancellation passed. Source hashes match verification copies; V1–V8 unchanged and no new migration. No normal database/R2 content changed, no app install or API restart performed. Phone checks and the earlier physical deletion-cleanup observation remain pending separately. See phase-12-library-filters.md.

## 22. Private folders — approved 26 October 2026

The user reported Phase 12 options work and approved private folders. Scope: one owner folder per video, create/rename/remove management, moving to a folder or Unfiled, and an All videos/Unfiled/owned-folder selector combined with existing search/sort/status/sharing/pagination. Removing a folder preserves all videos/media/sharing. Names are 1–64 characters and unique case-insensitively per owner; initial bound 100 folders/account. V9 adds an owner-bound composite folder FK and leaves existing videos Unfiled. Viewer details do not expose uploader folder IDs, and folder membership never grants sharing access. See phase-13-private-folders.md for files, tests and pending device checks. No folder sharing, nested folders, bulk video deletion, reports, monetization or hosting is added.

Phase 13 executed verification: Android 62 passed; backend 63 passed/4 opt-in integration tests skipped; zero failures/errors; APK built. Real PostgreSQL V9/folder operations and composite ownership constraint executed in an isolated schema; move/rename/remove preserve videos and the prior sharing capability. Cross-owner and deleted-video moves, name validation, folder-scoped cursors, UI mutation failures and selected-folder reset checks passed. Source hashes match verification copies; V1–V8 unchanged. No normal migration/user folder/video/R2 change, API restart or phone install was performed by the agent. Phone acceptance remains pending. See phase-13-private-folders.md.

## 23. Guest viewers and simpler player — approved 27 October 2026

The user reported folders work on their phone, then explicitly requested that only uploaders/account managers log in. Watching an enabled shared video must require neither email login nor a guest-login step. This supersedes the sign-in requirement in Section 14 and older sharing reports; UUID-only/public discovery access remains forbidden. The proposed refresh-token/session-security phase was replaced by this request and remains unimplemented.

Home now contains a memory-only link field that automatically opens a complete pasted capability-bearing link. The main bottom tabs are Home/Upload/Profile. Upload and owner-management destinations remain login-protected, while anonymous details/HLS reads require a valid active share capability on every asset request. R2 remains private; Stop sharing/deletion/suspension/expiry still revoke future viewer requests. Invalid bearer credentials do not silently downgrade to guest. Account-based blocking remains a personal signed-in preference rather than a global restriction on anonymous link holders.

The player has a portrait 16:9 viewport, landscape fullscreen/Back-to-portrait, and double-tap left/right 10-second seeking with bounds and feedback. Native controller buttons/scrubbing/accessibility, existing speed controls, quality selection and signed-in video details are preserved. No public feed, report feature, hosting, spending, monetization, new schema migration or existing-user-content mutation was added.

Executed verification: 69 Android tests passed, no skips/failures/errors; debug APK built. Backend 66 passed and 4 opt-in live suites skipped; no failures/errors. Real PostgreSQL anonymous capability/revocation checks ran in generated isolated schemas; H2/mock HTTP/storage covered guest HLS and creator-action denials. Native double-tap/button input ran under Robolectric, including a corrected test-main-looper drain before checking posted button clicks. V1–V9 migrations are unchanged and source hashes match verification copies. No live R2 test, normal API restart, phone install or actual fullscreen/guest phone acceptance was performed for this increment. See phase-14-guest-viewing.md for exact files, commands, limitations and device checks.

## 24. Secure creator sessions — approved 27 October 2026

After the user reported that the queued video now plays and that creator login, guest playback, landscape and double-tap seeking work on their phone, they explicitly approved refresh-token rotation and server-side logout. This supersedes Section 23 stating that session work was deferred. The external Start-VidooraProcessor launcher was also corrected to continuous mode after the one-job launcher left later uploads waiting; this is a local launcher change, not cloud availability.

V10 adds persisted refresh sessions with only current-token hashes. Registration/login create independent sessions; refresh rotates a single-use token under a row lock, preserving the original absolute 30-day deadline. Logout idempotently revokes only that login session, including when an older rotated generation is supplied. Legacy stateless refresh tokens cannot renew, so creators should sign in once after update. Existing access JWTs retain their 15-minute lifetime; immediate server-side access-token revocation is not claimed.

Android serializes account/Room changes, coordinates refresh and logout, commits encrypted token changes and keeps encrypted pending logout requests for connected WorkManager retry. A network-send guard uses the current stored identity instead of stale cached tokens, strips credentials from auth/foreign destinations and preserves signed-in block preferences. Renewal decisions use the actually sent failed token. Offline UI distinguishes local sign-out from pending server acknowledgement, and older pending revocations never clear newer logins. No passwords/credentials/configuration are newly required; existing users, videos, guest sharing and processing are retained.

Executed verification: 76 Android tests passed, zero skips/failures/errors; debug APK built. Backend 72 passed and 4 opt-in FFmpeg/live R2 suites skipped, zero failures/errors. Real PostgreSQL V10/session locking, a single concurrent-refresh winner, logout/rotation fencing and fresh-service persistence ran in a generated isolated schema. Android Robolectric/mock HTTP/Room tests verified concurrent rotation, logout finality, durable offline retry/account isolation and stale-provider send/renewal behavior. V1–V9 unchanged; app/server sources match verification copies. No normal API restart/migration, phone install, hardware Keystore or live R2 session result was performed/claimed. See phase-15-secure-sessions.md for exact files, commands, limits and phone acceptance. Broader rate limiting, all-device controls, public hosting and remaining integration/load checks remain separate scopes.

## 25. Automatic processing status — approved 27 October 2026

The user confirmed login, guest playback after logout and the logged-out Upload login prompt on their phone, then approved automatic processing-state updates. The upload queue now explains that UPLOAD COMPLETE means transfer completion and links to Processing & sharing. Owner details moves through the actual server states to Ready to share without manual refresh, with actionable terminal-failure guidance. Readiness never enables sharing automatically and no conversion percentage/ETA is fabricated.

Visible pending My Videos cards use a bounded authenticated owner-only status snapshot endpoint (1–50 IDs) with the same search/folder/status/sharing filters as the library. Updates preserve loaded pages, order, cursor, applied criteria and unsubmitted search drafts; requested rows that are deleted or leave the view are removed only from that UI. Offscreen/unloaded results and new matches in Ready/Failed-only filters still require existing Refresh/return/Load more behavior. A shared five-second single-flight poller is tied to resumed screens, pauses during detail edits/moves/actions, cancels on navigation/background, and backs off failures to 60 seconds. Stale view/action replies are fenced; offline errors preserve cached state.

Executed verification: 84 Android tests passed, zero skips/failures/errors; APK built. Backend 74 passed and 4 opt-in FFmpeg/live R2 tests skipped, zero failures/errors. Real PostgreSQL fixture snapshots verified owner isolation and current filter/deletion rules. Android Robolectric/mock HTTP/coroutine tests covered lifecycle/backoff, visible targets, loaded-page/draft/cursor preservation, filtered removals, offline retention, stale-reply rejection, readiness without publication, edit protection, guest idleness and authorization loss. Source trees match verification copies in both directions; V1–V10 unchanged; no new migration/dependencies. No normal API restart, phone install, live R2 or actual new processing result was executed/claimed for this increment. See phase-16-processing-status.md for exact source files, commands, limitations and phone acceptance. Keep the API and continuous processor running for local development; hosting remains deferred.

## 26. Resume watching locally — approved 27 October 2026

The user reported automatic processing updates work on their phone, then approved Resume watching: remember a stopping point across app closures, offer Resume/Start over, support guests and keep account progress separate without bypassing access checks. The implementation is device-local encrypted numeric progress, not a server watch-history/analytics feature or cross-device sync.

The player freshly checks details access before a saved-position prompt and retains private per-asset HLS checks. Guests still need a complete active link after restart; no links/titles/tokens/raw video IDs are stored as progress. Guest and account UUID namespaces are hashed separately, token renewal retains identity, account changes rebuild the player scope and synchronized/lease guards fence stale writes. Up to 100 records are retained; records older than 30 days are ignored and pruned on later writes. Positions inside the first/last five seconds, invalid/unknown or incompatible timelines do not prompt. Checkpoints are best-effort periodic and pause/exit writes; completion and Start over clear only that video's checkpoint. Existing fullscreen, double-tap, quality/speed and creator/guest flows remain.

Executed verification: 94 Android tests passed, no skips/failures/errors; debug APK built. Ten new Robolectric/mock HTTP/store/session tests covered reconstruction, namespace privacy, bounded retention/corruption, completion/clear, stale writers, fresh access before history disclosure, prepared-media saving, denial/not-ready behavior, retry/timeline compatibility, identity change and single-flight opening. Source tree matches the verification copy in both directions; server source is unchanged from the last Phase 16 verified tree. No backend/PostgreSQL/R2/FFmpeg suites were rerun, normal API restarted, phone installed or live playback result claimed for this Android-only increment. See phase-17-resume-watching.md for exact files, command, limits and phone acceptance. Hardware Keystore/native dialogs/force-stop timing require device confirmation; no cloud hosting or new credentials are needed.

## 27. Responsive UI refinement — approved 27 October 2026
The user reported Resume watching works and requested a professional UI before new features. Approved work covers consistent light/dark theme and typography, centred bounded content, scrollable keyboard-safe forms, clearer Home/Profile and collapsible library filters. Existing creator/guest access, uploads, processing and player behavior remain. This is a single-column adaptive pass; a full tablet navigation redesign, storage overview, hosting and new features are deferred. See ui-refinement.md for source changes, executed verification and pending visual acceptance. Preview declarations do not establish universal device support.


UI verification result: final debug APK built; 94 Android regression tests passed with no failures/errors/skips. App source hashes match the verification project exactly. No backend suite or physical-device/visual acceptance was executed for this increment.


## 28. User-supplied UI references — 27 October 2026
The first generic refinement did not meet the user's visual expectations. They supplied 20 screen images and approved starting with Home, navigation, authentication and Profile. This first group now follows their white/indigo, subtle-border, icon-led design with real Paste/Back/Create account actions, preserving guest capability access, creator login gates, memory-only link input and existing upload/player/session behavior. Remaining upload/library/media/folder references will be treated in the next UI group after review of this direction. No new backend feature or public discovery is implied. See reference-ui.md for exact changes and executed checks; the earlier UI-refinement report describes the prior pass.

Reference UI first-group result: final debug APK built; 94 Android regression tests passed with no failures/errors/skips; exact app source parity verified. A local emulator rendered Home, guest Profile, login/registration and narrow/large-text/dark checks with no real account/API mutation. A later audit found that -datadir did not isolate its AVD disks; the existing local AVD received debug installation/display changes without uninstall/data clear. See the correction in reference-ui.md and video-details-reference-ui.md. Physical-phone and remaining screen acceptance are still pending.


## 29. Video details and safety UI references — approved 27 October 2026
The user specifically asked to implement the previously deferred private/shared/processing details, delete dialog, blocked list and unavailable layouts, using less text. These screens now use the image-led details hierarchy, compact status badges, grouped sharing/management controls, explicit destructive confirmation, avatar/divider list and centred unavailable state. Existing backend authorization and mutations are unchanged; owner details use the existing bounded owner-only thumbnail loader. Missing previews and blocked usernames are not fabricated. Confirmed preflight unavailability has its own presentation flag; transient failures retain Retry and access checks still precede saved progress. See video-details-reference-ui.md for exact files, synthetic/native verification and remaining phone checks. Remaining upload/library/player-reference work is not assumed complete.


Video-details UI result: final APK built; 94 Android regressions passed with zero failures/errors/skips, plus seven native Compose checks passed in the final separately named AVD at normal text/light mode. Synthetic screenshots were reviewed. Confirmation callbacks, private/shared/processing readiness, unavailable Back and blocked Back/Refresh were exercised; no real account mutation or backend/live-storage suite was executed. Exact app source parity was verified. An initial emulator -datadir isolation mistake was disclosed and corrected by explicit separate AVD disk paths; see the report for precise effects and restoration. Real owner thumbnail delivery and physical-phone acceptance remain pending.

## 30. Viewer-only AdMob test integration — approved 29 October 2026
While the user's AdMob account is under review, they approved the revised low-interruption plan: viewers open details from shared links and explicitly tap Watch, with an eligible preloaded interstitial before playback and a banner below portrait playback. The proposed Home/paste-field banner and timed mid-video interstitial were not adopted. Ten minutes is a later-transition cooldown, not a playback interruption timer. At least two in-app interactions and a two-impression process-session cap are used. Owner previews and creator management stay ad-free; signed-in non-owners remain viewers.

The Android integration uses Google demo ad-unit IDs, UMP request permission and privacy choices, safe SDK callback handling, fresh server access before the transition and existing player checks afterward. No video titles/IDs/capabilities/account identities are added to ad requests. An optional validated public AdMob APP ID can configure UMP later; it does not enable live ad units. Release ad requests are explicitly disabled. The link flow now opens details rather than immediately starting the player, superseding the older autoplay destination as approved for the explicit Watch transition.

Live ad units/earnings, account/app approval, user-uploaded-content eligibility/privacy/store rollout and creator payouts are not assumed implemented or approved. See viewer-test-ads.md for exact code, checked Google sources, executed verification and remaining real-device/SDK-delivery limits. Backend, schema and R2 permissions are unchanged.

Viewer demo-ad verification result (29 October): final debug/unsigned-release/instrumentation builds succeeded; 105 Android unit tests and eight isolated-emulator tests passed. UMP permitted requests and SDK initialized. The banner did not produce a load callback within the bounded check; screenshot remained empty and SDK HTTP timeout messages were observed. Actual banner/interstitial delivery and physical-device real-video acceptance remain pending. Release ad requests remain OFF. See viewer-test-ads.md for evidence and limits.

## Viewer-entry correction — approved 29 October 2026
The user clarified that ad eligibility is based on the viewing flow, not ownership. My Videos/Profile/upload-queue details are management-only: no Watch and no ad initialization. Complete pasted/incoming links open an explicit viewer details route, eligible for viewer ads even when the signed-in viewer uploaded that video. Existing consent, cooldown/session cap, unavailable-ad immediate continuation, demo-only units and release OFF remain. This supersedes the earlier owner-preview ad exemption and direct management playback UI. Backend owner permissions are unchanged; this is an Android navigation/product-flow restriction, not an ads-based authorization boundary.

Link-only Watch correction verified: debug/instrumentation builds succeeded, 105 unit tests and eight synthetic native UI tests passed. Source parity: 146 files with zero mismatches. No backend or real ad/media delivery rerun. See viewer-test-ads.md.

## Dedicated video ads and restored owner Watch — approved 29 October 2026
The latest user direction supersedes the earlier management-only Watch restriction and app-wide interstitial cooldown/cap. Restore Watch for server-verified uploaders in My Videos. Guests/non-owners still require complete active share links; backend details and every HLS asset retain authorization. The user explicitly selected dedicated IMA/VAST video ads for a pre-roll on each viewing session and content-timeline breaks every ten minutes in long videos. Use Google demo tags only with UMP request permission and release OFF. Do not use ordinary interstitial timers during content. No ad on upload/profile management; watching one's own video uses the same video-ad flow. Ads cannot be guaranteed or made into a private-media access boundary. Paid ad-free subscriptions, billing, live video-ad inventory/Ad Manager setup and payouts are separate unfinished work. See docs/video-ads.md; the older viewer-test-ads.md describes superseded interstitial stages.

Dedicated demo video-ad result: final debug/unsigned-release/instrumentation builds succeeded; 105 unit tests passed. After correcting two stale UI assertions and selecting software codecs only in the headless network smoke, three targeted native tests passed (53.547s) and actual demo ad playback followed by public sample content was observed (adStarted/contentReady true, playerError zero). Initial failures remain documented. Real ten-minute/private-HLS/physical-device acceptance, live inventory and paid ad-free subscriptions remain pending. Main player codec configuration and backend authorization are unchanged. See video-ads.md.

## Watch interstitial clarification — approved 29 October 2026
The latest user clarification replaces the in-player entry pre-roll: explicit Watch (including My Videos) -> fullscreen AdMob demo interstitial -> dismissal/failure/bounded-unavailability continuation -> freshly authorized player. No owner/login exemption and no inherited app/account cooldown. Later, count ten minutes of actual foreground content playing, excluding pauses/buffering/background/ad time and seek distance; use a dedicated SDK video-ad break in fullscreen, then restore content position/prior fullscreen state. Never insert ordinary AdMob interstitials into active playback. Upload/profile management stays ad-free. Keep consent gating, demo inventory and release OFF. Ad delivery is not guaranteed; paid ad-free subscriptions, billing and live inventory are not implemented by this increment. See docs/watch-interstitial.md; it supersedes entry-pre-roll/content-cue timing in video-ads.md.

Watch clarification verification: debug/instrumentation builds succeeded; 112 unit tests and four targeted isolated-emulator tests passed (96.886s), source parity 152 files/zero mismatches. Dedicated armed demo break returned to public sample content without error. Initial interstitial native run observed no AdActivity and one fallback continuation, so actual initial fullscreen delivery is not claimed. Real ten-minute/private-HLS/phone acceptance remains pending. Demo-only/release-OFF and backend authorization remain. See watch-interstitial.md.

## Cinematic UI redesign, Stage 1 — approved 29 October 2026
The attached redesign brief authorizes Stage 1 ONLY: inspect/map all twenty existing screens, shared cinematic dark design system, controlled reusable components/previews and icon/splash asset assessment. Preserve all business logic, navigation, APIs, ad/subscription behavior, uploads, auth, player lifecycle and security. Do not implement Stage 2 branding/splash or stages 3–6 screen compositions until separately approved. The supplied collage and square logo are visual references, not code or fake production data. Dark palette and shared action/card/header styling are the current foundation; see docs/cinematic-redesign-stage1.md for map, exact files, artwork limitations and executed verification. No new logo generation is authorized.

Cinematic Stage 1 verification: debug/instrumentation builds succeeded; 115 unit tests passed. Three native synthetic UI cases passed across runs; final two capture cases passed with blank-frame rejection (9.613s), and valid screenshots were visually reviewed/saved under docs/assets/cinematic-stage1. Source parity: 156 files; 175 protected Android/backend source/resource files unchanged. Initial capture/API issues are recorded in cinematic-redesign-stage1.md. Stage 2 splash/icon and later screen-layout stages remain unstarted pending approval; separate splash/clean logo-layer assets are outstanding. No live backend/device/ad or release result is claimed.

## Queue deletion, compact cards and launcher icon — approved 29 October 2026
The user explicitly approved swipe-left queue deletion with confirmation and matching My Videos cleanup, compact thumbnail/details cards, icon-only Back/Refresh, 12 dp buttons, and launcher packaging from F:\app\app\vidoora_logo.png. Preserve owner checks, durable DELETING retries, server-acknowledged local cleanup and originals outside managed drafts. This specific increment supersedes the prior Stage 1-only restriction for these files; the remaining staged screen redesign and splash remain deferred. See docs/queue-deletion-ui.md for behavior, affected areas, verification and limits.

Queue/card verification: final debug/instrumentation build succeeded; 124 unit tests passed with zero skips/failures/errors. Two synthetic isolated-emulator queue tests passed (21.173s), including swipe/confirmation and completed state. Source parity 163 files/zero mismatches. Real backend/R2 deletion, physical-device thumbnails and launcher masks were not tested. See queue-deletion-ui.md.

## Compact cards, editable thumbnails and splash — approved 29 October 2026
The latest user request authorizes compact one-line Queue/My Videos cards, Share labeling without file sizes, thumbnail duration overlays, owner custom-thumbnail selection, capability-authorized link thumbnails, and a two-second minimum splash with a moving 30%-width bar. This supersedes earlier owner-only thumbnail-read and deferred-splash UI restrictions for this increment. Keep R2 private, authorize thumbnail GET with the same active video capability/owner checks as playback, require authenticated ownership for bounded/sanitized JPEG edits, and preserve sharing opt-in, deletion retries, upload controls and real session restoration. See docs/compact-thumbnails-splash.md. No other later redesign stage, deployment or live-ad/subscription work is implied.

Compact-thumbnail/splash verification: 127 Android unit tests and 73 backend H2/mock tests passed; eight opt-in integrations skipped. Five native synthetic cases passed (83.766s), followed by a 6.188s splash foreground/color recheck after visual correction. Final debug/instrumentation build succeeded; source parity 165 Android + 60 server files / zero mismatches. Initial invalid-input fixture failure and cold-start LOW_MEMORY process exit are documented. Live R2 thumbnail transfer/physical-device gallery acceptance remain pending.

## Fixed viewer plans and private offline direction — approved 30 October 2026
The user requires Free (ads, up to 480p, no downloads), ₹13/24-hour Day Pass (ad-free, available higher qualities, no downloads), ₹39/7-day Week Pass (ad-free, higher available qualities, five downloads) and ₹99 monthly auto-renewing (ad-free, higher available qualities, 30 downloads per billing period). Prices/benefits must remain unchanged. Practical downloads/resolution decisions were delegated: private account-bound in-app downloads, no Gallery export, idempotent quota reservations/retries, no rollover, offline validation at most 24 hours and never past plan expiry, fresh online sharing/entitlement checks, fail closed on reboot/clock rollback. Existing ownership/share privacy and creator upload functionality remain. All new UI/navigation/support/history items remain planned, not presumed implemented. See docs/viewer-membership-plan.md for exact scope, source facts and implementation gates. Current change is the tested pure policy foundation only: it does not enable billing, free-quality restrictions, paid ad suppression or offline playback. The user has Play Console but Vidoora has no app entry/artifact yet. Never grant premium from a local flag or a mock purchase in production; do not publish/upload or change the application ID/signing identity without concrete user authorization.

Membership policy foundation verification (30 October): nine targeted pure server tests passed with zero skips/failures/errors; final build succeeded in 1m 19s after Kotlin-daemon fallback. Runtime viewing/ad behavior remains unchanged. Play Billing, verified-grant persistence, live 480p enforcement, offline media download/playback and the proposed UI remain future integration steps, not completed features. The user confirmed an existing Play Console account but no Vidoora app entry/artifact.

## Billing client and receipt stage — 30 October 2026
The unpublished Play draft now exists under com.vidoora.app. Separate billingTest identity preserves the existing development installation. A staged Google Play client, real Google API verifier/acknowledgement adapter and encrypted account-bound PostgreSQL receipt journal are implemented, with additive V11 migration. No purchase route, plan UI, runtime paid benefits, Play products, credentials, real payment, RTDN/reconciliation or private download implementation is activated. See docs/play-billing-receipts.md and its executed verification section; do not mistake a verified receipt for delivered ad-free/download access.

## Verified viewer benefits — 30 October 2026
Backend Free480 master/direct-asset enforcement and account-bound read-only entitlement status now exist, connected to Android ad suppression and quality selection with short monotonic leases. Complete share/owner/block/deletion checks remain independent and are repeated after membership lookup. Paid read verification requires private opt-in Google configuration and valid acknowledged receipts; default remains Free and checkout is still unavailable. No paid activation, download runtime, Play purchase or device acceptance has been completed. See docs/verified-viewer-benefits.md for current evidence and the next payment-delivery/download gates.

Viewer-benefits final verification: 138 Android unit tests and 106 backend synthetic/H2/mock tests passed, nine opt-in integrations skipped; full build succeeded in 9m38s. Development APK assembled/signed; billing-test and Android native-fixture Kotlin compiled, with 248 source/build files matching the verification copy. No native/live Google purchase/PostgreSQL/R2 test ran. See verified-viewer-benefits.md for failed fixture runs, corrected final evidence and manual acceptance.

## Visible plan preview — 30 October 2026
The user manually saw Free 360p/480p and approved proceeding. Added shared plans entry on Home/Profile/Free portrait playback and one read-only plan preview screen using the existing design system. Exact Day/Week/Monthly terms are displayed; payments/downloads are clearly unavailable and all Coming soon controls are disabled. No billing or entitlement write is wired by this stage. See docs/subscription-plans-ui.md for current verification and scope; paid activation, private downloads, payment history/support and four-tab/history work remain unfinished.

Plan-preview final verification: 142 Android JVM/Robolectric tests passed, including four new Compose UI cases; zero failures/errors/skips. Debug APK assembled, billing-test and native-fixture Kotlin compiled; 250 source/build files match the verification copy. Four synthetic native-canvas screenshots were visually reviewed at normal/150% text. Build succeeded in 2m48s. APK signing verified with the same archived development certificate. No real purchase, backend rerun, device installation or native instrumentation was performed. See subscription-plans-ui.md for earlier capture/rounding failures, corrected final evidence and remaining activation/download work.

## Four-tab structure and full Profile — 30 October 2026
User explicitly asked to finish the complete UI structure first. Added Home/History/Upload/Profile; Upload now owns creator tools and the upload form is a separate child. Profile now displays actual account identity/User ID, current plan/expiry and account/support/settings actions. Public support recipient was provided as vidoora.support@gmail.com; drafts include user ID and optional reference without automatic sending. Watch history, payment records and offline downloads remain unavailable rather than fabricated; app share/rating await a real public release. No backend schema/service or billing activation change. See docs/four-tab-account-structure.md for implementation/test evidence.

Four-tab final verification: 148 Android JVM/Robolectric tests passed, including six new structural/account/support checks; zero failures/errors/skips. Debug APK assembled, billing-test/native-fixture Kotlin compiled; 256 source/build files match the verification copy. Five synthetic native-canvas screenshots were inspected, including 150% bottom-tab labels. Full build succeeded in 6m46s. APK signing certificate matches prior archived development builds. No actual mail send/client launch, device installation, native instrumentation, backend rerun or live payment/storage test was performed. See four-tab-account-structure.md for the corrected initial mailto fixture failure and remaining data services.


## Purchase activation/recovery — 30 October 2026
Approved continuation adds durable encrypted receipt delivery, server verification/acknowledgement and retries, account-scoped observed-order history with stable support references, and isolated billingTest Restore wiring. See docs/purchase-activation-recovery.md. Recovery is separately opt-in and OFF by default; checkout remains unconditionally disabled. Private downloads, full financial/refund/RTDN reconciliation, real Play licensing tests and always-on deployment remain unfinished. This supersedes earlier statements that no receipt API or payment-record service exists; those features are now implemented but unconfigured by default. Never treat synthetic fixtures as live purchase verification.


## Private offline downloads - approved 30 October 2026
The user approved continuing after purchase recovery. Private account-bound encrypted HLS downloads, durable atomic Week5/Monthly30 quota reservations, device-specific cancellation, server-observed transfer proofs and bounded offline leases are now implemented. Profile Downloads is connected; ready details expose Download. Source-available offline quality is capped at 720p, with no Gallery export or private-access bypass. Reboot/clock rollback/expiry require online validation; offline revocation is bounded by the lease, not immediate. See docs/private-offline-downloads.md for precise limits and executed evidence. This supersedes older descriptions of downloads as entirely absent. Checkout remains unconditionally disabled pending real Play/licence/device testing and reliable HTTPS backend delivery; do not infer production readiness or live-purchase success. Existing History and refund/RTDN financial reconciliation remain unfinished.


## Play internal testing preparation - approved 31 October 2026
The user approved internal testing/upload and explicitly requires Chrome for Play Console, not the in-app browser. A Chrome release draft is saved; no bundle upload or rollout is claimed. New playInternal is non-debuggable under com.vidoora.app with private upload signing, target36, demo ads only and a developer-device loopback backend. Ordinary debug remains com.example.vidoora. The latest user chose a new dedicated upload key after forgetting the old location; private local password/key creation is pending. See docs/play-internal-testing.md for secure helpers, fixed private key location, verification evidence and remaining account/tester/upload gates. No public production release, real purchase, live ads, remote hosting, other-app key/tester changes or fabricated paid access is authorized.

## Internal-test checkout - approved 1 November 2026
The user approved implementing the new internal billing checkout update. See docs/internal-test-checkout.md. playInternal alone enables the Android checkout gate; the server requires a separate explicit default-OFF internal-test flag and rejects non-test receipts in this mode. Preserve real server verification, encrypted recovery and fixed INR prices. No public rollout or real charged purchase is authorised; new compiled/uploaded/device evidence must be recorded as it occurs.

## Private media edge — 2 November 2026

User approved connecting the streaming delivery prototype. Source integration now provides an optional compiled HTTPS segment origin, separate edge authentication, shared backend private/quality authorization and a private R2 Worker handler. Existing playback is the default; manifests and offline downloads stay on their current API paths. No live cloud deployment, resource creation, paid upgrade, public bucket, load capacity or laptop-off operation is claimed. See docs/private-media-edge.md for the contract, test evidence and deployment gates.

