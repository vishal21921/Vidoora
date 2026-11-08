# Creator invitations and account improvements — 3 November 2026

## Approved behavior

Creator uploading is free and invitation-only for the owner's selected members. A paid viewer plan never grants uploading. Registration asks for email, password and an optional creator invite, without username/display-name fields. Existing accounts and media remain; V14 preserves creator access for existing accounts with videos. Backend UUID identity is unchanged; a unique generated numeric public account ID is added for support. Android Room3→4 preserves the user and upload queue.

New viewers have no Upload tab. Profile lets existing viewers redeem a code; invite redemption updates the cached server-derived role. API initiation checks current creator/admin role, including a user-row lock around storage allocation. Existing accepted transfers can finish or cancel. UI hiding is not the security boundary. Blocked uploader management remains available to signed-in viewers in Profile.

Invites contain random192-bit codes, are SHA-256 hashed in the database, expire in7 days and can be redeemed once atomically. Only the server owner uses scripts/creator-invites.py; no public invite-creation endpoint or secrets in Android. This is not a strict40-creator cap or a per-creator storage quota. The existing1GiB upload limit remains.

## Download allowance

GET /api/v1/downloads/quota resolves the actual verified provider grant, hashes the same billing period and counts charged downloads plus live reservations. Returns limit/used/reserved/remaining with no-store. UI polls only while the Downloads screen is resumed, keyed to the current account; unavailable data is labelled unknown. Existing reservation enforcement remains authoritative. Removing a completed download does not refund a charged slot; cancelled unused reservations release slots. Period rollover has its own allowance.

## Real processing progress

V15 adds progress_percent. FFmpeg out_time_us relative to probed duration across all renditions supplies0–85%; completed output uploads supply85–99%; committed completion is100. Updates are fenced by job token, status and live lease. Progress file reads are bounded to4KiB and deleted before processed upload. Retries can restart their progress. Queued work has no fabricated percentage or ETA. Owner-only details and bounded owner status snapshots expose it; upload queue distinguishes Queued, Processing N%, Failed and Ready, and shows Share only when ready. No claim of a measured speed improvement: larger-video delay still needs cloud timing/load diagnosis. Sequential conversion and CPU quota are not silently increased.

## Password recovery

V16 stores only hashes of256-bit single-use15-minute codes. Requests use generic responses, per-account five-minute request spacing and a bounded process-local rate limiter. Successful resets consume all outstanding account codes and revoke refresh sessions. Existing access JWTs retain their15-minute lifetime. No auto-login. UI explicitly reports unavailable until a sender is configured.

Sender options: Gmail SMTP over certificate-verified TLS using a PRIVATE Gmail app password (2-Step Verification required), or Resend with a verified sender. Never use a Google login password or send credentials in chat. scripts/configure-recovery-email.py is a private root-only VM setup helper. Actual email delivery remains untested/unconfigured. The rate limiter behind Nginx uses the socket peer and is conservative/shared; distributed abuse limiting and a durable email outbox are not implemented.

## Clickable links

User approved a separate Firebase Spark project to avoid adding Blaze to the existing billed Cloud project. Actual new project: vidoora-links, owner vishalchauhan638637@gmail.com. Hosting deployed at https://vidoora-links.web.app. HTTPS root, /watch/<uuid> rewrite and /.well-known/assetlinks.json returned200. Public association uses the Play app-signing SHA256 supplied by the user: 3B:97:F4:27:31:C2:F4:DF:F6:84:58:51:22:84:EB:1C:6F:22:F6:16:4B:C1:C3:D1:AE:10:2D:EA:F1:05:A5:78.

links/ contains the static site and association generator. No analytics, third-party JavaScript, browser playback or video metadata fetch. Capabilities remain in URL fragments; the Android intent fallback passes the token only to the app, never in the Play Store URL. Installed/verified app links open app details; Watch keeps existing ads/subscription rules. Browser fallback has Open in Vidoora and Play Store installation controls. After installation users must return to the original link; deferred-link recovery is not claimed. Closed testing installation remains restricted to eligible testers.

Set server PUBLIC_SHARE_ORIGIN=https://vidoora-links.web.app and build Android with -PvidooraShareHost=vidoora-links.web.app only after deployment. Legacy vidoora:// links still parse. No device App Links verification claimed yet.

## Executed checks and rollout state

A full validation run passed144 backend tests,3 skipped, including real local PostgreSQL migrations throughV16, concurrent one-use invite redemption, existing PostgreSQL processing/auth/purchase/download tests and generated-video FFmpeg HLS. Android171 tests passed in that run; subsequent queue/link tests have their separate final log. Later targeted mail/queue changes were recompiled and tested; see workspace outputs/creator-queue-check.txt. No live R2 fixture test was re-run for this code change.

Signed version4 is being prepared for internal testing. This document is not a claim that new backend code is deployed. Current hosted runtime remains the earlier release until a protected backup, idle-worker check, coordinated API/processor update and live smoke test complete. Do not upload a new Play release or replace the closed-test review automatically.

## Private invitation commands (after the new backend is deployed)

On the VM, run sudo python3 /opt/vidoora/tools/creator-invites.py create. Copy the displayed single-use code privately to the member. Revoke an unused code with revoke-code (hidden prompt). Disable future uploading for a numeric account using revoke-user --user-id NUMBER; existing videos are preserved. Code creation and sharing are owner actions; no real code has been generated by this implementation task.

## Follow-up: support ID and recovery wording — 3 November 2026
Support IDs are numeric strings padded to at least eight digits (00000042); underlying account numbers and UUIDs are unchanged. Recovery confirmation explicitly says email is sent only if registered; unknown addresses create no reset token and send no email. Signup and Profile both accept creator invitations.
Hosted release 20261103 and its generated R2/HLS smoke test completed successfully. Version 4 AAB was built and provided; no Play rollout is claimed. Gmail recovery availability was verified true over HTTPS, and the user reports password reset working. These follow-up ID/wording edits require a new backend deployment; they are not yet live.


## Public ID and shorter links follow-up

The app displays the stored account number plus 10,000,000. Account number 1 therefore appears as 10000001. Account UUIDs and database identity values remain intact, and the creator admin helper translates the displayed number back before making changes. Existing app sessions may need a fresh login to refresh a cached ID.

New share links use a compact, signed capability in the same URL fragment format that version 4 already accepts. The signature binds the video ID, current sharing version and expiration. Older JWT links remain valid under their original expiration and sharing rules. Stop sharing rotates the sharing version and invalidates either format. Existing browser fallback carries the capability only to the app, and the Play Store URL excludes it.

The follow-up passed 61 focused backend tests. The public Firebase landing script and Android association were checked separately. An Android device test remains pending.
