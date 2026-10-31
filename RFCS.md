# RFCs

## RFC-UP037-1: the config differential test keeps launcher 2.5's reader

(History: the app now bundles launcher 3.0 at b06ab21; see RFC-UP300-1.)

The app bundles launcher 2.7 (v2.7.0, 3e34f40). ConfigDifferentialTest compared the codec with the
upstream Config.java of the pinned submodule, copied in at build time. Launcher 2.7's Config.java
reads 67 new keys and needs Android stubs (android.os.Environment) and resources (R entries for
the new keys, WallpaperMood, ThemeFiles, WallpaperRotation.ORDER_SHUFFLE) the test harness does not
have. Smallest change: the 2.5 reader (v2.5.0, 9071ec8) is vendored in core/spec/src/test/java/
com/gathin/tvhome/pinned with its digests pinned, so the differential test still runs. The read code
of the keys the codec models is unchanged from 2.5 to 2.7 (checked for the bump), so the codec still
agrees with the bundled launcher on them. Open: port the 2.7 reader (stubs and resources), model the
new keys the app sets or shows, and carry the rest without the "the launcher ignores it" warning.

## RFC-LOOK-SETUP-1: Setup's look step has the Look sheet's choices

0.4.0 made apps in a row and the picture choices of the Look sheet, but Setup's look step still
offered the list of looks only, and a Setup run again cleared the saved apps in a row. Smallest
change: the Look sheet's content moves to `:ui:design` as `LookEditor` (with `PhotoPicker`, the
photo crop and `OwnPhoto`), used by both `:feature:launcher` and `:feature:setup`; a feature still
sees only `:core:flows`, `:core:model` and `:ui:design`. Frozen signatures grow, nothing is removed:
`SetupIntent` gets `SelectColumns`, `SelectPicture` and `SetOwnPicture`; `WizardDraft` gets
`columns` and `picture` (with defaults, so a draft written before loads as before); `SetupDeps` gets
`documents` (where the person's photo is kept) and `pictures`. One helper in `:core:flows`
(`LookDrafts`) makes the draft and its preview for both machines, and Setup encodes its look with
the same picture and apps in a row as the Look sheet, so the same choice is the same bytes.

## RFC-LOOK-SETUP-2: one mapping from the Look choice to the Look editor

After RFC-LOOK-SETUP-1 each feature mapped the flows' Look choice into `LookEditorState` itself,
and the preview mapping (`LookPreview` to `LookEditorPreview`) was the same code in both. Smallest
change: `:ui:design` gets one edge, `implementation(project(":core:flows"))`, allowed by rule 1
(layer 7 over layer 5), and `LookEditorFrom.kt` holds the one mapping (`LookDraft.toEditor`,
`LookPreview.toEditor`). Each feature hands in only its own state fields and keeps its own button
under the editor. No layer, allowlist or feature rule changes; a feature still sees only
`:core:flows`, `:core:model` and `:ui:design`, and the components stay plain values. Not chosen: a
new shared module (a new layer and a rule 3 change) or `:core:flows` (it cannot see `:ui:design`).

## RFC-LOOK2-ENGINE: the codec reads with launcher 2.7's own Config.java

(History: since RFC-UP300-1 the pinned reader is launcher 3.0 at b06ab21, and the 2.7 reader is
kept, frozen by digest, beside it.)

Launcher 2.7 (3e34f40, versionCode 8) is now the pinned reader of `LauncherConfigCodec`: its
Config.java (and the files it needs) is vendored under `core/spec/src/test/java/com/gathin/tvhome/v27`
with small Android stubs, and the differential test runs it, with the 2.5 and 2.0 readers kept for
their keys. `CARRIED_27` grows to 16 keys (the dock band, glow, names, top bar, focus spring and
cinematic shelf keys), read by `LauncherKeys` with Config.java's rules; `LauncherConfigDoc` is
unchanged. `LookKeys.build` writes every look2 push from scratch, and 43,050 distinct docs agree
with 2.7 with no warning. The codec refuses a config.json with NaN or Infinity in a list
(`CRASH_27_NON_FINITE`): 2.7 crashes Home on every start when it keeps its copy of such a file.

## RFC-LOOK2-MODEL: a saved look is a legacy choice or a look2 choice

A saved look is either a legacy 0.4.0 choice (style null), encoded by the frozen `LegacyLook` path
byte for byte, or a look2 choice (style, picture, columns, movement, lift, names, details), encoded
by `LookKeys.build` from the rules resolved on this TV (`LookRules`). The first send of a migrated
draft writes the new form, with `columns=` always. home.txt gains style, movement, lift, names,
prevpic and d.* lines, and every line stays optional. One earlier look per TV is kept in
tvs/<id>/earlier.txt, plus earlier.jpg when that look showed the person's photo; Forget this TV
deletes both with the TV's folder.

## RFC-LOOK2-PREVIEW: the preview reads every look2 field from the bytes

`LookPreview.of` reads the codec's effective config plus `LauncherKeys.look`, so the phone draws
what the TV will read and never the choice itself. The sky is a port of Sky.java (`Sky`), the focus
colour comes from a small table of app colours (`AppColours`), and `TvPreview` follows launcher
2.7's own layout code (activity_main.xml, item_app.xml, AppAdapter tile sizing, DockDecoration).
`TvLook` and `LookPreview` only gained defaulted fields, so 0.4.0 callers draw as before.

## RFC-LOOK2-UI: one Look editor with three pages, in two places

`LookEditor` in `:ui:design` holds the Look, More options and Advanced pages, the pinned preview,
the leave question and the notes; `LookEditorFrom.kt` stays the one mapping from `LookDraft`. The
Look sheet gives it "Put it on the TV" with the way back under it, and Setup's look step gives it
Done (and no leave question, as closing keeps the choice). System Back, the predictive gesture,
swipe and the scrim all go through the modal sheet's `confirmValueChange` into `LookEditorNav`, so
no activity-compose dependency is added. No module, layer or dependency changes.

## RFC-UP300-1: the codec and the looks are checked against launcher 3.0's reader

The app bundles launcher 3.0 (upstream main b06ab21 after v3.0.0, versionCode 9). The 2.7 reader
is frozen with digests; the 3.0 reader from the pin is vendored in `com.gathin.tvhome.v30` and is
the reader the codec and the look are checked against; the codec carries the 25 keys of 3.0
without a warning; no frozen type changes.

- `core/spec/src/test/java/com/gathin/tvhome/v30`: Config.java and the seven helpers it calls
  (Festival, WallpaperRotation, RaceWeekend, WallpaperMood, Sun, ThemeFiles, WallpaperCaptions),
  the submodule's files with only the package line changed; the `Cards` stub (cleanLabel,
  unchanged); an `R` stub with 2.7's ids plus the 24 config.xml ids 3.0 added; `V30ConfigBridge`.
  Config.java at the pin calls no class outside that set and no new Android API, so no other stub
  was needed. `LookDifferentialTest.vendoredLauncher30ClassesAreTheSubmodules` compares the copies
  with the submodule.
