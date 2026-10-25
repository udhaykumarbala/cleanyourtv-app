// FROZEN INTERFACE (PRODUCT-SPEC section 5.1, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W1.

package tv.cleanyour.core.model

// R0: 2 the grammars of SECURITY 3.1, declared once; value classes and the renderer share them
val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63}){1,15}$")
val CLASS_RE = Regex("^\\.?[A-Za-z_][A-Za-z0-9_\$.]{0,199}$")
const val COMPONENT_PAT = "[A-Za-z][A-Za-z0-9_]{0,63}(\\.[A-Za-z][A-Za-z0-9_]{0,63}){1,15}/\\.?[A-Za-z_][A-Za-z0-9_\$.]{0,199}"
val COMPONENT_LIST_RE = Regex("^$COMPONENT_PAT(:$COMPONENT_PAT){0,15}$")
val SHA256_RE = Regex("^[0-9a-f]{64}$")
val RUN_TOKEN_RE = Regex("^[0-9a-f]{16}$")
val TV_ID_RE = Regex("^[0-9a-f]{16}$")
val FILE_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

@JvmInline value class TvId(val value: String) { init { require(TV_ID_RE.matches(value)) } }        // R0: 3; first 16 hex of sha256(serial + device)
@JvmInline value class PackageName(val value: String) { init { require(value == "android" || (PACKAGE_RE.matches(value) && value.length <= 255)) } }   // RFC-W1-2: also the framework package
@JvmInline value class Sha256(val hex: String) { init { require(SHA256_RE.matches(hex)) } }
@JvmInline value class PlanId(val value: String)        // ULID
@JvmInline value class RunToken(val value: String) { init { require(RUN_TOKEN_RE.matches(value)) } }   // R0: 3
data class Component(val pkg: PackageName, val cls: String) {                                       // R0: 3
    init { require(CLASS_RE.matches(cls) && pkg.value.length + 1 + cls.length <= 300) }
    val flat: String get() = "${pkg.value}/$cls" }

@RequiresOptIn(level = RequiresOptIn.Level.ERROR) annotation class RawShell
class ShellLine @RawShell constructor(val text: String) // only :core:ops (renderer) and :core:probe (fixed scripts) opt in

sealed interface Endpoint { val host: String; val port: Int
    data class PlainTcp(override val host: String, override val port: Int = 5555) : Endpoint
    data class Tls(override val host: String, override val port: Int, val serviceName: String?) : Endpoint }

data class DeviceFacts(val brand: String, val manufacturer: String, val model: String, val device: String,
    val serialLast4: String, val sdk: Int, val release: String, val fingerprint: String, val abis: List<String>,
    val ramMb: Int, val hasGms: Boolean, val adbSecure: Boolean, val adbFeatures: Set<String>,
    val installRefusal: Boolean, val locale: String)

data class TrustRecord(val adbKeyId: String, val tlsCertSha256: Sha256?, val firstConnectedAt: Long, val pairedAt: Long?)

data class Tv(val id: TvId, val nickname: String, val room: String?, val facts: DeviceFacts,
    val endpoints: List<Endpoint>, val lastSubnet: String?, val trust: TrustRecord, val profileId: String?,
    val level: Level, val launcher: LauncherState?, val wizard: WizardDraft?, val lastSeenAt: Long,
    val debugging: DebugState)                                                                       // R0: 16
enum class Level { SIMPLE, CUSTOM }
enum class DebugState { ON, OFF, UNKNOWN }   // R0: 16; what the Manage chip shows (D7), set from the last connect or debug_off
data class LauncherState(val versionCode: Long, val signer: Sha256, val overlayDigest: Sha256?, val ourOverlayDigest: Sha256?)
// R0: 2 was referenced and never declared; persisted on every committed choice (ARCHITECTURE 2.2); W8 may add fields, never remove
data class WizardDraft(val step: Int, val level: Level, val kept: Set<PackageName>, val extraOff: Set<PackageName>,
    val dock: List<PackageName>?, val themeId: String?, val keepDebuggingOn: Boolean, val planId: PlanId?, val updatedAtMs: Long,
    val curtain: Boolean = true)   // RFC-CURTAIN-1: "Show the progress on the TV" (Custom only; Simple always shows it)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val endpoint: Endpoint, val attempt: Int) : ConnectionState
    data class AwaitingAllow(val sinceMs: Long, val fingerprint: String) : ConnectionState
    data class Connected(val endpoint: Endpoint, val features: Set<String>, val maxPayload: Int) : ConnectionState
    data class Reconnecting(val reason: AdbError, val attempt: Int, val nextAtMs: Long) : ConnectionState
    data class WaitingForBoot(val sinceMs: Long, val deadlineMs: Long) : ConnectionState
    data class Failed(val error: AdbError) : ConnectionState }

