# Service Health and viewer polish — 7 November 2026

Approved scope: viewer loading/action feedback and navigation continuity, plus a compact admin-only Service Health screen.

## Behavior
- My Videos reconciles already loaded pages through bounded owner status snapshots on return, preserving pagination, filters and scroll anchors. Manual Refresh still reloads the library. Resume requests stop when the screen stops; polling and return snapshots are serialized.
- History and creator/admin analytics retain numeric scroll positions across temporary loading states. History positions are separated by account identity; no video title or share capability is stored in saved UI state.
- Thumbnails distinguish loading from missing previews without changing card dimensions. Existing Link copied feedback remains. Existing success notices on Video details become short snackbars.
- Settings → ADMIN → Service Health reads `/api/v1/analytics/service-health`. A successful response proves this API request reached the service and PostgreSQL. A failed refresh clears old indicators and shows unavailable.
- A processor process independently writes a PostgreSQL heartbeat every 15 seconds. RECENT means within 90 seconds, STALE means overdue, UNKNOWN means absent or inconsistent time. It does not prove individual video progress or future availability. Existing job leases remain authoritative for jobs.
- Show real queued/processing/failed jobs and expired processing leases. No polling in the Android background, storage scan, public health details or automatic repair actions.
- Last verified backup is an optional database marker written by the deployment tool only after pg_dump completes and pg_restore --list can read the archive. This is archive validation, not a full restore drill. Missing marker is Unknown. No daily backup schedule is introduced or implied.

## Security and migration
V19 creates the small `service_operational_status` table. Every report checks the current database ADMIN role and suspension state, even when a JWT claims ADMIN. Responses use no-store. No paths, credentials, raw errors or private media links are returned.

## Verification
183 Android unit/Robolectric tests passed on the installed Java 17 runtime. The initial Java 25 run compiled but failed nine SQLite native-runtime checks. The isolated real PostgreSQL VideoAnalyticsIntegrationTest and two AdminRevenueTest unit checks passed; the new timestamp assertion was corrected to PostgreSQL precision before the final rerun. Commands: :app:testDebugUnitTest; :server:test --tests *VideoAnalyticsIntegrationTest --tests *AdminRevenueTest :server:installDist. No live R2 test was executed for this increment. Backend release 20261107-service-health deployed successfully after a validated pg_dump backup. Live checks verified HTTPS, fresh processor heartbeat, backup marker, database counters, no-store, unauthenticated rejection and non-admin denial. Physical-device UI acceptance and Google Play upload remain pending; builds and server checks do not replace them. See workspace outputs/v16-server-verification.log and outputs/v16-backend-verification.jpg.


Owner acceptance follow-up, 7 November 2026: the owner reported checking version 16 and confirmed everything was under control. This is owner-reported phone verification, not a new agent device test. Play upload/track/publication status still requires separate Console verification.
