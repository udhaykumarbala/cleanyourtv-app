# Clean Your TV for iOS

This is the native SwiftUI companion app for iPhone and iPad. It targets iOS 26 and newer.
The existing Android app manages Android TVs over ADB; it does not clean or modify the iPhone.
The Clean Your TV launcher and TV progress curtain remain Android apps installed on the TV.

## Build

Open `CleanYourTV.xcodeproj` in Xcode 27 or newer, select the `CleanYourTV` scheme and an
iOS 26 simulator or device, then build. The unit tests are in the `CleanYourTVTests` scheme target.

## Port status

The native app contains the welcome screen, local Bonjour discovery for `_adb._tcp`, a
Keychain-backed RSA-2048 ADB host key, the ADB authentication handshake and TV identity read. The
key is used for ADB authentication, stays on the iPhone, and is not reset when the app is
reinstalled. After approval on the TV, the app reads `getprop`, installed and disabled package
lists, the current HOME resolver and seven explicitly allowlisted secure/global settings. Parsers
enforce output limits and strict formats. The pinned 131-profile community catalog is bundled and
matched by model/device, brand, Android API and fingerprint. A safety review applies the package
denylist, profile `neverDisable` entries, persistent-service and TV-input checks, HOME claimants,
the active keyboard and screensaver, and known stock/Fire TV launchers. It checks requested
permissions for each enabled profile-listed package (up to 64 per analysis) and protects packages
requesting `TV_INPUT_HARDWARE`; missing or unscanned permission data blocks that package. Incomplete
package flags or missing HOME/input observations block recommendations.

Only selected `reviewCandidate` packages can be applied; optional and protected packages cannot be
changed. Before applying, the app repeats the full safety scan and requires its snapshot digest to
match the reviewed plan. It saves and verifies a per-TV restore point on the iPhone before issuing
`pm disable-user --user 0`; the package plan never uninstalls packages or changes system settings. Each change is
verified against the TV package inventory. A partial failure leaves the restore point active.
Restore compares each package's live state with its saved pre-change state, makes only the required
enable/disable-user changes, verifies them, and retains the point if restoration is incomplete.
Restore data is stored in the app's Application Support directory and does not survive deleting the
app; keep the app and this iPhone until restoration is complete. Review plans and restore points
are bound to TV/profile digests and protected against accidental file edits.

Still to port are Android Wireless Debugging TLS pairing, broader TV discovery, the full safety
evaluation of other live execution guards, launcher configuration (theme and dock) and the remaining
management screens. Apply/restore should be validated on each supported Fire TV/Android TV model
before relying on it for recovery.

On a Fire TV, Fire OS ignores `set-home-activity` and protects Amazon's launcher, so the package plan
cannot change the home screen. The separate "Clean Your TV as the Home screen" card (Fire TV only, after a
scan) installs two bundled, SHA-256-pinned APKs over ADB (`cmd package install -S`, streamed on shell v2
stdin): the Clean Your TV launcher (`com.gathin.tvhome`, built from `third_party/cleanyourtv/launcher`) and a
small accessibility service (`tv.cleanyour.homeinterceptor`, source in `firetv-home-interceptor/`) that opens
the launcher when Amazon's Home activity appears. It adds the service to `enabled_accessibility_services`
without dropping the ones already there. "Give Home back to Amazon" switches only that service off and
uninstalls those two packages. Amazon's home is never disabled. This is experimental: an Amazon update can
break it, and the first press of Home waits 0.7 s so Amazon's Settings still open. Amazon's featured-content
autoplay controls must still be changed on the TV.

The bundled catalog is generated from the pinned `third_party/cleanyourtv` submodule at
`9071ec8d2f41826778f6b4c7f5f42dadc22f5b22` and `core/spec` metadata. Regenerate it with
`./gradlew :core:spec:convertProfiles`, then copy the generated `profiles.json` into
`ios/CleanYourTV/`.

The local-network usage prompt and Bonjour service declaration are in the app's `Info.plist`.