sealed interface AdbError {
    sealed interface Transport : AdbError { data object Unreachable : Transport; data object Refused : Transport
        data class Timeout(val phase: String) : Transport; data object Closed : Transport
        data class Protocol(val detail: String) : Transport; data object NoShellV2 : Transport }
    sealed interface Auth : AdbError { data object Denied : Auth; data object PendingTooLong : Auth
        data class TrustChanged(val oldPin: Sha256?, val newPin: Sha256?) : Auth }
    sealed interface Device : AdbError { data class InstallFailed(val code: String) : Device; data object NotEnoughSpace : Device
        data object InstallRefusedByFirmware : Device; data object Busy : Device }
    data class NonZeroExit(val exit: Int, val stderrHead: String) : AdbError
    sealed interface Policy : AdbError { data class ProtectedPackage(val pkg: PackageName) : Policy
        data class GuardFailed(val step: Int, val reason: String) : Policy; data class Stale(val diff: List<String>) : Policy
        data class WrongTv(val expected: TvId, val found: TvId) : Policy } }
class AdbException(val error: AdbError) : Exception(error.toString())   // R0: 7 the one exception type TvSession throws

enum class EnabledState { ENABLED, DISABLED_USER, DISABLED, DEFAULT }
data class PackageState(val enabled: EnabledState, val system: Boolean, val versionCode: Long, val signer: Sha256?,
    val persistent: Boolean, val tvInput: Boolean, val label: String?, val installedByUs: Boolean)
data class HomeClaimant(val component: Component, val priority: Int)
data class HomeResolution(val resolved: Component?, val claimants: List<HomeClaimant>)
data class HdmiSource(val port: Int, val cecName: String?, val tifInputId: String?, val behindSwitch: Boolean)
enum class SnapshotReason { BASELINE, PRE_PLAN, MANUAL, POST_PLAN }
data class Snapshot(val tvId: TvId, val takenAtMs: Long, val reason: SnapshotReason, val facts: DeviceFacts,
    val packages: Map<PackageName, PackageState>, val home: HomeResolution, val settings: Map<SettingKey, String?>,
    val ime: Component?, val screensaver: List<Component>, val launcherOverlay: ByteArray?, val hdmi: List<HdmiSource>,
    val contentHash: Sha256)   // sha256 of SnapshotCodec.canonical(this) with contentHash blank (R0: 15)

enum class SettingsNs { SYSTEM, SECURE, GLOBAL }
// R0: 8 the 0.1 subset of the SECURITY 3.3 allowlist, each key with its value shape; the renderer refuses a value
// that does not match; adding a key is an RFC
enum class SettingKey(val ns: SettingsNs, val key: String, val valueShape: Regex) {
    SCREENSAVER_ENABLED(SettingsNs.SECURE, "screensaver_enabled", Regex("^[01]$")),
    SCREENSAVER_COMPONENTS(SettingsNs.SECURE, "screensaver_components", COMPONENT_LIST_RE),
    ENABLED_ACCESSIBILITY(SettingsNs.SECURE, "enabled_accessibility_services", COMPONENT_LIST_RE),
    ACCESSIBILITY_ENABLED(SettingsNs.SECURE, "accessibility_enabled", Regex("^[01]$")),
    ADB_ENABLED(SettingsNs.GLOBAL, "adb_enabled", Regex("^0$")),
    ADB_WIFI_ENABLED(SettingsNs.GLOBAL, "adb_wifi_enabled", Regex("^0$")),
    DEVICE_NAME(SettingsNs.GLOBAL, "device_name", Regex("^[A-Za-z0-9 ._-]{1,32}$")) }

enum class GroupDefault { DISABLE, ASK, KEEP }
enum class Platform { ANDROID_TV, GOOGLE_TV, FIRE_OS, TCL_CN }
enum class Quirk { HDMI_SWITCH, HOME_TAKEOVER_SETUPWRAITH, EXTRA_HOME_CLAIMERS, REENABLED_AT_BOOT, REBOOT_COMMAND,
    WIRELESS_DEBUG_PAIRING_REQUIRED, USB_POWER_CUT_WHILE_DEBUGGING, SETTLE_BEFORE_REBOOT, STOCK_LAUNCHER_KEEP_ENABLED,
    EXACT_PROFILE_MATCH }
data class PackageEntry(val name: PackageName, val note: String?)
data class PackageGroup(val id: String, val title: String, val default: GroupDefault, val packages: List<PackageEntry>)
data class ProfileMatch(val models: List<String>, val devices: List<String>, val brand: String?, val sdkMin: Int?, val sdkMax: Int?, val fingerprintPrefix: String?)
// Test build (0.3.5-test1): `candidate` marks a list made from reports of the same model and not
// tested yet. Only the test build bundles one (core/spec/candidates, -Pcyt.candidates=true).
data class TestedInfo(val by: String?, val firmware: String?, val daysLivedWith: Int?, val rebootTested: Boolean, val candidate: Boolean = false)
data class Profile(val id: String, val match: ProfileMatch, val platform: Platform, val stockLaunchers: List<Component>,
    val liveTv: PackageName?, val setupWraith: PackageName?, val quirks: Set<Quirk>, val groups: List<PackageGroup>,
    val neverDisable: Set<PackageName>, val suggestedDock: List<PackageName>, val tested: TestedInfo?, val sourceSha256: Sha256) {
    // Test build: a candidate list (TestedInfo.candidate), offered only after the person agrees.
    val isCandidate: Boolean get() = tested?.candidate == true
}