- `ConfigCodecImpl.CARRIED_30`: input_sources, lunar_date, lunar_utc_offset, open_on_start_choice,
  show_library_pill, library_ok_opens, uniform_tiles, consume_volume_keys, power_on_home,
  home_long_press_home, power_on_home_always, dock_band, wifi_status_warnings, date_below_clock,
  vpn_pill, locale, top_bar_apps, show_greeting, header_white, clock_wallpaper,
  clock_wallpaper_dim, clock_glow, single_row, dock_band_all and glass_style. They are carried in
  `unknown` as is; `BUILT_IN` and `LauncherConfigDoc` are unchanged, since every 2.7 default stays.
  A test holds the set to what 3.0's Config.load reads beyond 2.7's.
- `LauncherKeys.keys30` reads them with Config's helpers (bool, integer, string, stringList,
  glassStyle) into a new `Keys30`, with `DEFAULTS_30` from config.xml at the pin. Config keeps
  single_row and-ed with dock_row and adds top_bar_apps to the hidden list; `effective()` does not
  model the second (the app never writes top_bar_apps), and the differential applies both.
- `UpstreamLauncher.Version.V30` is the bundled one; V27, V25 and V20 stay for TVs not updated.
  On 3.0: the 288,500 build look cross product (43,050 distinct docs, 0 codec warnings), the
  10,000 file fuzz with the 25 keys in valid and out of range values, the theme fixtures (retro
  and nature-calm included), the encoded docs and the TV choices; the 2.7 runs stay beside them.

