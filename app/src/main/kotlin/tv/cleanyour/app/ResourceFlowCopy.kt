package tv.cleanyour.app

import android.content.Context
import android.icu.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.Date
import tv.cleanyour.core.flows.CopyKey
import tv.cleanyour.core.flows.FlowCopy
import tv.cleanyour.feature.connect.R as ConnectR
import tv.cleanyour.feature.manage.R as ManageR
import tv.cleanyour.feature.setup.R as SetupR

// FlowCopy over string resources: every sentence a machine produces lives in the strings.xml of
// the module that shows it, as ICU MessageFormat with named arguments (PRODUCT-SPEC 4). The when
// below is exhaustive, so a new CopyKey without a string fails the build.
class ResourceFlowCopy(context: Context) : FlowCopy {
    private val app = context.applicationContext

    override fun text(key: CopyKey, args: Map<String, Any>): String = format(app, idOf(key), args)

    override fun date(epochMs: Long): String = SimpleDateFormat("d MMM yyyy", locale()).format(Date(epochMs))

    override fun dateTime(epochMs: Long): String = SimpleDateFormat("d MMM, HH:mm", locale()).format(Date(epochMs))

    private fun locale() = app.resources.configuration.locales[0]

    companion object {
        fun format(context: Context, id: Int, args: Map<String, Any>): String {
            val pattern = context.getString(id)
            if (args.isEmpty()) return pattern
            return MessageFormat(pattern, context.resources.configuration.locales[0]).format(args)
        }

        fun idOf(key: CopyKey): Int = when (key) {
        CopyKey.GATE_TITLE -> SetupR.string.gate_title
        CopyKey.GATE_SUBTITLE -> SetupR.string.gate_subtitle
        CopyKey.GATE_SUBTITLE_RESET_KEY -> ManageR.string.gate_subtitle_reset_key
        CopyKey.GATE_SUBTITLE_DELETE_ALL -> ManageR.string.gate_subtitle_delete_all
        CopyKey.NOTIFY_PROGRESS -> SetupR.string.notify_progress
        CopyKey.NOTIFY_LOOK -> SetupR.string.notify_look
        CopyKey.BANNER_FIRE -> ConnectR.string.banner_fire
        CopyKey.BANNER_TCL -> ConnectR.string.banner_tcl
        CopyKey.BANNER_MIBOX -> ConnectR.string.banner_mibox_usb
        CopyKey.STOP_TV_NOT_FOUND_TITLE -> ConnectR.string.stop_tv_not_found_title
        CopyKey.STOP_TV_NOT_FOUND_BODY -> ConnectR.string.stop_tv_not_found_body
        CopyKey.STOP_TV_NOT_FOUND_PRIMARY -> ConnectR.string.stop_tv_not_found_primary
        CopyKey.STOP_TV_NOT_FOUND_SECONDARY -> ConnectR.string.stop_tv_not_found_secondary
        CopyKey.STOP_WRONG_NETWORK_TITLE -> ConnectR.string.stop_wrong_network_title
        CopyKey.STOP_WRONG_NETWORK_BODY -> ConnectR.string.stop_wrong_network_body
        CopyKey.STOP_WRONG_NETWORK_PRIMARY -> ConnectR.string.stop_wrong_network_primary
        CopyKey.STOP_WRONG_NETWORK_SECONDARY -> ConnectR.string.stop_wrong_network_secondary
        CopyKey.STOP_WIFI_OFF_BODY -> ConnectR.string.stop_wrong_network_off_body
        CopyKey.STOP_IDENTITY_CHANGED_TITLE -> ConnectR.string.stop_identity_changed_title
        CopyKey.STOP_IDENTITY_CHANGED_BODY -> ConnectR.string.stop_identity_changed_body
        CopyKey.STOP_IDENTITY_CHANGED_PRIMARY -> ConnectR.string.stop_identity_changed_primary
        CopyKey.STOP_TV_NOT_SUPPORTED_TITLE -> ConnectR.string.stop_tv_not_supported_title
        CopyKey.STOP_TV_NOT_SUPPORTED_BODY -> ConnectR.string.stop_tv_not_supported_body
        CopyKey.STOP_TV_NOT_SUPPORTED_PRIMARY -> ConnectR.string.stop_tv_not_supported_primary
        CopyKey.STOP_HOME_SETUPWRAITH_TITLE -> SetupR.string.stop_home_setupwraith_title
        CopyKey.STOP_HOME_SETUPWRAITH_BODY -> SetupR.string.stop_home_setupwraith_body
        CopyKey.STOP_HOME_SETUPWRAITH_PRIMARY -> SetupR.string.stop_home_setupwraith_primary
        CopyKey.STOP_HOME_SETUPWRAITH_SECONDARY -> SetupR.string.stop_home_setupwraith_secondary
        CopyKey.STOP_HOME_SETUPWRAITH_WIFI_BODY -> SetupR.string.stop_home_setupwraith_wifi_body
        CopyKey.STOP_HOME_SETUPWRAITH_WIFI_PRIMARY -> SetupR.string.stop_home_setupwraith_wifi_primary
        CopyKey.STOP_HOME_SETUPWRAITH_WIFI_UNKNOWN_BODY -> SetupR.string.stop_home_setupwraith_wifi_unknown_body
        CopyKey.STOP_HOME_OTHER_LAUNCHER_TITLE -> SetupR.string.stop_home_other_launcher_title
        CopyKey.STOP_HOME_OTHER_LAUNCHER_BODY -> SetupR.string.stop_home_other_launcher_body
        CopyKey.STOP_HOME_OTHER_LAUNCHER_PRIMARY -> SetupR.string.stop_home_other_launcher_primary
        CopyKey.STOP_HOME_OTHER_LAUNCHER_SECONDARY -> SetupR.string.stop_home_other_launcher_secondary
        CopyKey.STOP_HOME_UNKNOWN_TITLE -> SetupR.string.stop_home_unknown_title
        CopyKey.STOP_HOME_UNKNOWN_BODY -> SetupR.string.stop_home_unknown_body
        CopyKey.STOP_HOME_UNKNOWN_PRIMARY -> SetupR.string.stop_home_unknown_primary
        CopyKey.STOP_HOME_UNKNOWN_SECONDARY -> SetupR.string.stop_home_unknown_secondary
        CopyKey.STOP_HOME_USER_SAID_NO_TITLE -> SetupR.string.stop_home_user_said_no_title
        CopyKey.STOP_HOME_USER_SAID_NO_BODY -> SetupR.string.stop_home_user_said_no_body
        CopyKey.STOP_HOME_SETTINGS_OPEN_BODY -> SetupR.string.stop_home_settings_open_body
        CopyKey.STOP_HOME_USER_SAID_NO_PRIMARY -> SetupR.string.stop_home_user_said_no_primary
        CopyKey.STOP_HOME_USER_SAID_NO_SECONDARY -> SetupR.string.stop_home_user_said_no_secondary
        CopyKey.STOP_HOME_TIMED_OUT_TITLE -> SetupR.string.stop_home_timed_out_title
        CopyKey.STOP_HOME_TIMED_OUT_BODY -> SetupR.string.stop_home_timed_out_body
        CopyKey.STOP_HOME_TIMED_OUT_PRIMARY -> SetupR.string.stop_home_timed_out_primary
        CopyKey.STOP_HOME_TIMED_OUT_SECONDARY -> SetupR.string.stop_home_timed_out_secondary
        CopyKey.STOP_CONNECTION_LOST_TITLE -> SetupR.string.stop_connection_lost_title
        CopyKey.STOP_CONNECTION_LOST_BODY -> SetupR.string.stop_connection_lost_body
        CopyKey.STOP_CONNECTION_LOST_PRIMARY -> SetupR.string.stop_connection_lost_primary
        CopyKey.STOP_CONNECTION_LOST_SECONDARY -> SetupR.string.stop_connection_lost_secondary
        CopyKey.STOP_CONNECTION_LOST_TERTIARY -> SetupR.string.stop_connection_lost_tertiary
        CopyKey.STOP_STEP_FAILED_TITLE -> SetupR.string.stop_step_failed_title
        CopyKey.STOP_STEP_FAILED_BODY -> SetupR.string.stop_step_failed_body
        CopyKey.STOP_RESUME_TITLE -> SetupR.string.stop_resume_title
        CopyKey.STOP_RESUME_BODY -> SetupR.string.stop_resume_body
        CopyKey.STOP_RESUME_HOME_BODY -> SetupR.string.stop_resume_home_body
        CopyKey.STOP_RESUME_PRIMARY -> SetupR.string.stop_resume_primary
        CopyKey.STOP_RESUME_SECONDARY -> SetupR.string.stop_resume_secondary
        CopyKey.STOP_RESUME_TERTIARY -> SetupR.string.stop_resume_tertiary
        CopyKey.STOP_RESUME_CHANGE_TITLE -> SetupR.string.stop_resume_change_title
        CopyKey.STOP_RESUME_CHANGE_BODY -> SetupR.string.stop_resume_change_body
        CopyKey.STOP_HOME_PUT_BACK_TITLE -> SetupR.string.stop_resume_home_title
        CopyKey.STOP_HOME_PUT_BACK_PRIMARY -> SetupR.string.stop_resume_home_primary
        CopyKey.STOP_HOME_PUT_BACK_SECONDARY -> SetupR.string.stop_resume_home_secondary
        CopyKey.STOP_RECONNECT_HOME_BODY -> SetupR.string.stop_reconnect_home_body
        CopyKey.STOP_INSTALL_FAILED_TITLE -> SetupR.string.stop_install_failed_title
        CopyKey.STOP_INSTALL_FAILED_BODY -> SetupR.string.stop_install_failed_generic_body
        CopyKey.STOP_INSTALL_FAILED_PRIMARY -> SetupR.string.stop_install_failed_primary
        CopyKey.STOP_UNDONE_TITLE -> SetupR.string.stop_undone_title
        CopyKey.STOP_UNDONE_BODY -> SetupR.string.stop_undone_body
        CopyKey.STOP_UNDONE_PRIMARY -> SetupR.string.stop_undone_primary
        CopyKey.STOP_PROBE_FAILED_TITLE -> SetupR.string.stop_probe_failed_title
        CopyKey.STOP_PROBE_FAILED_BODY -> SetupR.string.stop_probe_failed_body
        CopyKey.STOP_PROBE_FAILED_PRIMARY -> SetupR.string.stop_probe_failed_primary
        CopyKey.STOP_PROBE_FAILED_SECONDARY -> SetupR.string.stop_probe_failed_secondary
        CopyKey.STOP_PLAN_REFUSED_TITLE -> SetupR.string.stop_plan_refused_title
        CopyKey.STOP_PLAN_REFUSED_BODY -> SetupR.string.stop_plan_refused_body
        CopyKey.STOP_NOT_BACK_TITLE -> SetupR.string.stop_not_back_title
        CopyKey.STOP_NOT_BACK_BODY -> SetupR.string.stop_not_back_body
        CopyKey.STOP_NOT_BACK_PRIMARY -> SetupR.string.stop_not_back_primary
        CopyKey.STOP_NOT_BACK_SECONDARY -> SetupR.string.stop_not_back_secondary
        }
    }
}
