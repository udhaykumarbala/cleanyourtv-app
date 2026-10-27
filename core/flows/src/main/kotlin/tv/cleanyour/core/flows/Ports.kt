package tv.cleanyour.core.flows

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.DockRanking
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc

// Seams the flows need that section 5 does not declare. They are W8's own: the app wires them to
// Android or to the section 5 objects, the tests to small fakes. None of them changes a frozen type.

// Every user-visible sentence a machine produces (stop cards, notifications, the credential prompt)
// comes from string resources through this port, so the copy stays in strings.xml with ICU
// MessageFormat and the machines stay pure Kotlin. The app maps each key to a resource id.
interface FlowCopy {
    fun text(key: CopyKey, args: Map<String, Any> = emptyMap()): String
    fun date(epochMs: Long): String          // "19 Oct"
    fun dateTime(epochMs: Long): String      // "19 Oct, 14:32"
}

// One entry per machine-produced string; `res` is the resource name in the module that shows it.
enum class CopyKey(val res: String) {
    GATE_TITLE("gate_title"),
    GATE_SUBTITLE("gate_subtitle"),                       // {tv}
    GATE_SUBTITLE_RESET_KEY("gate_subtitle_reset_key"),
    GATE_SUBTITLE_DELETE_ALL("gate_subtitle_delete_all"),
    NOTIFY_PROGRESS("notify_progress"),                   // {tv} {percent}
    NOTIFY_LOOK("notify_look"),
    BANNER_FIRE("banner_fire"),
    BANNER_TCL("banner_tcl"),
    BANNER_MIBOX("banner_mibox_usb"),

    STOP_TV_NOT_FOUND_TITLE("stop_tv_not_found_title"), STOP_TV_NOT_FOUND_BODY("stop_tv_not_found_body"),
    STOP_TV_NOT_FOUND_PRIMARY("stop_tv_not_found_primary"), STOP_TV_NOT_FOUND_SECONDARY("stop_tv_not_found_secondary"),
    STOP_WRONG_NETWORK_TITLE("stop_wrong_network_title"), STOP_WRONG_NETWORK_BODY("stop_wrong_network_body"),
    STOP_WRONG_NETWORK_PRIMARY("stop_wrong_network_primary"), STOP_WRONG_NETWORK_SECONDARY("stop_wrong_network_secondary"),
    STOP_WIFI_OFF_BODY("stop_wrong_network_off_body"),
    STOP_IDENTITY_CHANGED_TITLE("stop_identity_changed_title"), STOP_IDENTITY_CHANGED_BODY("stop_identity_changed_body"),
    STOP_IDENTITY_CHANGED_PRIMARY("stop_identity_changed_primary"),
    STOP_TV_NOT_SUPPORTED_TITLE("stop_tv_not_supported_title"), STOP_TV_NOT_SUPPORTED_BODY("stop_tv_not_supported_body"),
    STOP_TV_NOT_SUPPORTED_PRIMARY("stop_tv_not_supported_primary"),