## RFC-THEME-1: the look reaches the launcher through its ThemeProvider

Issues #1279, #1649, #1094 and #1290: Android 11 firmware of Xiaomi and TCL refuses the shell's sync
push into `/sdcard/Android/data/com.gathin.tvhome/files` ("PushAsset config.json: nonzero_exit(1)"),
and on Android 14 the push succeeds but the file is the shell's, so the launcher cannot open it and
ignores the look. Launcher 2.5 (versionCode 6) and later export `ThemeProvider` (authority
`com.gathin.tvhome.theme`, guarded by `WRITE_SECURE_SETTINGS`), which upstream's `tools/theme.sh`
uses. Smallest change: frozen signatures grow, nothing is removed. `:core:model` gets `FileRoute`
(SYNC, THEME_PROVIDER), a defaulted `route` on `Op.PushAsset` and `Op.RemoveFile`, and
`ProbeKind.LAUNCHER_FILE_SHA256` (takes a Path). `ConfigTransport.writePlanSteps` and
`resetPlanSteps` take a defaulted `route`. With THEME_PROVIDER the renderer stages the file in TMP
(the push now runs before an op's lines; no earlier op has both, so no earlier plan hash changes),
writes it with a fixed template (`content write`, a copy from the staged file as the fallback when
the content tool answers anything, the staged file removed) and checks it with a second template
(`content read | sha256sum`), so the check is the launcher's view; a removal is one quoted
`content delete` line. Both templates take one value from a two-entry list (`config.json`,
`wallpaper.jpg`) and have golden files. The planner picks THEME_PROVIDER on Android 10 and later
(`content write` exists from 10) when the launcher the look reaches has the provider (versionCode 6
and up, or not read, the LookGates rule); Android 8 and 9 and older launchers keep SYNC. Journals,
plan files and changes.json write `route` only when it is not SYNC, so every earlier file reads and
writes the same. Failure policies are unchanged; Setup's look send still CONTINUEs, but Done now has
a line for a refused look and the log and the report name the send and the TV's answer.

## RFC-RECONNECT-1: a saved TV is found again at a new address

Issue #1578: on a mesh Wi-Fi the TV moved from .111 to .104, the app tried only the saved address
and showed the debugging step for good, while "Add a TV" found the TV and asked "Is this your
TV?". Smallest change: `SessionManager` gets `openKnown(endpoint, expected)`, the handshake with
the app's signature only (`AdbConnection.open(sendPublicKey = false)` stops with `Auth.Denied`
where the TV does not take the signature, before the public key that raises the Allow dialog),
then the TvId check as `open` does (`Policy.WrongTv`). `TvAccess.session` polls the saved
addresses as before and, after one round, searches the network beside the polling with the
Discovery that "Add a TV" uses (15 s a search, NSD resolves one service at a time, again every 30 s while the debugging step waits);
each TV found elsewhere with port 5555 open gets `openKnown`, so a different TV is never connected
and a TV that never allowed this phone sees no dialog. The TV that answers is saved at its new
address (its plain TCP endpoints replaced). Setup's connect gives no identity check to a TV whose
TvId is saved on this phone and that took its key without asking. No other interface changes.

## RFC-HELPER-1: the Home button helper, a test build opt-in

Test build only (0.4.8 track, -Pcyt.homeHelper=true; the release bundles no marker, so its planner
refuses the helper and no screen offers it). The owner accepted an opt-in accessibility helper on
2026-10-28; the design is research/remote-buttons/PLAN.md sections 4.3 to 4.9. The TV keeps its own
home screen and its setup wizard; the launcher's HomeKeyService, with stock_home_redirect naming
that home screen and key_filter false, opens the new home screen whenever the TV's own comes up.
Frozen signatures grow, nothing is removed:

- `Op` (5.2) gets `AccessibilityAdd(expected, component)`, `AccessibilityRemove(expected,
  component, offIfEmpty)` and `ArmHelperWatchdog(component, seconds, token, offIfEmpty)`. Each
  renders one `HelperLines` template (core/ops): a TV-side compare-and-set of
  enabled_accessibility_services for the add and the planner's removal, a sed removal of that one
  entry for Undo's inverse and the laptop scripts, and a safety timer that does the same removal
  unless the phone defuses it. The renderer takes `com.gathin.tvhome/.HomeKeyService` and nothing
  else, never a list of more than 16, and none of them writes accessibility_enabled 1.
