# Explicit publication and in-app sharing

Approved 26 October 2026, after the user reported successful owner playback, sound, pause/resume, seeking and reopening. Existing upload/processing checks and production gaps remain in earlier audits. This milestone does not enable cloud hosting, monetization, moderation automation or browser playback.

## Behavior and permissions

My Videos → View video → Publish lets the owner choose Public or Unlisted after processing. Existing videos remain unpublished. Public videos appear in authenticated Explore and can be watched by signed-in viewers. Unlisted videos do not appear in discovery; viewers need both normal sign-in and a valid video-scoped link. Private/unpublished videos remain owner-only. My Videos and the upload queue continue to show only the current account's own records.

Stop sharing clears publication and rotates the link generation without deleting originals or HLS. Republishing or changing visibility also rotates the generation. Repeating a publish of the same active visibility preserves existing links. A new Share press does not invalidate earlier unexpired links of the current generation. Capabilities expire after 30 days and are bound to a video, generation, issuer, separate audience and token type. They cannot authenticate API calls. API access/refresh tokens cannot act as share capabilities.

Each new manifest/segment request rechecks the viewer, uploader suspension, explicit publication, visibility and capability where required. Owners retain preview access to processed unpublished content. Withdrawal blocks later authorization checks; already delivered/buffered bytes and requests already authorized cannot be recalled. This is access control, not DRM.

## Schema and API

V3__explicit_publication.sql adds is_published (false by default), share_version and a partial discovery index. Existing V1/V2 files and their checksums are unchanged. No user record, upload, original, output or published metadata is reset. The existing public schema receives V3 only when the updated backend/processor next starts; verification applies it in fresh isolated test schemas.

All routes require the existing access JWT:

- GET /api/v1/videos/mine: existing owner library, now including publication flag.
- GET /api/v1/videos/discover?limit=20&cursor=...: 1–50 items, stable published-time/UUID keyset pagination. Only explicitly published, processed PUBLIC videos from active uploaders.
- GET /api/v1/videos/{id}/details: metadata subject to the same viewer rules.
- POST /api/v1/videos/{id}/publish with visibility PUBLIC or UNLISTED: owner-only, requires processed media and a readable validated private master.
- POST /api/v1/videos/{id}/unpublish: owner-only withdrawal and generation rotation.
- POST /api/v1/videos/{id}/share-link: owner-only creation for a currently published video.
- GET /api/v1/videos/{id}/playback/{asset...}: existing bounded private HLS delivery, now applying explicit publication/capability rules.

Unlisted viewers use X-Vidoora-Share on details and every media request, in addition to their Authorization header. No capability is embedded in playlist/media URLs or navigation arguments. The API uses no-store and the client redacts both authorization headers and excludes share-link requests from HTTP logging. R2 remains private; the upload limit remains 1 GiB. Delivery still proxies through the backend with its existing bandwidth/memory/latency limits.

## Android links and session behavior

Public development link: vidoora://watch/<videoUUID>. Unlisted links carry a capability in the URI fragment, which is parsed into memory and then sent only as a header. Do not paste an unlisted capability into chat or record it in logs. The server does not store raw capabilities; it stores only the generation UUID.

The system sharesheet sends links on an explicit user action. MainActivity supports cold and singleTop/warm VIEW intents. Navigation waits through splash/login/register before opening details. A pending link is accepted after successful sign-in; consumed Intent data is cleared. Explore → Open link lets the user paste a link when another app does not make custom schemes clickable. Incoming links are validated for scheme/host, UUID, size, absence of query/userinfo/port, and capability shape.

Capability memory is bounded, video-specific, not written to preferences/saved navigation state, and cleared on login/account change/logout. Process death can require reopening an unlisted link. A revoked/unavailable details response clears stale Watch controls. Private credentials are never sent to R2 by the playback client. No automatic sharing message is sent by the agent.

