# Security policy

Clean Your TV changes a TV over its debugging connection, so we take reports seriously and
answer them first.

## Reporting a vulnerability

Please report privately through GitHub:
[Report a vulnerability](https://github.com/udhaykumarbala/cleanyourtv-app/security/advisories/new)
(Security tab, "Report a vulnerability"). Do not open a public issue, pull request or discussion
for a security problem.

Useful to include: the app version (shown in the app settings), the phone and TV models with their Android
versions, what an attacker can do and from where (same Wi-Fi, the TV, a malicious app on the
phone, the network path to GitHub), and the steps to reproduce. Redact your TV's serial number
and your network addresses; we never need them.

What happens next:

- We confirm we have it within 7 days.
- We agree on a fix and a disclosure date with you; 90 days is the default, sooner when a fix
  ships sooner.
- Fixes ship as a new signed release; the advisory credits you unless you prefer otherwise.

## Scope

In scope: this app and everything in this repository, including the release pipeline, the
signed module index and its keys, the bundled launcher and TV curtain as this app pins and
installs them, and the restore scripts it writes.

Problems in the launcher itself (the TV home screen) belong to the upstream project,
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv); report them there privately.

## Supported versions

Only the latest release gets security fixes. Android refuses updates signed by another key, so
updating in place is always safe; the release certificate SHA-256 is in
[docs/release-signer.sha256](docs/release-signer.sha256).

## The bundled launcher: what it asks the TV for

The app installs one launcher build, pinned by its SHA-256 (core/trust `PinnedLauncher`): launcher
3.3 (upstream main, commit d7b0d4a5, versionCode 12) since 0.4.9-beta1, 3.0 (commit b06ab21,
versionCode 9) from 0.4.5 to 0.4.8. Each upstream bump is reviewed against the previous pin; the
changes in the manifest are listed here, newest first.

Launcher 3.3 (upstream main, commit d7b0d4a5, versionCode 12; was 3.0, b06ab21, versionCode 9). No
new permission, component, exported entry or provider. Two manifest changes:

