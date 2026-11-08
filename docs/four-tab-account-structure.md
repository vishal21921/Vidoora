> Download follow-up: see [private-offline-downloads.md](private-offline-downloads.md). The runtime downloader and Profile Downloads are now implemented behind verified paid access. Earlier statements that all download code is absent are superseded; checkout and real store/device verification remain unfinished.

> Current follow-up (30 October 2026): see [purchase-activation-recovery.md](purchase-activation-recovery.md). Authenticated durable receipt recovery and payment records are now wired, OFF by default; billingTest has gated Restore. Historical descriptions of those pieces as absent are superseded. Checkout, private downloads and real Play verification remain unavailable.

# Four-tab navigation and account structure — 30 October 2026

The user corrected the priority: finish the requested overall UI structure before more subscription UI increments. The main navigation is now **Home / History / Upload / Profile**. This document supersedes earlier three-tab layout descriptions.

## Main destinations

| Tab | Current content |
| --- | --- |
| Home | Existing complete-link entry and plans shortcut; guest watching remains available |
| History | Recently watched videos on this device. Open a video to recheck access, or remove the entry. |
| Upload | Creator hub: Upload a video, My Videos, Upload queue, Folders, Blocked uploaders |
| Profile | Account identity/user ID, current plan/expiry, payments, downloads, support, settings, release-dependent share/rating and logout |

Existing upload/library/queue/folder/block screens are reused. The former Upload form is now the authenticated `new_upload` child destination, while `upload` is the hub. Queue New upload also opens that form. Login from the hub returns to the hub; direct creator children retain their authentication gates. Back and main-tab navigation remain consistent with the existing Home-root navigation. The bottom bar is shown only on the four main destinations, not secondary pages.

Guest creator controls are disabled behind a clear Log in/Create account action; no other account's tools/data are exposed. Existing upload workers, durable drafts, pause/resume/cancel/delete, private sharing, source limit, video playback/ads and backend APIs were not rewritten. My Videos/queue/folders/blocked controls were moved out of Profile as requested. No video-report option was introduced.

## Profile

Signed-in identity displays the actual existing server UserDto name, username and email. User ID is the existing backend account UUID, not a new installation/device identifier; it remains stable for that account. An explicit Copy action copies the full ID and confirms Copied. Guests are not assigned a fabricated account ID.

Current plan and valid-until date use only the existing verified membership view. Loading is labelled Checking access, and an account change renders the repository's current-session view rather than waiting for the next state-flow tick. This avoids displaying an old account's cached plan on the new account's Profile. No local premium toggle or fake subscription is created.

Membership actions lead to the existing plans preview, a separate Payments page and a Downloads page. Payment history is separate from the watch History tab. The payment page does not invent zero/success/failed/pending transactions or reference IDs; real payment data is not available yet. Guests can log in and return to the appropriate page. Downloads are clearly unavailable until the private downloader is implemented.

Help/App contains Support centre, Settings, Share app and Rate Vidoora. Share/rating controls are visible but disabled with release-specific explanations: the Play app is an unpublished draft, so no working public download/rating URL is fabricated. Logout still uses the existing secure logout/revocation path. The legacy creator callback parameters remain source-compatible but are no longer displayed as Profile actions.

## Support centre

The user explicitly supplied **vidoora.support@gmail.com** for the public support address. The screen shows it with Copy support email, the actual user ID (or clearly labelled Guest viewer), topic selection, optional transaction-reference input and an issue message. Fields are memory-only and reset when the account ID changes. No access tokens, purchase tokens, credentials, private links, device IDs or logs are automatically included.

Open email app requires a nonblank message. It builds a user-reviewed ACTION_SENDTO email draft with the fixed mailto recipient and subject/body intent extras. The message contains account ID/guest label, an optional transaction ID, app version, topic and the user-written message. The app does not send mail. Users can attach a screenshot inside their chosen email app, review and send it themselves. No Gmail account connection, SMTP key or background email service was configured.

User text is not interpolated into the mailto URI query. A test caught that the Android MailTo parser decodes query separators before splitting fields; the final implementation keeps body/subject in separate intent extras. Ampersand text stays literal and cannot introduce an extra recipient in that URI. Recipient and body behavior were tested synthetically; no email client was actually launched and no delivery was tested.