    STOP_HOME_SETUPWRAITH_TITLE("stop_home_setupwraith_title"), STOP_HOME_SETUPWRAITH_BODY("stop_home_setupwraith_body"),
    STOP_HOME_SETUPWRAITH_PRIMARY("stop_home_setupwraith_primary"), STOP_HOME_SETUPWRAITH_SECONDARY("stop_home_setupwraith_secondary"),
    STOP_HOME_SETUPWRAITH_WIFI_BODY("stop_home_setupwraith_wifi_body"), STOP_HOME_SETUPWRAITH_WIFI_PRIMARY("stop_home_setupwraith_wifi_primary"),   // D3
    STOP_HOME_SETUPWRAITH_WIFI_UNKNOWN_BODY("stop_home_setupwraith_wifi_unknown_body"),   // RF-LAUNCHER-240 F6
    STOP_HOME_OTHER_LAUNCHER_TITLE("stop_home_other_launcher_title"), STOP_HOME_OTHER_LAUNCHER_BODY("stop_home_other_launcher_body"),   // {launcher}
    STOP_HOME_OTHER_LAUNCHER_PRIMARY("stop_home_other_launcher_primary"), STOP_HOME_OTHER_LAUNCHER_SECONDARY("stop_home_other_launcher_secondary"),   // {launcher}
    STOP_HOME_UNKNOWN_TITLE("stop_home_unknown_title"), STOP_HOME_UNKNOWN_BODY("stop_home_unknown_body"),
    STOP_HOME_UNKNOWN_PRIMARY("stop_home_unknown_primary"), STOP_HOME_UNKNOWN_SECONDARY("stop_home_unknown_secondary"),
    STOP_HOME_USER_SAID_NO_TITLE("stop_home_user_said_no_title"), STOP_HOME_USER_SAID_NO_BODY("stop_home_user_said_no_body"),
    STOP_HOME_SETTINGS_OPEN_BODY("stop_home_settings_open_body"),   // issue #629
    STOP_HOME_USER_SAID_NO_PRIMARY("stop_home_user_said_no_primary"), STOP_HOME_USER_SAID_NO_SECONDARY("stop_home_user_said_no_secondary"),
    STOP_HOME_TIMED_OUT_TITLE("stop_home_timed_out_title"), STOP_HOME_TIMED_OUT_BODY("stop_home_timed_out_body"),
    STOP_HOME_TIMED_OUT_PRIMARY("stop_home_timed_out_primary"), STOP_HOME_TIMED_OUT_SECONDARY("stop_home_timed_out_secondary"),
    STOP_CONNECTION_LOST_TITLE("stop_connection_lost_title"), STOP_CONNECTION_LOST_BODY("stop_connection_lost_body"),   // {done} {total}
    STOP_CONNECTION_LOST_PRIMARY("stop_connection_lost_primary"), STOP_CONNECTION_LOST_SECONDARY("stop_connection_lost_secondary"),
    STOP_CONNECTION_LOST_TERTIARY("stop_connection_lost_tertiary"),
    STOP_STEP_FAILED_TITLE("stop_step_failed_title"), STOP_STEP_FAILED_BODY("stop_step_failed_body"),   // {done} {total}
    STOP_RESUME_TITLE("stop_resume_title"), STOP_RESUME_BODY("stop_resume_body"),   // {tv} {done} {total}
    STOP_RESUME_HOME_BODY("stop_resume_home_body"),
    STOP_RESUME_PRIMARY("stop_resume_primary"), STOP_RESUME_SECONDARY("stop_resume_secondary"), STOP_RESUME_TERTIARY("stop_resume_tertiary"),
    STOP_RESUME_CHANGE_TITLE("stop_resume_change_title"), STOP_RESUME_CHANGE_BODY("stop_resume_change_body"),   // {tv}; a Manage write
    STOP_HOME_PUT_BACK_TITLE("stop_resume_home_title"), STOP_HOME_PUT_BACK_PRIMARY("stop_resume_home_primary"),
    STOP_HOME_PUT_BACK_SECONDARY("stop_resume_home_secondary"),
    STOP_RECONNECT_HOME_BODY("stop_reconnect_home_body"),   // {tv}; R1 R-11
    STOP_INSTALL_FAILED_TITLE("stop_install_failed_title"), STOP_INSTALL_FAILED_BODY("stop_install_failed_generic_body"),
    STOP_INSTALL_FAILED_PRIMARY("stop_install_failed_primary"),
    STOP_UNDONE_TITLE("stop_undone_title"), STOP_UNDONE_BODY("stop_undone_body"), STOP_UNDONE_PRIMARY("stop_undone_primary"),
    STOP_PROBE_FAILED_TITLE("stop_probe_failed_title"), STOP_PROBE_FAILED_BODY("stop_probe_failed_body"),
    STOP_PROBE_FAILED_PRIMARY("stop_probe_failed_primary"), STOP_PROBE_FAILED_SECONDARY("stop_probe_failed_secondary"),
    STOP_PLAN_REFUSED_TITLE("stop_plan_refused_title"), STOP_PLAN_REFUSED_BODY("stop_plan_refused_body"),   // 0.3.4
    STOP_NOT_BACK_TITLE("stop_not_back_title"), STOP_NOT_BACK_BODY("stop_not_back_body"),
    STOP_NOT_BACK_PRIMARY("stop_not_back_primary"), STOP_NOT_BACK_SECONDARY("stop_not_back_secondary"),
}

