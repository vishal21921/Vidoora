# Private streaming delivery — 2 November 2026

User approved connecting the delivery prototype. This increment adds opt-in integration code; it does not deploy infrastructure, upgrade any account, migrate data, enable live ads or real charged purchases.

## Flow and defaults

Android still loads and resolves HLS playlists at the API origin. By default all media remains there. Building with `-PvidooraMediaEdgeBaseUrl=https://YOUR-WORKER-HOST/` rewrites only validated six-digit HLS `.ts` segment requests to that compiled HTTPS origin. Playlists, thumbnails, metadata, uploads and offline downloads do not move. Requests to arbitrary hosts, nonsegment routes, query credentials or different ports do not gain the edge identity exception.

The existing session client sends the current viewer identity and complete share capability in headers to this narrowly trusted origin, including access-token refresh after a 401. No session refresh token or edge secret is sent to the Worker. The app developer must control the configured origin; it receives viewer access tokens and share capabilities and must not log them. Android continues to enforce bounded segment reads and DataSpec offsets locally.

The Worker forwards each segment authorization request to `POST /api/v1/internal/media/authorize` over HTTPS. This endpoint is OFF by default (`MEDIA_EDGE_ENABLED=false`). When enabled it requires a separate private 32–256-character `MEDIA_EDGE_SECRET`, then applies the exact shared PlaybackService authorization routine: current owner/share/deletion/block checks, entitlement lookup, recheck after lookup, path and quality checks. It returns a scoped bucket/key without reading segment bytes. An owner still has no automatic paid-quality exemption.

Worker `EDGE_SECRET` must match that server secret; never put it in source, Android, command arguments or chat. The Worker checks the returned bucket and key against the requested video/segment and reads only the configured private R2 binding. It streams at most the configured 16 MiB object bound, returns `no-store`, does not cache authorization, and preserves 401 for refresh. A backend failure denies delivery; it does not downgrade a signed-in viewer to guest. Already-approved/in-flight bytes cannot be recalled on revocation, matching the existing request-time authorization boundary.

Offline downloads retain their existing API path, server-recorded delivery proofs, account quotas and encrypted leases. Their traffic still incurs potential Google egress. Processor uploads and playlist/API responses also remain Google traffic. This increment does not eliminate all Google bandwidth costs.

## Deployment preparation, not executed

1. Host the existing API on stable HTTPS first, with PostgreSQL private and processor separately supervised. No hosted origin currently exists.
2. Copy `deploy/media-edge/wrangler.example.jsonc` to a private reviewed deployment config. Replace API origin and private processed bucket name; bind the matching bucket as MEDIA. Keep ENABLED false initially. Do not enable bucket public access. The default workers.dev address is for controlled evaluation.
3. Enter the separate random edge secret through private environment/secret input on each platform. A publicly reachable backend endpoint is still protected by edge authentication and user/share permissions; review network exposure and abuse controls before deployment.
4. Review Worker account limits/costs and deployment permissions before creating resources. R2 charges and Workers usage are separate from Google trial credits. No paid upgrade is approved. Do not publish a free-plan capacity guarantee.
5. Enable server MEDIA_EDGE_ENABLED and Worker ENABLED only for an isolated test environment. Build the Android test variant with the reviewed edge URL. A new internal Play version is needed; existing installed APK settings do not change remotely.
6. Test real owner/guest/blocked/revoked/deleted playback, free/paid/expired quality, seeking, quality changes, token refresh, network failure, and laptop-off operation. Compare Google outgoing bytes and edge latency before expanding testers. Offline-download tests remain separately necessary.

Rollback: rebuild without the edge property (empty default) to restore the original segment path; do not silently fall back on authorization errors. Turning the Worker off denies edge playback for edge-configured installed builds until rollback/update. Keep legacy API routes working throughout. No migrations or existing upload deletion are required.

## Validation

Executed 2 November 2026: full backend suite 139 discovered, 128 passed, 11 opt-in integration tests skipped, zero failures/errors; Android debug suite 170 passed, zero failures/errors/skips; Worker Node suite eight passed. `:app:assembleDebug` succeeded. Node tests use mocked backend/R2 bindings; JVM tests use H2/mock storage and Robolectric. They are not live R2, Cloudflare runtime, PostgreSQL, Android device or load-test evidence. Existing data was not migrated or deleted.

Commands (PowerShell from the project root, JDK17):

```powershell
.\gradlew.bat :server:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
node --test deploy/media-edge/worker.test.mjs
```

Initial verification attempts encountered a sandbox Gradle lock restriction, a PowerShell unquoted-property parsing error, a nullable URL-field compile error and a test/source snapshot mismatch while editing. These were corrected; the final unchanged-source run succeeded in 3m25s. Test totals above come from the final XML results, not those failed attempts. No signed Play update was built/uploaded by this task.

## Sources checked

- https://developers.cloudflare.com/r2/api/workers/workers-api-reference/
- https://developers.cloudflare.com/workers/configuration/routing/workers-dev/
- https://developers.cloudflare.com/workers/platform/limits/
- https://developers.cloudflare.com/workers/platform/pricing/
