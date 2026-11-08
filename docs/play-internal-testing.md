# Google Play internal testing preparation - 31 October 2026

## Authorization and current boundary
The user explicitly requested starting internal testing and uploading through Chrome, not the Codex in-app browser. Use Chrome for subsequent Play upload/review actions. Vidoora's existing draft is com.vidoora.app in the approved developer account. A first internal release draft named Vidoora 1.0 - internal 1 was saved in Chrome with accurate developer-device-only release notes. No bundle has yet been uploaded, no testers selected and no rollout performed at the time of preparation. The internal track is not a production launch and does not guarantee BillDesk accepts the resulting tester link as a public listing.

The user initially recalled an existing key, then explicitly requested a NEW dedicated Vidoora upload key because its location was forgotten. Do not overwrite/search through unrelated apps' private keys. The user confirmed local key creation; never request passwords or key files in chat.

## Build changes
- New playInternal build type: release-derived, non-debuggable, com.vidoora.app, Vidoora Internal Test label, versionName suffix -internal. Default internal versionCode 1, overridable with vidooraPlayVersionCode for future uploads.
- Target SDK updated from 35 to 36 to meet Google's current submission requirement. compileSdk remains 37. Existing debug package com.example.vidoora and isolated billingTest identity remain unchanged. No installation or app data reset was performed.
- Internal backend defaults to http://127.0.0.1:8080/ for the developer phone with USB adb reverse. An eventual actual HTTPS backend can be supplied using vidooraInternalApiBaseUrl. URL credentials/query/fragment and non-loopback HTTP are rejected.
- Internal-only network security XML denies cleartext by default and permits only 127.0.0.1, trusting system certificate authorities. This is a local device testing setup, not remote hosting; do not invite remote testers expecting the laptop endpoint to work remotely.
- Google demo ads only; live ads remain disabled. Internal billing SDK/Restore support is enabled but still server-configuration gated; checkout buttons remain unconditionally Coming soon. No mock paid grant or payment activation was introduced.
- A Play upload signing validator requires all four private environment values, a private key entry, a currently valid X509 certificate and a non-debug certificate. Packaging/signing/bundle tasks depend on this validator. Do not upload the earlier Android-debug-signed APKs or a debuggable billingTest bundle to this track.

## Private upload key - user action once
Run manually in Windows PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "F:\app\app\Vidoora\scripts\New-VidooraUploadKey.ps1"
```

The user chooses and confirms a strong 12+ character password privately. The script refuses to overwrite an existing key/password file. It creates RSA3072 PKCS12 material with alias vidoora-upload, a non-personal Vidoora Upload certificate subject and 10,000-day validity. Passwords are passed to keytool via a temporary process environment variable, not command arguments or source. The temporary variable is removed afterwards.

Fixed private location: %LOCALAPPDATA%\Vidoora\Signing\vidoora-upload.p12. A current-user Windows DPAPI-encrypted password file and public certificate are stored beside it, outside the repository. JKS/keystore/P12/PFX/signing-property files are ignored by source control. Back up the .p12 securely and retain its password separately in a password manager. The DPAPI file is tied to this Windows user profile and is NOT a portable password backup. Never copy its plaintext password into chat/logs.

After the key exists, the local build helper loads it without repeatedly prompting:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "F:\app\app\Vidoora\scripts\Build-VidooraPlayInternal.ps1" -VersionCode 1
```

It runs bundlePlayInternal with one worker, no daemon/configuration cache or file watching, then restores signing environment variables. Expected artifact: app\build\outputs\bundle\playInternal\app-playInternal.aab. This path is an expected output, not proof a signed artifact exists before the helper actually succeeds.

For custom secure build environments, Gradle reads VIDOORA_UPLOAD_STORE_FILE, VIDOORA_UPLOAD_STORE_PASSWORD, VIDOORA_UPLOAD_KEY_ALIAS and VIDOORA_UPLOAD_KEY_PASSWORD. Do not put these secrets into gradle.properties, .env.example or command-line -P arguments.