These are development custom-scheme links, not verified HTTPS App Links or an install fallback. Another app can register the same scheme; recipients need Vidoora installed and configured to reach the same backend. The current laptop/USB loopback configuration does not make the app internet reachable. Cloud hosting, a controlled HTTPS domain, verified App Links and install handling need separate setup before unrestricted distribution. See the [Android deep-link guide](https://developer.android.com/training/app-links/create-deeplinks) and [sharesheet guide](https://developer.android.com/develop/ui/compose/sharing/send).

## Files changed

- server/src/main/resources/db/migration/V3__explicit_publication.sql: fail-closed explicit publication and generation fields/index.
- server/modules/video/VideoTables.kt: maps the additive fields; per-insert UUID generation.
- server/modules/playback/PlaybackService.kt: centralized read access, owner publication/withdrawal, scoped link issue, metadata and keyset discovery.
- server/modules/playback/VideoShareTokens.kt and PlaybackRoutes.kt: separate capability verification and authenticated routes.
- server/Application.kt: injects the capability signer using the existing secret; no new credential setup.
- server/tests/PlaybackSecurityTest.kt: explicit publication, unlisted scope/expiry/revocation, owner checks, suspension, pagination and share-token/API separation.
- server/tests/LiveProcessingIntegrationTest.kt: synthetic real PG/R2 publication, discovery, unlisted reads and revocation, isolated from user uploads.
- Android core/network/VideoShareAccess.kt and KtorClientFactory.kt: validated links, bounded memory and safe header/log handling.
- Android AuthRepository.kt: clears capability memory on account changes/logout.
- Android feature/playback: details/explore state and screens, bounded player header support, publication status, stale access clearing and sharing events.
- Android HomeScreen/NavGraph/MainActivity/AndroidManifest: Explore/details navigation and guarded incoming intents.
- Android tests: parser/isolation, capability-header transport and revoked-details access checks.
- AGENTS.md, master plan, README and audit docs: latest approval scope and observed evidence.

## Verification environment

Original source stays in F:/app/app/Vidoora. To work around the recorded Windows Java 25/sandbox canonical-directory behavior, verification uses byte-matched source/resource copies, the same Gradle 9.6/Kotlin/dependency versions and Java 17. Android uses outputs/phase5-verification (whole-project copy with local SDK/daemon criteria only); backend uses outputs/phase4-verification (server-only settings). Production project daemon criteria/SDK selection/signing configuration were not changed. The test-only init script selects the known cached API 28 instrumented SDK and writable F: home/temp paths for Robolectric. These adaptations do not make Robolectric a device test.

Commands actually run:

```powershell
# In outputs/phase5-verification:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
# Backend private credential loader, with explicit real FFmpeg/PG/live processing opt-ins:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File outputs/Run-Phase4Verification.ps1 -Tests
```

Backend integration creates fresh schemas, synthetic originals and unique R2 attempt prefixes; cleanup is restricted to those fixtures. It neither publishes existing user videos nor resets public tables. H2 permission/route tests are separate from the real PostgreSQL/R2 fixture. Final JUnit results and source hashes determine the executed pass/fail/skip totals. Earlier compile/test failures are not counted as passes.

## Phone acceptance — pending

Restart Start-Vidoora.cmd to load V3/new routes, wait for Responding at ...8080, then Sync/Run from the existing Android Studio project without uninstalling or clearing data. Keep the backend/USB reverse connection active.

1. As uploader A, My Videos → View video → Publish → Public. Confirm it is visible in Explore to account B and B can watch.
2. Stop sharing as A, then reopen/refresh as B: fresh details/media access must fail; owner preview must still work.
3. Publish Unlisted as A, use Share, then open/paste that link as B. It must play but remain absent from Explore. Without the capability, B must not gain access just from knowing the video UUID.
4. Stop and republish Unlisted: old links fail, newly issued links work. Check cold/warm intent handling and sign-in continuation. Never paste a real unlisted link in chat.
5. Confirm private/unprocessed videos stay restricted, account upload queues remain isolated, and upload/processing/owner playback still work.

Production work remains: controlled hosting/HTTPS and verified domain links, abuse/rate/quota controls, moderation/reporting, broader real device/media/interruption/load tests, secret/session revocation design, delivery costs and operational backups. No views/earnings are fabricated.

### Executed final results — 26 October 2026

- Android: 37 passed, 0 skipped, 0 failures/errors. Includes the 32 earlier checks, 3 link parser/memory checks, 1 capability-header check and 1 asynchronous details-revocation check. Debug APK packaging succeeded. A failed intermediate details test was corrected to await the response; no failing check was skipped to obtain the final result.
- Backend: 42 passed, 1 skipped, 0 failures/errors. PlaybackSecurityTest now has 14 permission/route/pagination/capability checks. The real PostgreSQL/R2/FFmpeg fixture executed, applied V1–V3 to its fresh schema, published the synthetic video publicly, verified a second fixture user's discovery/master read, withdrew it, tested unlisted access and generation revocation, downloaded/decoded HLS and cleaned its own data. The older LiveR2IntegrationTest was not opted in during this run and is the skipped test; its previous historical pass is not counted again.
- The final verification source/resource files were compared byte-for-byte with app/src and server/src. V1/V2 migration hashes match the pre-change backup. No production daemon criteria, developer SDK path, signing configuration or credential file was changed. An existing debug APK's signature compatibility with every installed device is not assumed; use the existing Android Studio signing setup for an update and do not uninstall/clear data.
- A separate read-only JDBC query found the current local public schema still at V2. It was not migrated or published by the fixture tests. The next updated API/processor startup applies V3 through Flyway. The user must explicitly select Publish for any real video.
- No new physical-device publication, link-intent, sharesheet or viewer playback check was executed by the agent. These remain the next manual acceptance steps. No cloud service/domain was provisioned, no public bucket policy changed, and no messages were sent to other users.

The verified APK is saved as outputs/Vidoora-publishing-debug.apk; the full report is also copied to the chat outputs directory. Source backups are under outputs/publishing-before. No Git commit or public-release claim is made.
