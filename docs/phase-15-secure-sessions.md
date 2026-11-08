# Phase 15 — secure creator refresh sessions

Approved 27 October 2026 after the user reported guest/creator login, playback, landscape fullscreen and double-tap seeking work on the phone. Scope: persisted single-use refresh rotation, current-login server logout and Android coordination/offline revocation. Guest sharing and existing content remain intact.

## Server behavior

V10 adds `refresh_sessions` with UUID session/user IDs, SHA-256 hash of the current refresh token, creation/absolute expiry, last-use and revocation timestamps. No plaintext refresh token is stored in PostgreSQL. V1–V9 are unchanged. Registration creates its user/session in one transaction; login creates an independent login session. Expired session metadata for that user is removed on a later successful login; user/video records are unaffected.

Refresh JWTs carry type/issuer/audience/subject, a random session ID and random JWT ID. A session lasts at most the existing 30 days; rotations do not extend the original deadline. A row lock serializes refresh and logout. Refresh verifies the signature/type/expiry, session owner/current-token hash/revocation/database deadline and account suspension, then replaces the stored hash and issues a new pair atomically. A used token cannot renew again. Stale-token use is rejected rather than revoking other valid login sessions or all devices. There is no rotation replay/grace window: losing a successful refresh response requires reauthentication.

`POST /api/v1/auth/logout` receives `{refreshToken: ...}` over the existing API transport, with no token in its URL. A valid generation can revoke only its own subject-bound session, including after rotation. It cannot select another user/session in request fields. Repeated/invalid/expired/legacy logouts acknowledge idempotently; such tokens cannot renew anyway. No access/guest sharing token can become a refresh token. Auth responses use `Cache-Control: no-store`; invalid refreshes return generic 401 SESSION_INVALID. Older stateless refresh tokens are not adopted automatically and cannot renew.

**Access tokens retain their 15-minute lifetime.** Revocation stops renewal; already-issued access JWTs are not checked against session rows and may remain valid until expiry. Offline local sign-out cannot immediately notify an unreachable server. There is no all-devices logout, refresh reuse alarm, password reset, rate limiting, deployment or broader security-readiness claim in this scope.

## Android behavior

A shared mutex coordinates every refresh and repository logout/token replacement. A separate repository mutex serializes login/register/restore/logout and Room writes so a late login/restore cannot undo logout; internal restoration cleanup does not reenter its public lock. New token pairs are committed to encrypted preferences before use. A second client with the same failed access token uses the first client's newly stored pair rather than replaying the old refresh. The refresh decision uses the token actually sent on the failed request rather than a stale provider-cache token, and foreign/auth-endpoint challenges never trigger renewal. Sign-in decoder/network failures use safe generic messages instead of exposing response fragments. Temporary network/server failures preserve credentials; terminal refresh rejection ends the local session and queues revocation. Creator screens observe token changes and stop exposing cached-user controls after rejection.

A final-send guard checks stored identity on every actual HTTP send. Ktor's cached bearer credentials are replaced from the phone's current stored session, including after a late cache write following clearToken. A signed-in user is not silently downgraded to guest, preserving account-based block preferences. Signed-out requests carry no bearer token. Authorization is removed from auth endpoints and foreign origins; sharing capabilities are stripped from foreign origins. This does not recall requests already sent. Guest same-origin sharing headers still work without bearer credentials.

Logout pauses recoverable uploads using the existing flow, atomically commits encrypted revocation material while removing active tokens, clears bearer/share caches and local user state, then schedules a connected WorkManager retry. The worker reads secrets from encrypted preferences, never WorkManager input data. It acknowledges only the exact pending token after an accepted server response and never clears a newer login. Flush batches are bounded to 20, each request has a five-second timeout, and retry uses exponential backoff. APPEND_OR_REPLACE avoids losing a new logout while a preceding work item finishes. Startup reschedules remaining pending work. Android force-stop delays it until the app is reopened.

Profile shows logout progress/error handling. If server acknowledgement is unavailable, the user sees that the phone is signed out and server sign-out will finish when connected. No false server-completion claim is made. Storage write failures are surfaced for retry. Tests explicitly inject ordinary preferences; production injection always uses EncryptedSharedPreferences and has no plaintext fallback.

## Changed files

Server `server/src/main/`:

