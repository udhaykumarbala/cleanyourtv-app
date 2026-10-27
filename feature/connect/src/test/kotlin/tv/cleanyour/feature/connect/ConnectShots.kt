package tv.cleanyour.feature.connect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.Families
import tv.cleanyour.core.flows.IdentityCheck
import tv.cleanyour.core.flows.NoSetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase

// Every state of :feature:connect's screens (PRODUCT-SPEC 4: S1, S1b, S1c, S2a to S2d, S2x and the
// inline stops of section 4: TV not found, wrong network, Wi-Fi off, identity changed, not
// supported). The copy comes from this module's strings, as the app formats it.

private fun fakeShot(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF05060A.toInt())
    val paint = Paint()
    val tiles = intArrayOf(0xFF2A2F45.toInt(), 0xFF1F5FD0.toInt(), 0xFF4A4A50.toInt(), 0xFF138A7A.toInt())
    repeat(4) { i -> paint.color = tiles[i]; canvas.drawRoundRect(RectF(70f + i * 130f, 230f, 180f + i * 130f, 320f), 16f, 16f, paint) }
    paint.color = 0xFF3A3A40.toInt()
    canvas.drawRoundRect(RectF(60f, 40f, 160f, 70f), 8f, 8f, paint)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private const val FINGERPRINT = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:7F:2A"

private val miTv = FoundTv("192.168.1.50", "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), true, null, null, 0L)
private val chromecast = FoundTv("192.168.1.61", "Bedroom Chromecast", "Chromecast", setOf(DiscoverySource.GOOGLE_CAST), false, null, null, 0L)
private val miTvTwin = miTv.copy(host = "192.168.1.77")
private val fireTv = FoundTv("192.168.1.70", "Fire TV Stick", "AFTSSS", setOf(DiscoverySource.GOOGLE_CAST), true, null, null, 0L)
private val tclTv = FoundTv("192.168.1.71", "TCL C635", "C635", setOf(DiscoverySource.GOOGLE_CAST), true, null, null, 0L)
private val pairingTv = FoundTv("192.168.1.61", "Bedroom Chromecast", "Chromecast", setOf(DiscoverySource.ADB_TLS_PAIRING), false,
    ServiceRef("adb-tls-pairing", "192.168.1.61", 41235), null, 0L)
private val unknownTv = FoundTv("192.168.1.90", null, null, setOf(DiscoverySource.MANUAL), true, null, null, 0L)

private val waiting = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING)
private val connected = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED)

private fun welcome(found: List<FoundTv>, looking: Boolean) = SetupState.Welcome(found, onWifi = true, looking = looking)

private fun door(family: String, phase: DoorPhase, step: Int, banner: String? = null, waitedMs: Long = 0, fingerprint: String? = null, tv: FoundTv = miTv) =
    SetupState.OpenDoor(tv, family, step, phase, fingerprint, banner, waitedMs)

// A stop card with the copy the app's ResourceFlowCopy would put on it.
@Composable
private fun card(case: StopCase, title: Int, body: Int, primary: Int, secondary: Int? = null, vararg args: Pair<String, Any>) =
    StopCard(case, msg(title, *args), msg(body, *args), stringResource(primary), secondary?.let { stringResource(it) }, planId = null)

@Composable
private fun notFound() = card(StopCase.TV_NOT_FOUND, R.string.stop_tv_not_found_title, R.string.stop_tv_not_found_body,
    R.string.stop_tv_not_found_primary, R.string.stop_tv_not_found_secondary)

@Composable
private fun wrongNetwork() = card(StopCase.WRONG_NETWORK, R.string.stop_wrong_network_title, R.string.stop_wrong_network_body,
    R.string.stop_wrong_network_primary, R.string.stop_wrong_network_secondary)

@Composable
private fun wifiOff() = card(StopCase.WRONG_NETWORK, R.string.stop_wrong_network_off_title, R.string.stop_wrong_network_off_body,
    R.string.stop_wrong_network_primary, R.string.stop_wrong_network_secondary)

