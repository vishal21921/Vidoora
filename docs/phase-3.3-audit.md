# Vidoora Phase 3.3 audit and verification

Initial audit: 12 October 2026. Integration evidence updated: 14 October 2026. Project: F:\app\app\Vidoora. Scope: existing upload reliability, tests and documentation. The 1 GiB upload limit is unchanged. Phase 4 has not been implemented.

## 1. Actual architecture

The project has an Android app module and a single Kotlin/Ktor backend module. Android uses Compose/Material 3, navigation, MVVM, Hilt, Ktor, encrypted preferences, Room and WorkManager. The backend uses JWT, BCrypt cost 12, Exposed, HikariCP, Flyway/PostgreSQL and S3-compatible R2 storage. V1 defines all 13 reported tables; Exposed currently implements users, videos, upload sessions and processing jobs. No database migration checksum was changed.

The Android home screen is a welcome/upload entry screen, not a feed. Media3 is declared but there is no player. Upload completion queues an existing processing-job record; there is no FFmpeg/HLS processor, moderation pipeline, monetization or payout implementation. The root feature/auth/login/LoginScreen.kt is an unused duplicate outside the Android source set. See README.md for the full flow, endpoint/configuration details and development commands.

## 2. Differences from the handover

| Reported behavior | Actual code at audit start |
| --- | --- |
| Durable source draft | Copy existed, but under cacheDir, which Android can evict |
| Completion part ordering | Android sorted tags; backend/storage did not ensure ordering or complete part coverage |
| DEF-03 refresh bounds fixed | No bounds check existed |
| DEF-04 seek optimization fixed | RandomAccessFile.seek/readFully existed for local files |
| Upload ownership/idempotency | Account checks existed, but session-to-video binding was missing; status exposed any authenticated user's uploads; completion and job creation were separate transactions |
| Working background worker | Hilt plugin/factory initialization and foreground-service configuration were missing |
| Real integration tested | Mock mode merely returned URLs/success; it did not upload or store bytes |
| Passing Android builds/tests | Could not be accepted as evidence: Kotlin compilation configuration was incomplete and the source contained an invalid encrypted-preferences enum |
| UploadRepository tests | Existing class primarily checked DTOs and constants, not repository/worker behavior |

The old backend also silently attempted H2 after any PostgreSQL/migration failure, hardcoded development secrets, and mapped JSONB completed_parts as text. Android used a plaintext token fallback, cached bearer tokens across logout, trimmed passwords, replaced unique work, fabricated missing ETags, and marked cancellation successful even if the backend request failed.

## 3. Confirmed defects addressed

- Persistent private draft directory; free-space reserve, bounded streaming copy, size validation, fsync, and cleanup on unsuccessful preparation. New drafts survive cache eviction and ordinary process death.
- Byte-range helper validates the 1 GiB bound and minimum multipart chunk size. Backend validates visibility, part counts, duplicates, complete coverage and ETags.
- Backend checks the user, suspension state, owner and exact video/session pairing for multipart mutations. Upload status is owner-only.
- Provider ListParts validates actual part sizes/ETags before completion; ascending order is enforced. HEAD reconciles a lost completion response or failed DB commit. Duplicate/concurrent completion queues one job under a row lock; missing legacy jobs can be repaired.
- Invalid/expired/aborted/completed-session transitions are rejected. Expired sessions are swept at startup/hourly; provider failures are retried by a later sweep. Bucket lifecycle is required for crashes before a session row is committed.
- Android tasks have explicit owners. Room 2-to-3 migration preserves data and quarantines ownerless legacy uploads. Queue filtering and worker checks prevent the next account inheriting old tasks. No destructive migration fallback remains.
- WorkManager Hilt factory, unique KEEP work, foreground service type/permissions and per-task notifications are configured. Account logout stops work before token/cache replacement.
- Pause/cancellation use conditional persisted transitions. Cancellation retains its draft until backend acknowledgement and remains retryable. Worker coroutine cancellation propagates without overwriting PAUSED/CANCELLING. Transient failures back off; automatic retries are bounded.
- Real ETags are preserved. Uncertain PUT outcomes are reconciled by uploading the same immutable range to the same part number. Successful Room parts survive new worker instances.
- R2 PUTs use a separate client without bearer tokens. Auth cache is cleared on session changes; refresh clients are closed, transient refresh failures retain tokens, and stale refresh responses cannot replace a newer session. Password whitespace is preserved. Token/file/provider work executes on IO dispatchers.
- Production startup requires configured JWT/database secrets and fails on PostgreSQL/migration errors. Mock storage/H2 are explicitly selected in tests. Internal exception details are hidden from API responses. Plaintext token fallback is removed, and backup/device-transfer exclusions protect tokens and drafts.

