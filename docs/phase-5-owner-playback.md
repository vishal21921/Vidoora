# Secure Android owner playback

Approved 14 October 2026 after Phase 4 processing checks. The previously approved uploader/processor remains intact. This milestone adds owner playback, not public publication, discovery, sharing, ads, earnings or payouts.

## User flow

Home → My Videos → Watch. The server returns the owner's 50 most recent uploads, with title, processing state, duration and selected visibility. Ready-to-watch rows are PROCESSED; uploaded/processing/failed/cancelled rows explain their state. Refresh reloads the library. Chosen PUBLIC visibility does not publish content; another account cannot watch it in this milestone.

The Media3 player uses the existing 1.4.1 dependency family, adding HLS and player UI modules without a wholesale upgrade. The controller supports pause/seek and HLS adaptation. Player state stores position through ordinary Compose/activity recreation. ON_STOP pauses playback; leaving the screen releases resources. Network/auth/decoder errors show a generic retry action, without provider diagnostics.

## Access and delivery

GET /api/v1/videos/mine and GET /api/v1/videos/{id}/playback/{asset...} require the existing access JWT. Refresh JWTs are rejected as API credentials. The backend checks current user suspension, ownership, PROCESSED state and recorded private output prefix on every media request. A non-owner gets a generic missing-video response, including when the selected visibility is PUBLIC or UNLISTED. Neither raw originals nor arbitrary keys are served.

Allowed asset paths: master.m3u8, a generated rendition/index.m3u8, or its segment_XXXXXX.ts. Path traversal, other files, absolute URLs, external/embedded playlist URIs and non-VOD variant manifests are rejected. Manifests reference only relative paths under the same video route. Responses use no-store; no signed URL, bearer token or storage credential is embedded in playlist text or query parameters. R2 stays private.

Delivery is a backend proxy for this local development milestone. There are four concurrent R2 storage reads, playlists capped at 256 KiB and segments at 16 MiB. Both backend and Android buffer one bounded asset; the Android adapter reads requested byte offsets from that local buffer. HTTP Range is not a server feature here; seeking can download a whole segment again. This improves integration simplicity but adds backend egress, memory and latency, especially before the first frame. It is not a CDN or a scalability guarantee. Define protected direct/CDN delivery before public release.

The Android DataSource only accepts the configured API origin and exact media path grammar. It uses the existing Ktor bearer-auth and refresh handling, never a copied access token or credentialed R2 request. Transfer timing includes the network request. The refresh token's existing server-side revocation limitation remains; this is authenticated access control, not DRM or prevention of recording by an authorized owner.

## Changes

- Backend PlaybackService/PlaybackRoutes: owner library and bounded authenticated HLS reads, safe manifests and errors.
- VideosTable: maps existing V2 processed bucket/master key columns. No V3 migration or database reset.
- S3StorageService: bounded private asset read; Routing/Application: register new routes.
- Android feature/playback: library DTO/state/view model/screen, refresh-capable Media3 DataSource, player view model/screen.
- HomeScreen/NavGraph: My Videos entry and player navigation. Existing auth/upload/profile routes remain.
- app/build.gradle.kts: HLS/UI and lifecycle Compose dependencies, matching existing catalog versions.
- PlaybackSecurityTest: owner isolation, suspended/unprocessed denial, safe paths/manifests and route JWT types/response headers.
- PlaybackDataSourceTest: requested byte ranges, blocked origins/paths, safe unauthorized errors and bounded playlists (Robolectric + mock transport).
- LiveProcessingIntegrationTest: real PostgreSQL/R2 playback manifest read for the isolated generated fixture and foreign-owner denial, without switching the other tests to the live database.
- AGENTS.md/master plan/README and this file: explicit scope and evidence.

## Verification and Windows setup

Android verification uses a whole-project source/configuration copy at outputs/phase5-verification, with only local Java 17 daemon criteria and the existing F: verification SDK path in local.properties. The original project's Java 25 criteria and developer SDK path remain unchanged. Backend verification uses outputs/phase4-verification with source/resource copies and the documented private credential loader. Source hashes are compared before accepting results. No physical-device test is inferred from compilation or Robolectric.

Commands:

```powershell
# Whole-project verification copy:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
# Backend source copy; privately loads real PG/R2 settings and explicit integration opt-ins:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File outputs/Run-Phase4Verification.ps1 -Tests
```

Initial Android build stopped because the existing debug-keystore lock directory lacked sandbox write access. The lock permission was granted; no signing key was regenerated. Final JUnit reports were inspected: Android 32 passed, 0 skipped/failures/errors; backend 35 passed, 1 skipped, 0 failures/errors. Backend includes 7 playback security/route tests and executed real PostgreSQL/R2 fixture playback reads. Android includes 4 new Robolectric/mock media transport tests plus the 28 existing tests. The older live R2 upload harness is the skipped test in this backend run. Physical playback, refresh during long playback and measured quality switching remain unverified.

To test on the phone: restart the API through Start-Vidoora.cmd so new routes are loaded; wait for Responding at ...8080. Connect/authorize USB, restore adb reverse tcp:8080 tcp:8080 if needed. Sync and Run the app from Android Studio, then open My Videos and Watch a processed upload such as ritesh while signed into its uploader account. Keep the API window/laptop running. Do not uninstall the app or clear its data to update it.

Device acceptance remains manual: actual picture/sound, seek, pause, back/reopen, rotation, network recovery/retry, account isolation, and a sufficiently long video to observe token refresh/quality adaptation. No browser playback, public links or background-audio service is added.

### Test environment recovery and signing

Robolectric could not create its user-home lock and then could not create SDK-fetch/native-SQLite directories in the sandbox temporary root. Verification-only init script outputs/phase5-test-home.init.gradle assigns writable test JVM user.home and java.io.tmpdir under ignored F:/app/app/Vidoora/build (native file-backed SQLite still failed under the C: sandbox workspace), enables offline SDK resolution and points to the already cached API 28 instrumented JAR used by the earlier audit. It does not modify production code or the developer's home/settings. The initial failed runs remain failures; only completed subsequent reports count.

Both archived Phase 3.3 and new verification APK signatures validate, but their certificates differ. The existing C:\Users\chauh\.android\debug.keystore was not regenerated (its last-write time predates this work). The installed phone certificate was not inspected; use Android Studio Run from the existing checkout/signing setup for a data-preserving update. Do not uninstall or clear data to work around a signature mismatch. The supplied APK is a verification artifact, not a guaranteed replacement for every earlier signed build.


Final Android test command used the documented verification copy and added `--init-script ..\phase5-test-home.init.gradle` for the sandbox-only test-home/temp/offline-SDK adjustments. It returned BUILD SUCCESSFUL after both migration tests and all upload-worker/media tests ran. APK packaging succeeded earlier; APK signature verification returned exit code 0. Backend final verification helper returned BUILD SUCCESSFUL. Source/resource hashes were compared with the existing repository before accepting these results.

No authorized phone was visible through the available ADB query, so no physical install, rendering, audio or seek result is claimed. Next step is a data-preserving Android Studio update and a manual Watch check with the restarted API. The existing phone login/upload queue must not be cleared.

26 October 2026: the user reported that phone playback, sound, pause/resume, seeking and reopening all worked. This closes the initial manual owner playback check only; it is not an automated/device-matrix or long-token-refresh result. Publication and sharing were subsequently approved and are documented separately.