- `Choices` gets `homeHelper` (default false); `PlanKind` gets `HOME_HELPER_OFF` and `PlanRequest`
  `HomeHelperOff`. `DefaultPlanner` takes `homeHelper` (default `HomeHelper.BUILD_ON`), last.
- `UndoOrder` gets `Target.Helper` and `Phase.HELPER`, between package states and settings.
- The engine runs the helper's guarded section (`SECTION_HELPER`) with its own step R vocabulary
  (our entry out, accessibility_enabled 0, the timer defused, Home), looks twice at a focus on the
  TV's own home screen before it rolls back, and checks the helper's priors for staleness.

Not done, by decision: TCL's AUTO_START app-op, button mapping (app_keys), a probe of
`dumpsys accessibility` (the snapshot's two settings and the overlay give Manage's states).

## RFC-REACH-1: the network is a hint, not a gate; the debugging step says which case it is

A user on 0.4.6.1 waited for good at "Waiting for the TV..." with USB debugging on and the phone on
Wi-Fi. D19 detects a different network by link subnet, and `TvAccess` used that as a gate: when the
phone's subnet was not the TV's `lastSubnet` (a new or reset router, a mesh node or band on another
subnet, a guest network) the step showed `WrongNetwork` and neither probed the saved addresses nor
searched the network (RFC-RECONNECT-1), and `quiet()` gave up the same way. The TvId check (D37:
`open` and `openKnown` with the expected TvId) is what keeps a different TV out, so the gate bought
no safety. Change: on any Wi-Fi the saved addresses are probed and the network searched; only a
phone that is not on Wi-Fi is `WrongNetwork`; a TV reached on a new network saves it as
`lastSubnet`. The subnet stays a D19 hint, shown to the person. Additive, no frozen signature
changes: `TvAccess.check` (a `StateFlow<ReachCheck>`, the live "What the app sees" rows, `Seen`
per row) and `TvAccess.lastHandshake(tvId)`; one log line per change of state (fixed words and
counts, never an address or a subnet). The sheet (`DebugStepSheet`, now in `ReachSheet.kt`) has its
own copy for not on Wi-Fi, the Allow prompt and debugging off, "Show details" with the rows, a hint
after 30 s with nothing found, "Report a problem" (`ReportContext` case `REACH_<STATE>`) and "Copy
connection details". That block (`ConnectionDetails`, made when asked for, also from App settings)
has the phone's network kinds (`PhoneNetwork`, read by the app from ConnectivityManager with
ACCESS_NETWORK_STATE only), each saved TV's addresses cut to their last part with port 5555 and the
known-TV handshake, a fresh 10 s search, the last 40 "access:" lines and the step's state. It keeps
RFC-RECONNECT-1's rules: `openKnown` only, so no Allow dialog on any TV, and it saves nothing.
`ManageDeps` gets `phoneNetwork` (with a default).

0.4.8, "Enter the TV's address" on the sheet (the beta4 follow-up; additive): shown in the debugging-off
case with "Show details" or the 30 s hint, it turns the sheet into Setup's address entry (S1c, now
`AddressEntry` in `:ui:design`, the same words and field) and hands the text to
`TvAccess.tryAddress(tvId, text)`, which takes Setup's rule (`isHomeAddress`) and says no otherwise. The
wait tries the typed address first from the next round, with a saved address's rules: on a network
other than the setup's only `openKnown` (no Allow dialog on a stranger's TV), the TvId check always.
It is saved in place of the saved addresses only once this TV answers there; a different TV there drops
it and the wait goes on. `ReachCheck.typed` is its row in "What the app sees"; the log says only that
an address was typed, never which.

