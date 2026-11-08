# Phase 9 — In-app video quality selection

Approved 26 October 2026. The user reported that Phase 8 controls were visible; actual phone search/save/load-more checks remain pending and are not implied by that report. Scope approved here: add Auto and available manual quality choices to the existing authenticated Android HLS player.

## Behavior and boundaries

- Watch video has a Quality button. Auto is the default for a newly opened video.
- Options come from Media3's loaded video track groups, using the actual positive format heights and device-supported formats only. They are sorted and deduplicated by height. No unsupported, audio-only or unknown-height entry is offered. A 240p-only source shows Auto/240p; 360p/480p/720p are not fabricated for missing renditions.
- Manual selection sets a single Media3 video track override. Auto removes video overrides and leaves Media3 to adapt under its normal bandwidth/device constraints. Audio overrides/language preferences and unrelated track parameters remain unchanged.
- Changing quality does not seek, call prepare, replace the HLS source or change playWhenReady. Position and paused/playing intent are retained. Already buffered segments may play before the new rendition appears; the UI explains this and shows Playing: <actual video height>p from Media3 video-size events separately from the chosen quality.
- The desired quality is saved for this video across configuration recreation/rotation and Retry. A temporary empty-track loading event does not erase it. If a loaded source no longer offers that supported height, selection falls back to Auto. This is not a global quality preference for every new video/account.
- Quality controls are disabled until supported tracks are known and while playback has failed. Existing error/Retry behavior remains; retry captures current position and paused/playing intent. Lifecycle pause/release remains in place.
- All renditions still use the original authenticated HLS master/media/segment routes and AuthenticatedMediaDataSource. No alternate direct R2 URL, capability query string, public access, backend route, schema change, hosting or monetization is introduced.
- A forced high rendition can buffer more on a slow connection; Auto is the default. This feature does not change processing output, upscaling policy, upload limits or existing media files.

## Changed files

- app/src/main/java/com/example/vidoora/feature/playback/VideoQualities.kt: derive supported choices, preserve non-video parameters, select one video track or return to Auto. The apply helper changes only trackSelectionParameters when needed, avoiding repeated track-event writes.
- app/src/main/java/com/example/vidoora/feature/playback/VideoPlayerScreen.kt: menu, desired/rendered quality display, saved per-video preference, track/size listeners and Retry state preservation.
- app/src/test/java/com/example/vidoora/VideoQualitiesTest.kt: supported/sorted/deduplicated options; low-resolution/empty/unsupported tracks; exact manual index; Auto/unavailable fallback preserving audio/settings; Player-interface regression forbidding seek/prepare/media replacement/pause changes.
- AGENTS.md, README and docs/VIDOORA_MASTER_PLAN.md: approved scope and verification record.

The implementation was checked against the installed Media3 1.4.1 source API. Dependencies and backend source/migrations are unchanged. Backup is in C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\quality-before.

## Verification

Executed 26 October 2026: **52 Android tests passed, zero skipped/failures/errors; debug APK built.** The Gradle command completed successfully, JUnit XML was inspected and app source hashes match the verification copy. Existing data-source authentication/privacy regressions passed with the suite. No backend/live integration/device instrumentation test was executed for this Android-only change; native switching, rotation and Retry acceptance remain pending on the phone.

Built artifact: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\Vidoora-quality-debug.apk. No phone installation, uninstall/data clearing, backend restart or external configuration was performed by the agent.

Standard project command:

```powershell
Set-Location 'F:\app\app\Vidoora'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest '-Pkotlin.compiler.execution.strategy=in-process'
```

Executed in the established sandbox verification copy (same app source/dependencies, Gradle 9.6 and existing Java 17 toolchain; copied SDK/offline Robolectric setup). Original project Java 25 daemon/SDK settings remain unchanged:

```powershell
# Working directory: C:\Users\chauh\Documents\Codex\2026-10-12\i-x20\outputs\phase5-verification
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --console=plain
```

The Player-interface test checks that the helper touches only track selection; Robolectric format/group tests exercise the actual Media3 parameter API. They do not demonstrate decoder-level rendition switching on a real phone. No backend, PostgreSQL, R2, FFmpeg or device instrumentation test is needed or claimed for this Android-only change. Existing authentication/privacy data-source tests run with the Android suite. New phone quality acceptance remains pending.

## Phone acceptance

1. Sync and Run the existing project in Android Studio. No backend restart/migration is required for this feature; keep the normal backend running and USB reverse connected.
2. Open a processed video with several renditions. Once it loads, tap Quality. Confirm Auto plus only its available supported heights.
3. During playback select 360p, 480p or 720p where available. Playback must not return to the start. Allow buffered video to finish before judging the Playing height; use a longer video for a meaningful switch check.
4. Pause, change quality, and confirm it remains paused. Resume and seek, then return to Auto.
5. Rotate the device and check position and chosen quality. With a retryable network failure, reconnect and Retry: position and chosen quality should be retained.
6. Open a low-resolution video and confirm unavailable larger qualities are absent.
7. Repeat playback/quality selection using a valid complete link in a second account. Stop sharing or Block should still deny future media requests; a quality change never grants access.

Phase 8 save/search/older-page phone checks remain pending separately. Do not advance to unapproved hosting, public discovery, reports, monetization or video deletion as part of this feature.