@Composable
private fun identityChanged() = card(StopCase.IDENTITY_CHANGED, R.string.stop_identity_changed_title, R.string.stop_identity_changed_body,
    R.string.stop_identity_changed_primary, R.string.stop_identity_changed_secondary, "name" to "Mi TV 4A")

@Composable
private fun notSupported() = card(StopCase.TV_NOT_SUPPORTED, R.string.stop_tv_not_supported_title, R.string.stop_tv_not_supported_body,
    R.string.stop_tv_not_supported_primary)

@Composable
private fun Connect(state: SetupState, extras: SetupExtras = SetupExtras()) = ConnectFlow(state, extras, NoSetupActions, onReturning = {})

object ConnectShots {
    val all: List<Shot> = listOf(
        // S1 Welcome: the live line from looking to found, and the inline stops
        Shot("welcome_looking") { Connect(welcome(emptyList(), looking = true)) },
        Shot("welcome_none_yet") { Connect(welcome(emptyList(), looking = false)) },
        Shot("welcome_found_one") { Connect(welcome(listOf(miTv), looking = false)) },
        Shot("welcome_found_two") { Connect(welcome(listOf(miTv, chromecast), looking = false)) },
        Shot("welcome_tv_not_found") { Connect(welcome(emptyList(), looking = false), SetupExtras(card = notFound())) },
        Shot("welcome_wrong_network") { Connect(welcome(emptyList(), looking = false), SetupExtras(card = wrongNetwork())) },
        Shot("welcome_wifi_off") { Connect(welcome(emptyList(), looking = false), SetupExtras(card = wifiOff())) },
        Shot("welcome_identity_changed") { Connect(welcome(listOf(miTv), looking = false), SetupExtras(card = identityChanged())) },
        Shot("welcome_tv_not_supported") { Connect(welcome(listOf(miTv), looking = false), SetupExtras(card = notSupported())) },

        // S1b Which TV?
        Shot("picker_two_tvs") { Connect(SetupState.Picker(listOf(miTv, chromecast))) },
        Shot("picker_same_names") { Connect(SetupState.Picker(listOf(miTv, miTvTwin))) },

        // S1c Enter the TV's address (sheet over the picker)
        Shot("address_sheet") {
            var text by remember { mutableStateOf("192.168.1.50") }
            SheetFrame({ Connect(SetupState.Picker(listOf(miTv, chromecast))) }) { AddressContent(text, { text = it }, error = false, onConnect = {}) }
        },
        Shot("address_sheet_error") {
            SheetFrame({ Connect(SetupState.Picker(listOf(miTv, chromecast))) }) { AddressContent("8.8.8.8", {}, error = true, onConnect = {}) }
        },

        // S2a Let the phone in: the step cards per platform family
        Shot("door_steps_atv") { Connect(door(Families.ATV, DoorPhase.STEPS, 0), waiting) },
        Shot("door_steps_atv_step2") { Connect(door(Families.ATV, DoorPhase.STEPS, 1), waiting) },
        Shot("door_steps_gtv") { Connect(door(Families.GTV, DoorPhase.STEPS, 0, tv = chromecast), waiting.copy(tvName = "Bedroom Chromecast")) },
        Shot("door_steps_fire") { Connect(door(Families.FIRE, DoorPhase.STEPS, 0, banner = stringResource(R.string.banner_fire), tv = fireTv), waiting.copy(tvName = "Fire TV Stick")) },
        // The TCL chip asks about the menus; English or local menus get the Android TV steps (and the
        // Google TV line on step 2), Chinese menus the 本机信息 steps and the home screen banner.
        Shot("door_steps_tcl_ask") { Connect(door(Families.TCL_ASK, DoorPhase.STEPS, 0, tv = tclTv), waiting.copy(tvName = "TCL C635")) },
        Shot("door_steps_tcl_global") { Connect(door(Families.TCL_GLOBAL, DoorPhase.STEPS, 0, tv = tclTv), waiting.copy(tvName = "TCL C635")) },
        Shot("door_steps_tcl_global_step2") { Connect(door(Families.TCL_GLOBAL, DoorPhase.STEPS, 1, tv = tclTv), waiting.copy(tvName = "TCL C635")) },
        Shot("door_steps_tcl") { Connect(door(Families.TCL, DoorPhase.STEPS, 0, banner = stringResource(R.string.banner_tcl), tv = tclTv), waiting.copy(tvName = "TCL C635")) },
        Shot("door_steps_mibox_usb") { Connect(door(Families.ATV, DoorPhase.STEPS, 0, banner = stringResource(R.string.banner_mibox_usb)), waiting.copy(tvName = "Mi Box S")) },
        Shot("door_steps_unknown_brand_chips") { Connect(door(Families.UNKNOWN, DoorPhase.STEPS, 0, tv = unknownTv), SetupExtras()) },
        // beta2 QA: a typed address with no name yet; the chip shows the address, the line under the steps no model.
        Shot("door_steps_typed_address") { Connect(door(Families.ATV, DoorPhase.STEPS, 0, tv = unknownTv), SetupExtras(chip = ChipState.WAITING)) },
        Shot("door_steps_still_waiting") { Connect(door(Families.ATV, DoorPhase.STEPS, 2, waitedMs = 181_000), waiting) },

        // S2b Connecting
        Shot("door_connecting") { Connect(door(Families.ATV, DoorPhase.CONNECTING, 2), waiting) },
        // 0.3.6 (issues #98, #270, #325, #468): the port answered, the connection failed; the hint and "Copy error".
        Shot("door_connect_failed") { Connect(door(Families.ATV, DoorPhase.STEPS, 2), waiting.copy(connectError = tv.cleanyour.core.flows.ConnectError("unreachable", "Clean Your TV connection error"))) },
        // 0.4.6: the card itself, with "Report a problem" under "Copy error" (it opens the prefilled GitHub form).
        // Tall enough that the button shows at 200% font too.
        Shot("door_connect_failed_report", height = 1000.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { ConnectErrorCard(tv.cleanyour.core.flows.ConnectError("protocol", "Clean Your TV connection error"), {}) }
        },

        // S2c Look at the TV (Allow), and the "I don't see it on the TV" sheet before and after 3 retries
        Shot("door_allow") { Connect(door(Families.ATV, DoorPhase.ALLOW, 2, waitedMs = 5_000, fingerprint = FINGERPRINT), waiting) },
        Shot("door_allow_still_waiting") { Connect(door(Families.ATV, DoorPhase.ALLOW, 2, waitedMs = 46_000, fingerprint = FINGERPRINT), waiting) },
        Shot("door_allow_not_seen_sheet") {
            SheetFrame({ Connect(door(Families.ATV, DoorPhase.ALLOW, 2, fingerprint = FINGERPRINT), waiting) }) { NotSeenContent(0) {} }
        },
        Shot("door_allow_not_seen_restart") {
            SheetFrame({ Connect(door(Families.ATV, DoorPhase.ALLOW, 2, fingerprint = FINGERPRINT), waiting) }) { NotSeenContent(3) {} }
        },

        // S2d first-time identity check (D38) and Connected
        Shot("door_identity") {
            val png = remember { fakeShot() }
            Connect(door(Families.ATV, DoorPhase.IDENTITY, 2), connected.copy(identity = IdentityCheck("Mi TV 4A", "7K2Q", png)))
        },
        Shot("door_identity_no_picture") { Connect(door(Families.ATV, DoorPhase.IDENTITY, 2), connected.copy(identity = IdentityCheck("Mi TV 4A", "7K2Q", null))) },
        Shot("door_connected") { Connect(door(Families.ATV, DoorPhase.CONNECTED, 2), connected) },

        // S2x Pairing needed and its computer steps
        Shot("pairing_needed") { Connect(SetupState.PairingNeeded(pairingTv)) },
        Shot("pairing_steps_sheet") { SheetFrame({ Connect(SetupState.PairingNeeded(pairingTv)) }) { PairingStepsContent() } },
    )
}