## 4. Build and test results

Final command executed:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :server:test '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

Result: BUILD SUCCESSFUL. Debug APK built successfully.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| Android existing DTO/ViewModel/basic tests | 9 | 0 |
| MultipartPlanTest | 8 | 0 |
| RoomMigrationTest | 2 | 0 |
| UploadWorkerTest | 9 | 0 |
| Backend AuthAndUploadSecurityTest | 2 | 0 |
| Backend UploadValidationTest | 8 | 0 |
| Backend UploadReliabilityTest | 6 | 0 |
| LiveR2IntegrationTest | 0 | 1 |
| Total | 44 | 1 |

Room tests opened actual migrated SQLite databases (1-to-3 and 2-to-3), validated Room schema, preserved user/part data and checked ownerless-task isolation. Worker tests executed the real worker with Room and mock transports: exact byte payload/ETag, resume after transient PUT failure, already-successful parts after a new worker instance, paused/foreign-account protection, missing ETag, backend-acknowledged cancellation, cancellation propagation, bounded/permanent errors and unavailable draft storage. Backend tests exercised authorization, binding/bounds, concurrent completion, missing/wrong provider parts, lost completion response, duplicate cancellation and expiry cleanup.

Environment notes: Java initially was not on PATH. Android Studio JBR 25 was used to run Gradle, with Java 17 toolchains for both modules. Windows sandbox canonical-path resolution failed on SDK JARs under C:, including a scratch copy on C:. A byte-identical verification SDK copy under the project's ignored build/ directory on F: allowed compilation. local.properties was temporarily pointed at that copy and restored byte-for-byte afterwards. No installed SDK configuration was permanently redirected.

Robolectric's native SQLite loader failed on this environment; the two database/worker test classes explicitly select supported legacy SQLite mode on API 28. That mode is deprecated and does not replace device/native-runtime testing. Exposed/dependency deprecation warnings remain; no tests failed in the final run. Test reports were inspected, not inferred from a successful compile.

## 5. Live integration verification

Updated 14 October 2026. The automated results in section 4 remain the historical 12 October run: 44 passed and one live test skipped. No full suite was rerun for this documentation update.

### Executed live R2 harness

The saved JUnit report at `server/build/test-results/test/TEST-com.vidoora.backend.LiveR2IntegrationTest.xml` was inspected: tests=1, skipped=0, failures=0, errors=0, timestamp `2026-10-13T03:41:06.259Z`, duration 19.65 seconds. This is an executed live test, separate from the earlier skipped run.

The harness uses real Ktor routes and R2, but H2 for the database. It uploads a synthetic two-part fixture, submits reversed completion ordering, repeats completion, downloads and compares SHA-256, and cancels another multipart session. Its finally block attempts abort of its own sessions and deletes only its own object keys. An independent post-run listing for the harness itself has not been recorded. A separate manual cancellation cleanup check and bucket lifecycle configuration are recorded below.

### Real PostgreSQL and Android-to-R2 evidence

User-provided startup logs show PostgreSQL 16.15 at `vidoora_db`, successful V1 migration and subsequent validation with no pending migration. SQL confirmed the connection as the limited `vidoora` user. This is real startup/migration and upload transaction evidence, not a dedicated PostgreSQL automated test suite or a concurrency/failure-injection result.

The Android test device is a physical Realme RMX3370. The current Gradle property points to `http://127.0.0.1:8080/`; USB development requires `adb reverse tcp:8080 tcp:8080` and the laptop backend running. Reconnect/reapply reverse after a device or laptop restart. Hosting independently of the laptop was discussed and deferred by the user.