- `resources/db/migration/V10__refresh_sessions.sql`
- `kotlin/com/vidoora/backend/modules/auth/RefreshSessionsTable.kt` (new)
- `.../auth/AuthService.kt`, `JwtConfig.kt`, `AuthRoutes.kt`
- `.../infrastructure/DatabaseFactory.kt` (explicit H2 test schema)
- `.../plugins/StatusPages.kt` (generic session rejection)

Android `app/src/main/java/com/example/vidoora/`:

- `core/security/SecureStorageManager.kt`
- new `core/security/SessionRefreshCoordinator.kt`, `SessionRequestGuard.kt`, `SessionRevocations.kt`
- `core/network/KtorClientFactory.kt`
- `core/data/repository/AuthRepository.kt`
- `feature/profile/ProfileViewModel.kt`, `ProfileScreen.kt`
- `ui/navigation/NavGraph.kt`

Tests: new server `AuthSessionSecurityTest.kt`, `AuthSessionPostgresIntegrationTest.kt`; new Android `SessionSecurityTest.kt`. README, AGENTS and master plan updated. No credentials/dependencies changed and no existing user upload was modified or deleted.

## Verification

Backend final result: 72 passed, 4 skipped, zero failures/errors, including real isolated PostgreSQL session tests. Android final result: 76 passed, zero skips/failures/errors; debug APK built. App/server source hashes match the verification copies; V1–V9 migration hashes are unchanged. Initial compilation caught an unsupported wildcard import from Exposed's SQL expression object; it was changed to an explicit import. Two missing imports in the new Android test file were also corrected before the passing run. No failed compile is counted as a test pass. The four skipped backend tests are the opt-in FFmpeg/live R2 suites, not the new PostgreSQL session check.

The server suite covers single-use tokens, same-second uniqueness, absolute expiry, hash-only storage, parallel refresh winners, logout/rotation fencing, independent sessions/accounts, legacy/invalid/wrong-type/wrong-signature/expired/suspended rejection and no-store HTTP routes. The opt-in PostgreSQL fixture applies migrations in a generated validated isolated schema and exercises concurrent locks plus a fresh-service persistence check. It removes only its own fixture schema.

Android tests use Robolectric API 28/mock HTTP, explicitly injected preferences and coroutine synchronization. They cover one rotation for concurrent clients, logout waiting for refresh, no token resurrection, temporary-versus-terminal failure, durable pending logout across a reconstructed store, retry without clearing a newer login, actual-send protection against stale cached/foreign credentials, a real repository/Room late-login-versus-logout race, and renewal from the actually sent identity when the plugin cache is stale. These are not hardware Keystore, real phone or live R2 checks. The review APK is saved as outputs/Vidoora-secure-sessions-debug.apk; use the existing Android Studio project for the installed signing identity.

Actual source-matching Java 17 verification commands (approved outside the Windows sandbox to avoid known Gradle cache read failures):

```powershell
# outputs: loads only the encrypted local DB password and restores process environment afterward.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Run-LibraryVerification.ps1
# outputs/phase5-verification, Java 17:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Equivalent normal-project commands are `./gradlew.bat :server:test` and `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest`, with its configured SDK/toolchains. Real PostgreSQL requires the existing private environment setup. No regular API restart/normal V10 migration, phone installation, live R2 or live network session check is claimed for this increment.

## Phone acceptance

1. Restart Start-Vidoora so normal Flyway applies additive V10; Sync and Run the existing Android Studio project without clearing app data. The API must be reachable through the current USB reverse setup. Existing access tokens may still work until expiry, but old stateless refresh tokens cannot renew; log out and sign in once to start a new session.
2. Log in as a creator, open My Videos/folders/queue and play a video. Close/reopen the app; the session should restore. Ordinary expiry should renew automatically; no token values/screenshots are needed.
3. Log out online. The app should return to Home, hide creator data and keep guest pasted-link playback working. Log back in and verify account isolation and paused-upload recovery as applicable.
4. Log out while the API is unreachable/offline. Confirm the phone signs out with pending-server notice, then restore connectivity/API availability and reopen the app if force-stopped. The pending worker should finish without affecting any newer login. Automatic backend tests verify revocation persistence; UI appearance alone is not evidence of a server response.
5. Keep the continuous processor running for new uploads. Session changes do not make processing run while the laptop is off.

Device acceptance, delayed cleanup observation from older phases, remaining upload/load checks and hosting remain separate pending work.