## Chrome workflow after signing
Verify the actual AAB package, non-debuggable/testOnly flags, target SDK, version code, signing certificate and absence of private credential files. Then upload the exact verified artifact through the saved Chrome internal-release draft. Review Play's actual validation results before moving to rollout. User/account legal or security-consent decisions must be handled explicitly when they arise; no public release is authorized.

Select a new Vidoora-only tester list after confirming the Google account used by Play Store on the user's phone. Do not modify tester groups belonging to other apps. Licence testers are separate from internal testers; do not perform real purchases. Current purchases remain disabled until real product, server credential and licence-test configuration is ready.

On the tester phone, install from the Play opt-in link, connect USB, start the saved backend launcher and keep its terminal running. Run adb reverse for that device:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:8080 tcp:8080
```

This developer-device arrangement remains laptop dependent by explicit project choice. Real always-on HTTPS delivery remains required before public/remote usage and real payments.

## Artwork
The inspected internal upload screen requires a bundle, release details and tester setup; it does not currently require generated marketing artwork. The app already contains the approved Vidoora icon. Do not delay this internal track with speculative image generation. When store-listing artwork becomes required, inspect exact asset dimensions first, supply a separate prompt for a feature graphic if useful, and use real app screenshots rather than generated screenshots that misrepresent implementation.

## Verification status
Initial merged manifest and BuildConfig inspection confirmed com.vidoora.app, target36, DEBUG=false, the loopback endpoint and demo-only ad flag. Both PowerShell helpers parsed without syntax errors. The initial Gradle script import collision was corrected. A full Robolectric run exposed mixed native-graphics/legacy-SQLite class-loader failures in UploadWorkerTest; its nine tests passed in an isolated run. The per-test-class forked full suite completed with 165 passing tests and zero failures/errors/skips. No new server tests are claimed for this Android build-configuration increment.

References checked 31 October 2026:
- https://support.google.com/googleplay/android-developer/answer/9845334
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/google/play/billing/test

## Follow-up verification - 31 October 2026
The user confirmed creation of the new private upload key and selected vishalchauhan638637@gmail.com as the phone Play Store tester account. The full per-class-isolated Android suite completed: 165 tests, zero failures/errors/skips. Internal manifest and Kotlin compilation also passed. The real signed build initially failed because the generated daemon criteria requested an unavailable JDK25 download; daemon criteria now use the installed, verified JDK17. Signed bundle build/upload/rollout remain pending actual results. No live purchase or device installation is claimed.

## Signed artifact and Chrome blocker
Real bundlePlayInternal completed successfully in 5m7s, including lintVital and private signing validation. Artifact: F:\app\app\Vidoora\app\build\outputs\bundle\playInternal\app-playInternal.aab. SHA256: 5B2AD656D51791DF99B5C3F2D7F781236D2895E07851DB639FE81E7E3AC0DDFC. Merged manifest: com.vidoora.app, versionCode1, 1.0-internal, min26/target36, non-debuggable. jarsigner verified the bundle; keytool confirmed CN=Vidoora Upload and public certificate SHA256 4C:DE:CD:39:44:80:BC:E5:EE:2C:97:05:95:21:23:78:47:48:ED:D3:C7:C6:AE:04:DE:98:A9:8C:D3:55:41:95. Credential-filename scan found zero keystore/DPAPI/env/service-account files. Jarsigner emitted self-signed/no-timestamp and ZIP streaming-order warnings; actual Play parsing is still pending.
Chrome upload was attempted using the file chooser, but setFiles was blocked because the ChatGPT extension has Allow access to file URLs disabled. No bundle upload, tester-list selection or rollout is claimed. User was given the documented Chrome permission step. Tester account confirmed as vishalchauhan638637@gmail.com. Phone installation and runtime verification remain pending.