| Check | Evidence and result |
| --- | --- |
| Registration/login | User reached the home screen after restoring the USB backend connection |
| Small public upload | SQL for `listeno`: PUBLIC, UPLOADED, COMPLETED, 1,060,926 bytes, processing QUEUED |
| Multipart upload | Screenshot for `first`: 39.3 MB, 8/8 parts, 100%, UPLOADED. SQL: UPLOADED, COMPLETED, 41,194,145 bytes, processing QUEUED. User confirmed matching object in the dedicated R2 test bucket |
| Pause/resume | User reported successful completion after resume |
| Cancellation | SQL for `ffc` shows CANCELLED / ABORTED. User found no completed object at the exact key. The read-only cleanup script returned zero unfinished multipart uploads for that key at 2026-10-14 12:06:43 UTC (17:36:43 IST). This proves absence at check time, not the precise time/cause of removal. Local draft removal remains unverified |
| Close/reopen recovery | User reported a resume option after reopening and subsequent 100% / UPLOADED; distinct controlled force-stop test recorded below |
| Background upload | User reported completion after leaving the app in the background; notification permission/restriction matrix not tested |
| Account isolation | User reported second account showing only its own upload, then first account showing its earlier uploads with second-account uploads hidden |
| Network interruption | User reported paused/retrying state when internet was disabled and automatic recovery/completion after internet returned |

| Android force-stop | After following pause, Settings Force stop, reopen and resume instructions, user reported 100% / UPLOADED. Active unpaused process-kill recovery remains untested |
| Backend restart | User paused an upload, restarted the backend and confirmed resume from saved progress followed by 100% / UPLOADED |
| Phone restart | User followed pause, phone reboot, USB reconnection/backend restart and resume instructions and reported successful completion |
| Offline cancellation | Initial internet-off check still had USB backend access. In a separate follow-up, user stopped the backend, tapped Cancel, restarted it and confirmed CANCELLED. These are manual reported results |
| Oversized selection | User reported the app showed a more-than-1-GB error. Exact file size and absence of backend initiation were not independently captured |
| Near-limit upload | Pending: user has no suitable 900-1,000 MB video |
| Notification progress | Initially absent; after checking phone notification settings, user confirmed a visible notification with changing upload progress. Denied-permission/OS restriction matrix remains untested |
| R2 lifecycle | Screenshot showed enabled whole-bucket default abort rule at seven days, with completed-object deletion unchecked. User confirmed saving a change to one day. Post-save configuration readback and timed automatic expiry execution remain unverified |
Manual device outcomes above are user-reported observations, not agent-executed instrumentation tests. QUEUED processing is expected: FFmpeg/HLS and playback are not implemented. No new build, full test suite or live harness was executed during this documentation update.

## 6. Remaining blockers and risks

- Cancellation cleanup for one key now has SQL and provider-listing evidence. Verify local draft removal, post-save lifecycle configuration, and actual timed failed/expired-session cleanup; do not infer lifecycle execution from a configured rule.
- Paused force-stop and backend restart recovery have user-reported completion evidence. Active unpaused process kill and full device restart subsequently has a user-reported successful result.
- Near-limit upload is explicitly pending for lack of a suitable file. Insufficient local space and long-running URL/session expiry remain untested. Oversized selection rejection and internet-off cancellation have user-reported results; cancellation with the backend unreachable subsequently has a user-reported successful result.
- Verify Android notification/foreground restrictions and supported OS/device behavior; legacy Robolectric tests are not native device instrumentation results.
- Real PostgreSQL concurrency, uncertain completion/transaction recovery and pool occupancy under slow R2 calls remain untested on the live database. Automated coverage uses H2/fake storage.
- Old Room tasks lack trustworthy account ownership and remain quarantined. Old cache drafts cannot be made retrospectively durable.
- Refresh JWTs remain reusable until expiry; logout is local. Revocation/rotation, rate limits, quotas, abuse controls, HTTPS-only release configuration, restricted CORS and observability remain public-launch work.
- Local authenticated-startup cleanup and Android background restrictions apply. CANCELLING can remain pending while signed out or backend unavailable.
- The app currently depends on the laptop backend and USB reverse mapping. Cloud deployment was deferred; laptop-off operation is not supported by the local setup.
- Review Git baseline/secret exclusions before committing; no Git baseline was created or verified during this update. Load/cost testing remains open.

Core integration happy paths and several manual reliability checks now have evidence. Phase 3.3 is not declared fully verified or production-ready; Phase 4 was explicitly approved by the user on 14 October 2026 after review of remaining gaps; implementation is tracked separately in phase-4-processing.md.