// DockRanking.pick behind a seam, so the flows do not call W4's object before it exists.
fun interface DockPicker {
    fun pick(profile: Profile?, installed: Set<PackageName>, hasInputs: Boolean, liveTv: PackageName?, locale: String): List<PackageName>
    companion object { val Ranking = DockPicker { p, i, h, l, loc -> DockRanking.pick(p, i, h, l, loc) } }
}

// LauncherConfigCodec.encode behind a seam, for the same reason.
fun interface ConfigEncoder {
    fun encode(doc: LauncherConfigDoc): ByteArray
    companion object { val Codec = ConfigEncoder { LauncherConfigCodec.encode(it) } }
}

// Whether the phone has a screen lock (D17: without one the prompt is skipped and the plan screen
// shows the grey line). The app answers from KeyguardManager.isDeviceSecure.
fun interface ScreenLock { fun available(): Boolean }

// The phone's ADB key as App settings > About shows it, and "Reset my key" (W2's signer behind it).
interface KeyInfo {
    val fingerprint: String
    val backing: String          // STRONGBOX, TEE or WRAPPED_SOFTWARE (adb.wire.KeyBacking names)
    suspend fun reset()
}

// The launcher's card prompt answer on the TV (M3c). 0.1 has no provider, so the default answers
// null (unknown) and the screen offers the manual outcome buttons; W4's screenshot probe or the
// 0.2 provider can answer later without touching the machine.
enum class CardAnswer { SHOWN, HIDDEN }
fun interface CardAnswerDetector {
    suspend fun answer(session: TvSession, pkg: PackageName): CardAnswer?
    companion object { val Unknown = CardAnswerDetector { _, _ -> null } }
}

// "Check for updates" (D12): one GET on user action. Returns the newer version name, "" when up to
// date, or null when the check failed.
fun interface UpdateChecker { suspend fun latestNewerThan(current: String): String? }

// What the store build of the app offers (STORES-PLAN 1). The GitHub and F-Droid build (FULL)
// offers everything. The Google Play build (PLAY) has no "Check for updates" (Play updates the app)
// and no add-ons (Play allows no app downloads from outside Play): the machines then neither show
// nor run them, so no button leads nowhere. `updatesOff` false (PLAY) means the TV's software
// updaters always stay on (the planner's keepUpdatesOn): Switched off then offers no Off pill and no
// Undo for an updater. The app passes its flavour's rules into SetupDeps and ManageDeps; the default
// is the full build.
data class StoreRules(val updateCheck: Boolean = true, val addOns: Boolean = true, val updatesOff: Boolean = true) {
    companion object {
        val FULL = StoreRules()
        val PLAY = StoreRules(updateCheck = false, addOns = false, updatesOff = false)
    }
}

// The redacted diagnostics text (SECURITY 4.6: IPs masked, serials and tokens replaced), shown to
// the user before the share sheet. The app builds it from the redacting logger's ring. `clear`
// deletes the log itself (its files and the ring), for "Delete everything".
fun interface Diagnostics {
    suspend fun redactedText(): String
    suspend fun clear() {}
}

// One diagnostics line per wizard milestone (connect, probe, plan, run), so "Report a problem"
// has something to show. The app writes them through its redacting logger; the machines never
// pass a serial, an address or a package list, only outcomes, counts, stable codes (LogCodes), the
// TV's model and device codename, and the one op that failed with its package (0.3.2 B3).
fun interface FlowLog {
    fun line(text: String)
    companion object { val None = FlowLog {} }
}

// Foreground or background, from the single activity's start and stop (D7's 10 minute rule).
class AppForeground(initial: Boolean = false) {
    private val state = MutableStateFlow(initial)
    val foreground: StateFlow<Boolean> = state.asStateFlow()
    fun set(foreground: Boolean) { state.value = foreground }
}

// The launcher component everything targets (PRODUCT-SPEC 3.4).
object Launcher {
    val pkg = PackageName("com.gathin.tvhome")
    val home = Component(pkg, ".MainActivity")
}
