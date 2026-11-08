# Link-only sharing privacy correction

Approved 26 October 2026 after the user clarified personal storage and sharing only by a complete video link. This supersedes the earlier public-discovery portion of phase-6-publication-sharing.md. Reported pasted-link playback in a second account and Stop sharing denial are historical user observations; the corrected UI/migration still needs a phone check.

## Final policy

- My Videos and upload queues remain owner-only. New uploads default to PRIVATE in the Android request/form, server request/model and PostgreSQL column default.
- The owner can preview processed private content without a link. Enable link sharing opts a processed video into UNLISTED capability sharing; no Public option exists.
- A non-owner must sign in and present a valid video/version-scoped capability on details and every HLS request. Knowing a UUID or having an old ID-only link never grants access. Legacy PUBLIC rows are denied even independently of the migration.
- Every newly issued shared link contains its scoped capability. Stop sharing rotates the generation and blocks later viewer requests, while preserving originals/HLS and owner preview. Existing active UNLISTED generations remain valid across the migration.
- There is no public list in the app. Home → Open video link accepts the complete pasted link, including the fragment after #. The old authenticated discover endpoint returns an empty page for old-client compatibility and never queries/returns video metadata.

Signed-in capability sharing is not recipient-specific DRM: a person with a complete link can forward it to another signed-in user. Previously delivered/buffered bytes cannot be recalled. R2 remains private; backend delivery and its existing limits remain. The 1 GiB upload limit is unchanged.

## Cutover and migration

V4__link_only_privacy.sql changes the database visibility default to PRIVATE, converts legacy PUBLIC records to private/unshared, clears their publication timestamp and rotates their generation. It drops only the obsolete discovery index. It does not delete any users, videos, sessions, jobs, originals or HLS objects. UNLISTED sharing state and generations are untouched. Existing V1/V2/V3 migration scripts are not modified.

The backend rejects new PUBLIC upload requests and PUBLIC publish requests; it does not silently claim they succeeded. Old app versions must be updated for private-by-default uploads. The existing /publish and published/is_published field names are retained for DTO compatibility but now mean enabling link sharing, never listing content publicly. Only UNLISTED can be enabled. The /share-link route cannot issue ID-only public links.

Restart the updated backend to apply V4 and load the new permission rules; an old running JVM does not adopt source edits automatically. Then Sync/Run the app from the existing Android Studio checkout, without uninstalling or clearing data. Keep the laptop/backend and USB mapping active. No cloud/domain/bucket-policy configuration is changed.

## Files and rationale

- PlaybackService.kt: removes PUBLIC viewer grant and discovery querying, rejects Public activation and public share links; retains capability checks, owner checks and revocation.
- V4__link_only_privacy.sql: withdraws legacy exposure, preserves active UNLISTED links and changes the default.
- VideoTables.kt, UploadService.kt and UploadDtos.kt: private defaults and no new public upload acceptance.
- UploadFormScreen.kt: explains private upload and later link sharing instead of offering public visibility.
- PublishingScreens.kt/PublishingViewModels.kt: private/shared wording, Enable link sharing, Share, Stop sharing and a dedicated link-entry screen; removes discovery state/loading/pagination and the public choice.
- HomeScreen.kt/NavGraph.kt/VideoLibraryScreen.kt: direct Open video link navigation and owner-only private/shared status.
- PlaybackSecurityTest.kt: legacy-ID denial, no public activation, empty retired discovery, signed links, owner controls and suspension/revocation.
- LiveProcessingIntegrationTest.kt: real PG/R2 fixture rejects Public and UUID-only reads, then verifies complete-link playback and revocation.
- LinkOnlyMigrationIntegrationTest.kt: a fresh PostgreSQL schema first runs V1–V3 with legacy Public/Unlisted/Private fixtures, then V4; checks public withdrawal, unchanged Unlisted generation/state, preserved records/source references and a new private default.
- VideoShareLinkTest.kt: private upload default, alongside existing parser/isolation/transport checks.
- AGENTS.md, master plan and README: latest direction explicitly overrides public-discovery planning. Previous audit results remain historical.

## Verification

Use the established byte-matched source/resource copies and unchanged dependency versions. Android verification uses Gradle 9.6 / Java 17 and the existing SDK with the documented test-only F: home/temp and cached API 28 instrumented SDK; source checkout daemon criteria and developer SDK paths are unchanged. Legacy Robolectric SQLite coverage is not a device test.

```powershell
# In outputs/phase5-verification:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
# Loads credentials privately for the isolated PG/R2/FFmpeg tests:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File outputs/Run-Phase4Verification.ps1 -Tests
```

Final completed reports determine the pass/fail/skip counts. The PostgreSQL migration test only creates/removes its own validated generated schema; it never resets or migrates the local public schema. The live R2 test uses synthetic objects and deletes only its own fixture keys. No private capabilities or credentials are printed or embedded in files.

## Phone checks after updating

1. Confirm Home has My Videos and Open video link, with no Explore/Public option. Upload a small file and check its private default.
2. As uploader, open a processed video → Enable link sharing → Share. Paste the complete new link as a second signed-in account: it should play. Remove the capability after # and try again: it should fail for that account.
3. Stop sharing and retry a fresh open as the recipient: unavailable. The uploader must still be able to Watch from My Videos.
4. Previously public ID-only links must fail for other accounts; the owner can enable link sharing and issue a new complete link. Existing active Unlisted links should continue working unless stopped/expired.

Do not paste complete capability-bearing links into chat. Report only the status. Clickable HTTPS App Links and laptop-independent hosting remain deferred by the user's earlier choice. Broader device, expiry, storage/load/cleanup and public-launch safeguards remain pending in earlier reports.

### Executed results — 26 October 2026

Android: 38 passed, 0 skipped, 0 failures/errors; debug APK built. Backend: 43 passed, 1 skipped, 0 failures/errors. The skipped suite is the older opt-in live multipart-upload harness, not the executed live processing/privacy checks.

Real PostgreSQL 16.15 verification executed the dedicated V3→V4 legacy-privacy migration test and fresh-schema V1–V4 processing tests. It confirmed Public withdrawal/default change and preservation of active Unlisted generations/source references/record count. The real R2/PG/FFmpeg fixture rejected Public activation and UUID-only viewer access, then verified complete-link access and generation revocation. H2 service/route tests separately cover legacy-row denial, empty discovery, ownership, suspension, expiry and API-token separation. Android checks include the private upload default and the existing link/header/session-isolation and revocation behavior. These are not new phone UI results.

All tested app/src and server/src files were hash-compared with the repository. V1–V3 match their pre-change backup. Verification used the exact commands/adaptations above; no whole original-checkout Java 25 build or connected device test is claimed. No live public-schema migration or real user video modification was executed by these tests. Restarting the API is required to enforce the new policy and apply V4 in the development database.

The link-only APK is saved under outputs/Vidoora-link-only-debug.apk. Source backups are under outputs/link-only-before. Update through the existing Android Studio signing setup; do not uninstall or clear app data. No Git commit, bucket-publication change, cloud hosting or external message was performed.
