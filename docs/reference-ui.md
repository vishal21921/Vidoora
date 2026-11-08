# Reference-based UI — first group, 27 October 2026

## Scope
The user supplied 20 design images and approved beginning with Home, navigation, login/registration and Profile. This increment implements that group. Upload, library, queue, video details/player/dialogs, folders and blocked-uploaders reference layouts remain the next group; their existing working layouts are retained, although shared theme colours/shapes apply throughout the app.

References: Home 215250; login clipboard 7c915996; registration 213355; signed-in Profile 212140; guest Profile 212339; rounded navigation from Upload/Profile references. These are design references, not fixed pixel measurements. System bars, keyboard, video content and account information use Android/current application state rather than painted sample data.

## Changes
- HomeScreen.kt: lowercase play-mark branding, Viewer/Creator badge derived from current sign-in, centred link-entry layout, inline Paste button, automatic-open guidance and small account-required notice. The clipboard is read only after an explicit Paste tap; links are still handled by the existing memory-only VideoLinkEntry. No clipboard history, saved capability, public feed or new network endpoint.
- ReferenceBottomBar.kt: rounded bordered navigation container, three icon/label tabs, selected background and dot, minimum touch sizing and Tab semantics. Existing route/back-stack/login gates are preserved.
- ProfileScreen.kt: signed-in initials/account card and icon-led Library & activity menu; separate quiet destructive logout control. Guest Profile has a bordered card and separate working Log in/Create account actions. Existing progress/errors and logout behavior remain. No fake storage, followers or activity metrics.
- AuthComponents.kt: shared bounded scrollable keyboard-aware page and icon fields, programmatic field names, masked password and inline error styling. Password/account fields remain remember-only as before.
- LoginScreen.kt/RegisterScreen.kt: left-aligned headings, matching outlined fields and indigo actions, functional Back, account-switch actions and existing loading/success callbacks. No reset-password/social-login/backend changes.
- NavGraph.kt: wires Back, direct guest registration and signed-in Home badge without changing creator destinations or PLAYER identity scoping.
- ReferenceComponents.kt: common brand/header/card/action/error primitives.
- Theme.kt: white light surfaces, stronger indigo accent, pale borders and explicit neutral surface containers. Dark mode remains supported. Vector assets ic_ui_* use repo-native Android vectors; no icon package/font/network asset dependency. Back/chevron mirror for RTL.

## Verification
Final executed verification: :app:assembleDebug and :app:testDebugUnitTest succeeded. 94 Android tests passed, zero failures/errors/skips. App source hashes match the verification copy in both directions (zero mismatches or extra files). Existing compiler annotation/deprecation warnings remain. Final APK: outputs/Vidoora-reference-ui-debug.apk. The isolated emulator was stopped after verification. Command run in the existing exact-source Android verification copy:

```powershell
$env:JAVA_HOME='C:\Users\chauh\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=2 --console=plain
```

Correction from the later video-details verification: the Pixel_6/API 37.2 emulator was started with a separate -datadir, but explicit AVD disk paths overrode it. These checks therefore used the existing local Pixel_6 AVD, emulator-5580; the debug app was installed with -r, with no uninstall/data clear or physical-phone install. Display overrides were restored by command afterward. Do not treat the earlier separate-directory claim as verified isolation. Later video-details checks use a separately named AVD and verified explicit disk paths; see video-details-reference-ui.md. Home, guest Profile, Log in and Create account were rendered and visually inspected. Profile Log in and Login Create account navigation worked. Registration was inspected at approximately 320dp width (density override 540 on 1080px) and system font scale 2.0: text wrapped, form scrolled and bottom Create account/account-switch actions were reachable. The blank password field moved above the keyboard. A first-run keyboard help card belongs to the emulator keyboard, not Vidoora. Dark Home at the same narrow/large-text settings was inspected with no horizontal content clipping; vertical scrolling is required at those settings. One emulator System UI not-responding dialog during boot was dismissed with Wait; this is not claimed as an app failure or ignored test result.

Screenshots in chat outputs: reference-home.png, reference-guest-profile.png, reference-login.png, reference-register.png, reference-register-large-text.png, reference-register-large-text-scrolled.png, reference-register-keyboard.png, reference-home-dark-large-text.png. They contain no real account/link/token. The final later changes add field semantics and RTL vector mirroring and do not change these LTR visual layouts.

No real account was created or used, no forms submitted and no upload/playback/backend/PostgreSQL/R2/FFmpeg integration suite rerun. Signed-in Profile was source-reviewed and compiled but not exercised against a live account here. Physical phone, tablet, RTL, TalkBack, dark authentication, landscape and wider device acceptance remain pending. Existing Android regression tests verify behavior rather than pixel similarity. No claim of every-device compatibility.

## Next review
Run the existing project from Android Studio without uninstalling/clearing app data. Inspect Home, Profile and authentication against the supplied images; confirm the visual direction before extending the reference treatment to the remaining screens. Backend need not restart for this Android UI change; existing backend/continuous processor still need to be available for real upload/playback.


