# List review for the d7b0d4a5 bump (upstream main, launcher 3.3)

The shipped lists that changed between b06ab21 and d7b0d4a5, checked package by package against the
denylist (`Denylist.kt`, now with upstream's core guard), each profile's never-disable list and the list
header's KEEP lines. Rule for new rows, as at the 2.7 and b06ab21 bumps: ads and telemetry are default-off
only when the header says a set the profile matches was tested with them off; a new group title is an ask
row of the closest category until it is reviewed; accessibility, the remote, TV inputs, the setup wizard
and home screens are never default-off. The reasons for every departure from upstream are in
`core/spec/upstream-feedback/curation-notes.tsv`; the brand sets are in `UP33-sure-sets.txt`.

67 shipped lists changed upstream: 34 only in their header or notes (no package goes on or off, no new
row), 5 only add names to a header keep line, and 28 change their rows (below). The 111 new folders are
parked (`unconverted.txt`), none is shipped.

## The lists that keep something on now

| List | Kept on at d7b0d4a5 | Here |
|-|-|-|
| nvidia-shield-tv-2019 | com.nvidia.ota, NvCPLUpdater, KeyComboOta (second unit, three days with updates on) | no longer rows; Better back on offers them |
| sony-bravia-4k-vh1 | com.youview.poa (Freeview Play on UK sets) | no longer a row; Better back on offers it |
| sony-bravia-4k-vh21 | the setup wizard (owns the saved Wi-Fi on the Wi-Fi units, casting needs it; upstream now keeps launcherx on too) | never-disable, so the home screen switch is skipped while the wizard claims Home; the Home button helper is the route upstream uses |
| skyworth-swtv-22ae-fhd | com.smartdevice.dailymanage, dailyshortcut, myapp (#1378) | already left out here; the leftOut entries go, still never-disable |
| tcl-43s5k | com.tcl.partnercustomizer (#2138: widget provider of com.mediatek.tv.service) | never-disable through the header keep list; Better back on offers it |
| xiaomi-mitv-ayfr0 | com.google.android.tvrecommendations (re-enabled by com.droidlogic at boot) | no longer a row |
| xiaomi-mitv-mssp3-android9 | com.xiaomi.mitv.updateservice | no longer a row; Better back on offers it |

## The lists with new rows

| List | What changed | Verdict |
|-|-|-|
| amazon-fire-tv-stick-4k-2018 | second unit's group (recess, naatyam, neodelegate, csm.htmlruntime, printspooler), 10 days | provisional ask row |
| hisense-fire-tv-aftcibfbd1 | 65U65SFC unit: five telemetry lines, a Popups and promos group, Alexa, Luna and Gallery lines | telemetry default-off (tested there); the new group a provisional ask row; ffsprovisioneeclient an ask row as on the Amazon lists; easyupgrade a Software updates row |
| hisense-smarttv-4k-ffm-lushan | US second unit: content feeds group, Haystack News, streaming apps | Haystack default-off (preloads); the new group a provisional ask row; streaming apps ask rows |
| hisense-u75-hengshan | 75U75N owner's choices group (DLNA, media center, Miracast tips, room calibration) | provisional ask row |
| insignia-ns55f301na25 | Luna player moves to the personal choice group | an ask row now |
| kalley-r10g | Privacy Sandbox and telemetry group; Realtek partnercustomizer and wifihotspot; two streaming apps | provisional ask row; the rest join ask groups |
| karbonn-swtv-20ae | unit 2 group (Play Games) | provisional ask row |
| nvidia-shield-tv-2019 | Google TV / Play Movies (second unit) | default-off (preloads) |
| oneplus-tv-cebu | three Android 11 lines (log uploader, remote diagnostics, screensaver) | ask rows: the profile matches the Android 10 build; the remote one is safety rule 8 |
| oneplus-tv-dubai | second unit (Android TV 11): remotediagnosing, JioPages, e-manual, Play Games | ask rows (the profile matches Google TV 14); remotediagnosing never-disable, as on cebu |
| philips-50pud7406 | units four to eight; the Google extras group gains five lines | the telemetry title is renamed with its values; the five are ask rows (the group already was) |
| philips-google-tv-ta1 | TA5 and 55OLED718 sets: Batch 3, the TA5 by-choice group, the Mimi group; the broadcast note now titles the stock home group | three provisional ask rows; AirplayAPK (a TV input) only in an ask row; the stock home title renamed with its values |
| philips-google-tv-ta7 | third unit: Privacy Sandbox, feedback, store demo, Health Connect | default-off, tested there with a reboot and the Input key on the real remote |
| sony-bravia-4k-ur3 | bugreportsender (Taiwan unit) | default-off (tracking) |
| sony-bravia-4k-vh21 | second and third units: help, eco and support tiles in the Sony ads group, the bug report service, BRAVIA CAM and seeds services | the ads title renamed with its values, so the tiles are default-off (tested there; vh1 has the help tiles off too); the services are ask rows |
| sony-bravia-vu1 | third unit: Play Games, the Sony auto-installs config | default-off (8 days there) |
| sony-bravia-vu31 | fourth unit: camera2, TTS, Voot | camera2 default-off; TTS an accessibility ask row (denylisted anyway); Voot a streaming ask row |
| tcl-55p7k | 55C655 extras group | provisional ask row |
| tcl-beyondtv2-latin-america | second unit: six groups, three more home content lines; the DO NOT DISABLE paragraph changes | content lines default-off (tested); the six groups provisional ask rows, with TalkBack and TTS accessibility, Assistant, hotword and Alexa voice, Chromecast casting and the media apps streaming rows; usercenter, useragreement, copydatatotv, katniss, pumpkin and mediashell leave never-disable (now ask rows) and six new keep names join it |
| tcl-g08-eu-android12 | AS36 and French AS34 sets: TCL preloads group, Google extras group | provisional ask rows; channelplus stays denylisted (a TV input) |
| tcl-uniontv-latin-america | 32S6500S, L40S6500 and RT41KA units: partner rows, TCL HOME tile, waterfall, ToS screen | promo lines default-off (tested on those units); the leanback partnercustomizer, com.tcl.dashboard and useragreement ask rows, as on the other lists |
| wanbo-x5-pro | second unit, five days: partner setup, Skyworth customization, auto-installs, calendar and contacts providers, Play Movies | the telemetry title renamed with its values; com.sdt.customization leaves never-disable for an ask row; the rest default-off (the two providers are upstream's CORE_OK exceptions) |
| xiaomi-mitv-aesp0 | partnercustomizer note now titles its own group | provisional ask row (it was one) |
| xiaomi-mitv-ayfr0 | Privacy Sandbox, Health Connect, contacts and calendar groups; Netflix token manager; Ambient mode | three provisional ask rows; the token manager a streaming ask row (Netflix is one); Ambient mode a screensaver ask row |
| xiaomi-mitv-mooq0 | com.xiaomi.tvqs.overseas (Q1 75 unit, #1520) | an ask row: contested, the quick settings panel on the Mi Box S 2nd gen |
| xiaomi-mitv-mssp1 | second unit extras, AOSP colour screensaver | provisional ask rows |

Header keep lines that add never-disable names, with no package change: oneplus-tv-y1 (TalkBack),
sony-bravia-4k-ur2 (AirPlay, Vewd service), tcl-40s5400a (fused location), tcl-beyondtv-rt2851 (the
global key service), xiaomi-tv-a-pro-2026 (the gallery, video player and media explorer, for USB media).

No changed list switches off TalkBack, katniss or text to speech by default here, nor a remote,
accessibility or TV input service, a setup wizard, a home screen or a package of upstream's core guard.

## Candidates

Three new upstream folders are lists for TVs we carry candidate lists for: smarttech-apollo-premium-4k
(our smarttech-apollo-premium4k, the Android 11 build), sharp-aquos-tve19c (our sharp-aquos-tve19a, the
same device and firmware) and xiaomi-mitv4i-magnolia (our candidate of the same name, #111). They are
parked; the converter now lets a candidate share its name with a parked folder only, so the magnolia
candidate keeps its id. Whoever curates these compares upstream's list with the candidate first, and the
candidate goes when the curated profile ships.