0.4.8, the debugging state after Finish (the beta4 follow-up): `SessionEnd.switchOff` recorded "on"
whenever port 5555 still answered after debug_off, and the Android emulator's own process listens on
5555 whatever the TV's adbd does, so the record said "on" and the sheet never said why debugging is
off. A port that stays open is now asked with `openKnown` (6 s) and an unstored read of adb_enabled
(`PlanRunner.readSnapshot`, the snapshot's settings read): on when this TV answers with it not 0 or an
adbd there refuses only the key, off when nothing answers as an adbd or it reads 0, unknown when the
read fails or a different TV answers.

0.4.8, one network search (the beta4 follow-up): every `discover()` of `NsdDiscovery` starts its own
NSD listeners and resolve queue, so the step's search, "Copy connection details" and Setup's search
ran side by side, resolving and probing everything twice (older Android refuses a second resolve
while one runs). `SharedDiscovery` (`:core:flows`, around the frozen `Discovery`, wired in AppGraph)
runs one search while anything collects and stops it with the last collector; a late collector first
gets every TV found so far as `Found`, then the changes.

## RFC-INSTALL-1: the streamed install is for user 0, and an unreadable APK says why

Twenty reports from 0.3.x to 0.4.7-beta4, all Google TVs on Android 11 and 12 (Sony BRAVIA VH2 and
VH22, TCL G05, G07 and G09, Xiaomi jaws, Hisense songshan) and one TiVo Stream 4K, ended "Install
com.gathin.tvhome: failed: verify: installed apk sha256 <x>, got no readable apk". The package dump
showed versionCode 9, but `pm path` named no APK, `am start -W` of the launcher failed, its provider
did not answer and its files folder was never made: a package on the TV that is not installed for
user 0. `pm uninstall --user 0` leaves a package like that when another Android user still has it,
and the streamed install, `cmd package install -S` with no user, is "for all users"; on Android 11
and 12 that installs over such a package without installing it for user 0 again
(PackageManagerService.updateSettingsInternalLI: "This does not apply to USER_ALL, which here means
only install on users for which the app is already installed"; Android 13 changed it). The pushed
install already names `--user 0` (SECURITY 3.2). Change, no frozen signature changes:

- `:adb:wire`: `AdbService.ExecInstall` is spelled `exec:cmd package install --user 0 -S <size>`, the
  same user as `pm install --user 0`, `pm uninstall --user 0` and `set-home-activity --user 0`. A
  fresh install no longer reaches other users, so Undo's `pm uninstall --user 0` removes it whole.
- `:core:probe`: `InstalledApkReader` beside the frozen Prober (like `LaunchableApps`), with one fixed
  read-only script (`ProbeScripts.installedApkState`, golden file): one `pm path`, then only the count
  of base.apk lines, the exit code of the hash command, the user in front and the package dump's
  "User N:" lines leave the TV, never a path or a hash. `InstalledApkParser` reads them as untrusted.
- `:core:engine`: when the installed APK's hash reads nothing, the install check asks it once, adds
  the cause in fixed words to the failure ("got no readable apk (on the TV but not installed for user
  0, installed for 1 other user)") and logs one line with the counts and flags. The check stays a
  hash check: the extra read never turns a failure into a pass, and the home screen switch is still
  never opened without its launcher.
- `:testing:fake-adbd`: the fake models a package that is not installed for user 0 and Android 11 and
  12's install rule, and takes the new install line.

Not done: a fallback read of the APK through the dump's codePath. It would hash the right file for a
package that still cannot start for user 0, so it would hide the cause instead of fixing it.

0.4.8-beta2, the TV's free storage after a streamed install the TV ended (#3221, #3101, #2851; additive,
no frozen signature changes): when the launcher's stream ends with a protocol error and the installed
APK does not hash to it, the engine reads the frozen `Prober.freeDataMb` once (the existing
`ProbeScripts.FREE_DATA`, `df -k /data`, already in the golden file) and appends " [TV storage: N MB
free]" to the failure; a read that fails leaves the words as before and logs one line. Its parser
(`FreeDataParser`) now takes only 1K-block output (the header says so), the busybox row wrapped after
a long filesystem name, and numbers only. `InstallStorage` (`:core:engine`) holds the words and the
"clearly low" rule (under 200 MB or under twice the APK), which the stop card uses for its new body.
The chooser card of #3301 and #3305 is words only: `SetupMachine` reads the check's "got
android/com.android.internal.app.ResolverActivity" from the journal and names the Home claimants the
person installed on the card (never in the log or the report). Two `CopyKey`s are added.