## 7. Recommended next steps

1. Check local draft cleanup and read back the saved one-day R2 lifecycle configuration. Timed expiry cleanup still needs isolated test evidence.
2. Test cancellation while the backend is unreachable, active process interruption and full device restart recovery; preserve test drafts and avoid destructive database changes.
3. Run the remaining size/storage/expiry and PostgreSQL failure/concurrency checks with isolated test data; record outcomes separately from H2 results.
4. Review the Git baseline, operating costs and public-launch security requirements.
5. Review the Phase 3.3 evidence and outstanding risks with the user. Begin Phase 4 only after explicit approval.

## 8. Changed files and reasons

Paths below are relative to F:\app\app\Vidoora. The directory was not a Git repository; an original source/configuration backup was taken before editing. Generated build/cache files and the temporary verification SDK are excluded from this inventory.

| File | Change | Reason |
| --- | --- | --- |
| `.env.example` | Added | Safe configuration template, with no credentials |
| `.gitignore` | Modified | Exclude secret files and Kotlin scratch state |
| `app/build.gradle.kts` | Modified | Compatible Kotlin/KSP/Hilt/Room setup, Java 17 toolchain, schema export and tests |
| `app/schemas/com.example.vidoora.core.database.AppDatabase/3.json` | Added | Versioned Room 3 schema |
| `app/src/main/AndroidManifest.xml` | Modified | Hilt worker initialization, foreground service configuration and backup protection |
| `app/src/main/java/com/example/vidoora/core/data/repository/AuthRepository.kt` | Modified | IO token operations, session-aware work recovery/logout and bearer-cache invalidation |
| `app/src/main/java/com/example/vidoora/core/data/repository/UploadRepository.kt` | Modified | Persistent drafts, disk failure handling, atomic queue, account isolation and cleanup |
| `app/src/main/java/com/example/vidoora/core/database/AppDatabase.kt` | Modified | Room migration/owner persistence, conditional states and account-aware recovery |
| `app/src/main/java/com/example/vidoora/core/database/dao/UploadTaskDao.kt` | Modified | Room migration/owner persistence, conditional states and account-aware recovery |
| `app/src/main/java/com/example/vidoora/core/database/dao/UserDao.kt` | Modified | Room migration/owner persistence, conditional states and account-aware recovery |
| `app/src/main/java/com/example/vidoora/core/database/entity/UploadTaskEntity.kt` | Modified | Room migration/owner persistence, conditional states and account-aware recovery |
| `app/src/main/java/com/example/vidoora/core/di/DatabaseModule.kt` | Modified | Room migration/owner persistence, conditional states and account-aware recovery |
| `app/src/main/java/com/example/vidoora/core/di/NetworkModule.kt` | Modified | Separate unauthenticated storage transport |
| `app/src/main/java/com/example/vidoora/core/network/ApiConfig.kt` | Modified | Configurable API base URL |
| `app/src/main/java/com/example/vidoora/core/network/KtorClientFactory.kt` | Modified | Origin-scoped auth, safe refresh retention and close refresh clients |
| `app/src/main/java/com/example/vidoora/core/security/SecureStorageManager.kt` | Modified | Correct encryption enum, fail closed and reject stale token replacement |
| `app/src/main/java/com/example/vidoora/core/work/MultipartPlan.kt` | Added | Validated byte ranges, real ETag handling and retry classification |
| `app/src/main/java/com/example/vidoora/core/work/UploadWorker.kt` | Modified | Resume, owner/state checks, exact PUT bytes, real ETags, safe cancellation and retry |
| `app/src/main/java/com/example/vidoora/feature/auth/AuthViewModel.kt` | Modified | Preserve exact password whitespace |
| `app/src/main/java/com/example/vidoora/feature/home/HomeViewModel.kt` | Modified | Wait for logout completion and clear navigation history |
| `app/src/main/java/com/example/vidoora/feature/profile/ProfileViewModel.kt` | Modified | Wait for logout completion and clear navigation history |
| `app/src/main/java/com/example/vidoora/feature/upload/UploadFormScreen.kt` | Modified | IO metadata reads and clear handled navigation success state |
| `app/src/main/java/com/example/vidoora/feature/upload/UploadQueueScreen.kt` | Modified | Surface control errors and retry pending cancellation |
| `app/src/main/java/com/example/vidoora/feature/upload/UploadViewModel.kt` | Modified | Surface control errors and retry pending cancellation |
| `app/src/main/java/com/example/vidoora/ui/navigation/NavGraph.kt` | Modified | Wait for logout completion and clear navigation history |
| `app/src/main/java/com/example/vidoora/VidooraApplication.kt` | Modified | Hilt worker initialization, foreground service configuration and backup protection |
| `app/src/main/res/xml/backup_rules.xml` | Modified | Exclude tokens, databases and drafts from backup and transfer |
| `app/src/main/res/xml/data_extraction_rules.xml` | Modified | Exclude tokens, databases and drafts from backup and transfer |
| `app/src/test/java/com/example/vidoora/MultipartPlanTest.kt` | Added | Actual byte-range, migration, worker/storage and retry verification |
| `app/src/test/java/com/example/vidoora/RoomMigrationTest.kt` | Added | Actual byte-range, migration, worker/storage and retry verification |
| `app/src/test/java/com/example/vidoora/UploadWorkerTest.kt` | Added | Actual byte-range, migration, worker/storage and retry verification |
| `build.gradle.kts` | Modified | Compatible Kotlin/KSP/Hilt/Room setup, Java 17 toolchain, schema export and tests |
| `docs/phase-3.3-audit.md` | Added | Architecture, secure setup, verification and limitations |
| `gradle.properties` | Modified | Compatible Kotlin/KSP/Hilt/Room setup, Java 17 toolchain, schema export and tests |
| `gradle/libs.versions.toml` | Modified | Compatible Kotlin/KSP/Hilt/Room setup, Java 17 toolchain, schema export and tests |
| `README.md` | Added | Architecture, secure setup, verification and limitations |
| `server/src/main/kotlin/com/vidoora/backend/Application.kt` | Modified | Fail-fast startup and periodic upload-session cleanup |
| `server/src/main/kotlin/com/vidoora/backend/infrastructure/DatabaseFactory.kt` | Modified | Fail-fast PostgreSQL/Flyway and explicitly selected H2 tests |
| `server/src/main/kotlin/com/vidoora/backend/infrastructure/S3StorageService.kt` | Modified | Explicit mock mode, provider parts/HEAD reconciliation, sorted completion and idempotent abort |
| `server/src/main/kotlin/com/vidoora/backend/modules/auth/JwtConfig.kt` | Modified | Require a configured JWT secret |
| `server/src/main/kotlin/com/vidoora/backend/modules/upload/UploadRoutes.kt` | Modified | Pass authenticated owner to status authorization |
| `server/src/main/kotlin/com/vidoora/backend/modules/upload/UploadService.kt` | Modified | Owner/video binding, locked atomic completion, provider validation and expiry recovery |
| `server/src/main/kotlin/com/vidoora/backend/modules/upload/UploadValidation.kt` | Added | Exact multipart coverage, bounds and completion ordering |
| `server/src/main/kotlin/com/vidoora/backend/modules/video/VideoTables.kt` | Modified | Match existing completed_parts JSONB schema |
| `server/src/main/kotlin/com/vidoora/backend/plugins/StatusPages.kt` | Modified | Propagate cancellation and sanitize internal errors |
| `server/src/main/resources/application.conf` | Modified | Required environment secrets and explicit storage/test mode |
| `server/src/test/kotlin/com/vidoora/backend/AuthAndUploadSecurityTest.kt` | Modified | Explicit test environment, authorization/state/recovery tests and opt-in live harness |
| `server/src/test/kotlin/com/vidoora/backend/LiveR2IntegrationTest.kt` | Added | Explicit test environment, authorization/state/recovery tests and opt-in live harness |
| `server/src/test/kotlin/com/vidoora/backend/UploadReliabilityTest.kt` | Added | Explicit test environment, authorization/state/recovery tests and opt-in live harness |
| `server/src/test/kotlin/com/vidoora/backend/UploadValidationTest.kt` | Added | Explicit test environment, authorization/state/recovery tests and opt-in live harness |
| `server/src/test/resources/application.conf` | Added | Explicit test environment, authorization/state/recovery tests and opt-in live harness |




