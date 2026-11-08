# Phase 17 — local Resume watching

Approved 27 October 2026 after the user reported automatic processing updates work on the phone. Scope: remember playback position locally, offer Resume/Start over, support guests with separate account namespaces and retain current server access enforcement.

## User experience

Opening an accessible video checks the existing details API first. If there is a useful saved checkpoint, playback waits for Resume or Start over. Dismissing the prompt returns without overwriting the previous position. Choosing either option is an explicit play action. No useful record means normal playback from the beginning. Finished/near-finished videos start from the beginning on a later opening.

A useful checkpoint is at least five seconds into the video and more than five seconds from the end. Videos of ten seconds or less therefore do not offer Resume. Unknown, invalid, expired and materially incompatible timelines are ignored. The duration tolerance is the larger of three seconds or five percent; a native-timeline check also prevents seeking into an incompatible/out-of-range saved timeline. Progress metadata is bounded to seven-day durations; this is not a new upload/file-duration limit.

Checkpoints are written about every five seconds while actually playing and on pause, lifecycle stop and disposal; completion clears the checkpoint. Native media must have prepared successfully before any checkpoint is allowed. Unknown timelines do not erase earlier progress. Retry performs a fresh access check and uses the in-session position without repeating a prior choice. A delayed check cannot start playback when the lifecycle is stopped. Fullscreen, double-tap seek, quality and existing speed controls keep the same ExoPlayer instance.

Progress writes run on IO and can finish after the player route is disposed. This remains a best-effort local cache: abrupt OS termination can lose recent seconds or an uncommitted final write. Save/read failures have safe notices and do not become false playback success or force deletion of auth/media data. Native fullscreen/lifecycle/Keystore behavior still requires phone acceptance.

## Privacy and storage

The existing production EncryptedSharedPreferences holds a versioned progress blob. Records contain only numeric position/duration/update time and SHA-256 namespace/video keys. No raw video/account IDs, titles, links, capabilities, playback URLs, access tokens or refresh tokens are added to progress data. Existing token/revocation preferences are unchanged. Tests explicitly inject ordinary preferences to test the data model; production has no plaintext fallback.

There are at most 100 records across this installation. Records older than 30 days are ignored at read time and pruned on later writes. There is no background erasure scheduler, cross-device synchronization, navigation history, offline-video cache or Home Continue-watching list. Clearing/reinstalling app data removes checkpoints; do not clear data to update this release.

Guests use a single device-local guest namespace, distinct from each account UUID. Different people using guest mode on the same installation share that guest namespace; the app cannot identify them individually without login. Current encrypted JWT subject parsing is used only for local partitioning, never authorization. Missing/malformed partial credentials do not get assigned another account's or guest's progress. Ordinary token rotation keeps the same namespace. Account changes rebuild the player scope, while a synchronized storage check rejects writes for an identity that has changed.

Each active progress session also has an in-memory lease. A new player session fences older writers for the same account/video key, preventing an older player from undoing Start over or replacing a newer session's checkpoint. The lease table is bounded separately and is never persisted as an access credential.

## Access checks

The server's fresh details response must allow playback before any saved-position lookup/prompt. A stored position is not permission and is never used to reconstruct a sharing link. Guests still need a complete current link after app restart. Media3 continues to fetch every playlist/segment through the existing private access-controlled backend; a later revocation still fails those requests. Already buffered bytes cannot be recalled, as in earlier phases.

Simulated denied, expired/revoked, missing and not-ready responses never offer a saved-position prompt or replace the prior record. An identity change while the check is in flight also prevents disclosure of the old namespace's position. Metadata requests are single-flight. Save failures/decoder errors use generic notices without response/token fragments.

## Files

Under `app/src/main/java/com/example/vidoora/`:

- `core/security/SecureStorageManager.kt`: guarded progress blob access and safe local identity extraction; no change to existing credential persistence behavior.
- New `feature/playback/WatchProgressStore.kt`: cache policy, encrypted blob model, bounds/TTL, identity/lease fences and asynchronous IO writes.
- New `feature/playback/WatchProgressSession.kt`: fresh-access-first startup, choices, retry, native-readiness gating and safe notices.
- `feature/playback/PlayerViewModel.kt`: progress/identity dependencies and namespace flow.
- `feature/playback/VideoPlayerScreen.kt`: startup prompt, selected start position, checkpoints/completion, timeline/lifecycle guard and existing controls.
- `ui/navigation/NavGraph.kt`: identity-scoped player composition and safe unknown-session state.
- New `app/src/test/java/com/example/vidoora/WatchProgressTest.kt`: 10 focused tests.

README, AGENTS and master plan updated. No server source/migration/dependency/environment changes, no new credentials, no uploaded media modified/deleted.

## Executed verification

**94 Android tests passed, zero skips/failures/errors; debug APK built.** Ten new Robolectric/mock HTTP/store/session tests exercised guest/two-account separation and reconstruction, hashed numeric-only records, 100-record eviction, expiry/corruption recovery, unknown timelines/completion/clear, stale-player and identity fences, access-before-prompt, native-ready-before-save, denied/not-ready cases, Start over/prompt dismissal, fresh retry/duration checks, in-flight account change and single-flight startup. Existing authentication, upload, status, thumbnail and player-control tests remained passing.

The final source tree matches the verification copy in both directions with no extra copied files. Server source remains byte-identical to the Phase 16 verification tree. Backend/PostgreSQL/R2/FFmpeg suites were not rerun for this Android-only increment; earlier counts are historical. No hardware Keystore, Compose dialog instrumentation, physical phone, new upload or live playback result is claimed here.

Actual build/test used the established Java 17/source-matching verification copy and copied SDK/offline Robolectric init script, with approved execution outside the Windows sandbox to avoid known Gradle cache-read failures. Original repository toolchain/dependency settings remain unchanged.

```powershell
# outputs/phase5-verification, existing Java 17 JAVA_HOME:
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Normal-project equivalent: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` with its configured SDK/toolchains. Review APK: outputs/Vidoora-resume-watching-debug.apk; update through the existing Android Studio project to preserve the installed signing identity/data. Backup: outputs/watch-progress-before/app-src.

## Phone acceptance

1. Keep the existing backend reachable and Sync/Run Android Studio without uninstalling/clearing data. No backend restart, SQL Shell or new credentials are needed for this increment.
2. Use a video longer than 30 seconds. Watch at least 10 seconds, pause and press Back. Reopen it through My Videos or a valid pasted sharing link. Confirm the time prompt appears and Resume continues near that position.
3. Repeat and choose Start over. It should start at zero. Finish the video, leave and reopen: it should start at zero without a stale near-end prompt. Short videos/positions inside the five-second boundaries intentionally do not prompt.
4. Close/reopen the app and provide the valid link again when watching as guest. Progress can survive; the link is not saved for automatic reopening. Test fullscreen/Back, double-tap, quality/speed, pause, background/foreground and network Retry.
5. Test the same shared video as guest, account A and account B. Each should get only its own saved position; switching back can recover that account's position. Guests on this device share one guest namespace.
6. Stop sharing/delete from the owner, then reopen using the old guest link. It must fail without showing the saved-position prompt. Restore a legitimate sharing flow only through the existing owner controls. Do not expose a complete share link or token in screenshots/chat.

Phone acceptance and broader hardware/abrupt-kill edge cases remain pending. No hosting, public discovery, reports, monetization, history feed or cross-device playback synchronization is added.