Reference: https://developer.android.com/guide/components/intents-common#Email . Local primary framework source inspected: Android SDK sources/android-37.0/android/net/MailTo.java.

## Settings

Settings contains the shared subscription badge and the existing UMP ad-privacy action when its existing consent prerequisites are available. Opening Settings does not initialize the ad SDK or show consent automatically. The version is displayed for support. No system settings, new billing behavior or new consent policy was enabled by this UI work.

## History and remaining data services

History is a structural placeholder, explicitly labelled coming soon. The existing encrypted numeric resume checkpoints are deliberately not repurposed as a navigable history: they contain no recoverable titles/complete links, and private playback still requires fresh authorization. No new link/capability/title retention or public discovery was introduced. A real account-separated history feature needs its own approved storage/access implementation.

Payment activation, verified payment-attempt records/status/reference IDs, purchase restore/recovery/reconciliation, private downloads and public release links remain unfinished. This stage completes navigation and account surfaces first; it does not claim those services now work. Existing subscription prices/benefits and purchase-disabled state remain unchanged.

## Changed areas

- ui/navigation/ReferenceBottomBar.kt and NavGraph.kt: fourth tab, creator hub/form split, account/support/settings routes and current-session plan rendering.
- feature/upload/CreatorHubScreen.kt: all creator tools in one place.
- feature/profile/ProfileScreen.kt: actual user identity/ID, current plan and account actions.
- feature/profile/AccountScreens.kt: History, Payments, Downloads, Support and Settings surfaces plus user-reviewed email intent builder.
- ui/theme/AccountComponents.kt and two code-native history/copy vector icons.
- AccountStructureUiTest.kt: synthetic large-font tab navigation, creator callback/gate, account ID clipboard, profile actions/expiry, support draft recipient/body and no-ad Settings checks.

## Acceptance after Android Studio Run

Confirm four bottom tabs; open Upload then each existing creator tool; check Profile identity/User ID/Copy and account sections; open Support and verify its recipient/reference fields. Open email app should launch a draft for review, never send automatically. Confirm History/Payments/Downloads show their truthful availability state. Keep the current app installed and existing backend running; no new secrets or database setup is required for these UI changes.

Final executed tests and screenshot inspection are recorded below after verification completes.


## Executed final verification

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileBillingTestKotlin :app:compileDebugAndroidTestKotlin --init-script ..\phase5-test-home.init.gradle '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
```

Java17, one worker, source-identical verification copy:

Android: 148 tests passed, zero failures/errors/skips
Source/build parity: 256 files, zero mismatches
BUILD SUCCESSFUL in 6m 46s

The passing total includes six new synthetic Compose/Robolectric cases plus the previous 142 Android tests. Checked: four selectable tabs at 150% text, all five creator callbacks, guest creator gating, actual fixture ID copy/plan expiry/profile actions, disabled unpublished share/rating, user-reviewed support email recipient/body isolation and Settings without ad initialization. Five new native-canvas host snapshots were visually inspected: creator-hub, four-tabs-large-font, profile-account-top, profile-account-actions, support-centre. All use synthetic identities/messages; they are not a physical device or emulator run.

The initial focused run failed one support-draft test: putting a user ampersand string in a mailto query was misparsed by Android MailTo. The final code uses a fixed recipient-only URI and separate subject/body extras; the final test proves the body survives and no BCC is added. That failed run was not counted as passing.

A debug APK was assembled at workspace outputs/Vidoora-four-tab-account-debug.apk. Billing-test and existing native-fixture Kotlin compiled. No APK was installed/uploaded, no native instrumentation or actual mail app/send/delivery was tested, and no live Google/R2/PostgreSQL/payment test was executed. Backend sources are unchanged and their previously passing suite was not rerun for this UI increment. Existing analytics-preference, annotation and deprecated API warnings remained non-fatal.

APK validation: apksigner verify succeeded. Signing-certificate SHA-256 ccc58de6a6c480204cb8b93e6574dc012e15d07e2d5f799f6f75b137e81a1cae matches the earlier archived debug builds. No uninstall, reset or installation was performed.