- `MainActivity`'s Home filter also lists the categories `android.intent.category.COMMON_HOME`
  (CVTE firmware sends Home and boot with it, upstream #1201) and
  `com.heytap.tv.launcher.category.MODERN_HOME` (ColorOS TV only promotes a Home that declares it).
  Both are in the one existing filter, so a plain Home intent still matches it as before; nothing new
  can start the activity that could not before.
- `HomeSettingsActivity` (not exported) uses the launcher's own theme instead of
  `Theme.NoDisplay` (a black backdrop behind the Default home picker).

Launcher 3.0 (upstream main after v3.0.0, commit b06ab21, versionCode 9; was 2.7, v2.7.0,
versionCode 8). The manifest at this commit is the same as at the v3.0.0 tag:

- New exported activity `AppLaunchActivity` for `VIEW` with `BROWSABLE` on `market://launch?id=<package>`,
  with `Theme.NoDisplay`. It opens an installed app by its package name (its TV launch entry, else
  its phone one) and closes; it takes no other data. It serves the "open app" request of the
  Android TV Remote service, which the stock launcher normally answers. This app never sends it.
  Exposure: since it is exported and BROWSABLE, any app or web link on the TV can ask it to open an
  installed app by package name; it cannot open anything that is not already installed and
  launchable, so the risk is low.
- New notification listener `GuardRevival` (behind `BIND_NOTIFICATION_LISTENER_SERVICE`). It reads
  no notification. The system binds it only once notification access is approved for it, by
  `cmd notification allow_listener com.gathin.tvhome/.GuardRevival` or by the owner in the TV's
  Notification access setting; it then rebinds it after a force stop, which brings Home back on
  firmware that kills it. On each bind it calls `GuardKeep.ensureHomeKey`, which does nothing more
  than the opt-in below allows. **This app never sends that command**, so on a TV set up with this
  app the listener stays unbound unless the owner approves it on the TV.
- New receiver `GuardConfig` for `com.gathin.tvhome.GUARD`, protected by `WRITE_SECURE_SETTINGS`:
  only a sender holding that permission (the ADB shell) can reach it. It switches the opt-in
  `guard_off_in_standby` ("leave the home helper off in standby", off by default; nothing else sets
  it). With the opt-in on, `HomeKeyService` steps out at screen off (`armStandbyExit`,
  `leaveForStandby`, `GuardKeep.stepOut`, which calls `disableSelf()` and needs no permission).
  Putting the helper back at screen on (`GuardKeep.ensureHomeKey`, which writes
  `enabled_accessibility_services` and `accessibility_enabled`) needs the launcher itself to hold
  `WRITE_SECURE_SETTINGS`; without that grant the opt-in leaves the remote-button helper off after
  the first standby. **This app never grants that** (`pm grant`) and never sends the broadcast, so
  the opt-in stays off on a TV set up with this app.
- New package visibility query for `android.settings.APPLICATION_DETAILS_SETTINGS` (scheme
  `package`): the tile menu and the app library offer App info only where it resolves. It grants
  nothing.
- No new `uses-permission`. Everything else is as in 2.7.

The core/ops `RendererPropertyTest.noOpApprovesGrantsOrSwitchesOnTheLaunchersHelpers` pins these
statements: no op renders `cmd notification`, `pm grant`, `WRITE_SECURE_SETTINGS` or a broadcast to
the launcher, and the only accessibility writes are the two accessibility settings with the op's
own value, which the planner uses only to put back the list the TV had, and, in the test build
only, the Home button helper's lines (the last section).

Since 0.4.7 (RFC-THEME-1) the app writes the look, `config.json` and `wallpaper.jpg`, through the
launcher's theme provider (`content://com.gathin.tvhome.theme`, exported and guarded by
`WRITE_SECURE_SETTINGS`, which the ADB shell holds) on Android 10 and later with launcher 2.5 or
newer, the way upstream's `tools/theme.sh` does: the file is pushed to the app's folder in
`/data/local/tmp`, `content write` copies it in as the launcher, the staged copy is removed, and the
check reads it back with `content read` piped to `sha256sum`. A direct copy is the fallback where the
provider does not answer. These are the renderer's two fixed templates beside the watchdog's
(core/ops `ThemeProviderLines`), filled only from a two-entry list of file names and covered by
golden files and the property tests. The provider takes nothing else from this app.

Launcher 2.7 (upstream v2.7.0, commit 3e34f40, versionCode 8; was 2.5, v2.5.0, versionCode 6):

- New permission `android.permission.WRITE_SECURE_SETTINGS`. It is a protected permission that
  only `pm grant` over ADB can give; installing the APK does not. Upstream uses it for one optional
  feature (`restore_key_services`, the `ServiceRepair` class): after a force stop, put the
  launcher's own key helpers back on the enabled accessibility list. Without the grant the code
  returns at once. This app never runs `pm grant` for it, so on a TV set up with this app the
  launcher does not hold it and the feature stays off.
- New permission `yandex.tv.permission.ACCESS_TV_SOURCE` (protection level normal, Yandex TV OS
  only): lets the Inputs menu open HDMI and the tuner there. It does nothing on other TVs.
- New package visibility queries: the standard settings screens, the HOME intent, the TCL and Fire
  TV settings packages and two Coocaa (Skyworth) input providers. Queries only make those apps
  visible to the launcher; they grant nothing.
- New exported activity `InputsRequestActivity` for `com.android.tv.action.VIEW_INPUTS`, disabled
  in the manifest. The launcher switches it on only when its config sets `handle_view_inputs`,
  which this app does not set. It opens the launcher's own Inputs menu and takes no data.
- New activities `ClockActivity` and `ProbeActivity`, not exported.
- `HomeKeyService` is now direct boot aware (it can start before the first unlock), and both key
  services no longer ask for window events: they receive
  key events only, which is less than 2.5 received. `stock_home_redirect` would add window events
  at run time; this app sets it only for the test build's Home button helper (below).
- Unchanged: the HOME activity, the theme provider (exported, guarded by
  `WRITE_SECURE_SETTINGS`), the screensaver behind `BIND_DREAM_SERVICE`, both key services behind
  `BIND_ACCESSIBILITY_SERVICE`, and the network, Wi-Fi and location permissions.

## The Home button helper (test build only)

Since the 0.4.8 test track, a build made with `-Pcyt.homeHelper=true` can switch on one of the
bundled launcher's accessibility services, `com.gathin.tvhome/.HomeKeyService`, after the person
agrees to it in Setup's Customise. A release build bundles no marker for it: its planner refuses
the helper and no screen offers it. Without that opt-in, everything above holds as written: the
app never adds a service and never writes `accessibility_enabled 1` unless putting back exactly
what the TV had.

What it is for: the TV keeps its own home screen (and its setup wizard, so casting and the Wi-Fi
it saved are not touched), and the helper opens the new home screen whenever the TV's own comes to
the front.

What the app writes, and nothing else:

- In the launcher's `config.json`: `stock_home_redirect`, the package of the TV's own home screen,
  which must be Google's (tvlauncher, leanback or launcherx), and `key_filter` false. Not
  `restore_key_services`, `guard_off_in_standby`, `power_on_home`, `home_long_press_home`,
  `consume_volume_keys`, `gamepad_passthrough`, `handle_view_inputs` or `app_keys`.
- `enabled_accessibility_services`: our one entry appended by a compare-and-set on the TV. The TV
  reads the list and writes it only while it still reads what the phone read; otherwise it writes
  nothing. Every other entry stays byte for byte and in order. A list of 16 entries, or one the
  app cannot read safely, is refused.
- `accessibility_enabled 1` only when accessibility is already on, or when nothing but our entry
  is listed: switching it on would bind every listed service, someone else's too, so with
  accessibility off and another service listed the helper is refused.
- A safety timer during the check, like the home screen switch's watchdog: unless the phone
  confirms in time, the TV takes our entry out of the list itself and shows its own home screen,
  so a new home screen that crashes at start cannot keep the TV in a loop of reopening it after
  the phone has gone.

Switching off (Manage), the check's own rollback, Undo everything and the laptop restore scripts
take out exactly our entry (in its short or full class form) and nothing else, and switch
accessibility back off only when this app switched it on and the list is then empty. Undo never
adds the helper. The renderer accepts no other service for these lines, and none of them writes
`accessibility_enabled 1`.

What the helper can see: with `stock_home_redirect` set, `HomeKeyService` asks Android for window
changes, which name the package and class of the activity in front. It does not ask for window
content (`canRetrieveWindowContent` is not declared), and with `key_filter` false it filters no key,
so it receives no button presses. The launcher has no INTERNET permission. Android still describes
any accessibility service as able to see the screen and the buttons pressed; the consent says
what this one asks for. Side effects of the bound helper: `ProbeActivity` opens once per boot (so
a home screen drawn before the bind is caught), Launcher settings offer Restart while it is bound,
and it binds before the first unlock (direct boot aware).

Unchanged and still pinned by the renderer tests: no `pm grant`, no app-ops (so not TCL's
`AUTO_START`; on TCL firmware with `TclAppBoot` the helper may not survive a restart), no
notification listener, no broadcast to the launcher, `WRITE_SECURE_SETTINGS` never granted.

Threat notes: the launcher is pinned by SHA-256 and signer; a hostile overlay could already name
another app's home screen in `stock_home_redirect` (a carried key), so the codec warns on any value
that is not one of Google's TV home screens, and a later look carries the helper's keys only while
our entry is listed and only for one of those. A new home screen that crashes in a loop is a local
denial of service with local escapes: TV Settings > Accessibility > Home button, safe mode, or the
phone's Manage and Undo.
