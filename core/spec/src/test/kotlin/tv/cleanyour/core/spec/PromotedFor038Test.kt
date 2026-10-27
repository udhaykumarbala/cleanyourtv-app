package tv.cleanyour.core.spec

import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Platform
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Five upstream lists that match TVs people reported. Each reported TV, as its TV report reads it,
// resolves to its own list: #446 MotorolaTV (Hisense, hayward), #347 Hisense Fire TV AFTCIBFBD1
// (coyote), #543 MiTV-MSSP0 (amelie), #481 TCL Percee TV (tcl_eu, Android 8.0) and #340 MiTV-ASTP0
// (mulan). Four came with the b06ab21 promotions; motorola-mt4310 is promoted with this test. None
// feeds the brand sets until it is reviewed (SureSetGenerator.REVIEW_PENDING).
class PromotedFor038Test {
    private val source = BundledProfileSource()
    private fun facts(model: String, device: String, brand: String, manufacturer: String, sdk: Int, fingerprint: String = "") =
        Fixtures.miTv().facts().copy(model = model, device = device, brand = brand, manufacturer = manufacturer, sdk = sdk, fingerprint = fingerprint)
    private fun winner(f: tv.cleanyour.core.model.DeviceFacts) = (source.resolveNow(f) as? ProfileResolution.Exact)?.profile?.id

    // The reports' values. #481's fingerprint has the build tag of the report replaced.
    private val reported = listOf(
        facts("MotorolaTV", "hayward", "MOTOROLA", "Hisense", 30) to "motorola-mt4310",
        facts("AFTCIBFBD1", "coyote", "Amazon", "Amazon", 30) to "hisense-fire-tv-aftcibfbd1",
        facts("MiTV-MSSP0", "amelie", "Xiaomi", "Xiaomi", 28) to "xiaomi-mitv-mssp0",
        facts("Percee TV", "tcl_eu", "TCL", "TCL", 26, "TCL/tcl_eu/tcl_eu:8.0.0/OTT1.180130.001/build:user/release-keys") to "tcl-50dp660",
        facts("MiTV-ASTP0", "mulan", "Xiaomi", "xiaomi", 28) to "xiaomi-mitv-astp0",
    )

    @Test fun eachReportedTvGetsItsList() {
        for ((f, id) in reported) assertEquals(id, winner(f), f.model)
    }

    @Test fun theirNeighboursDoNot() {
        // Another build of the Percee TV, a Polytron on the same hayward board, Android 10 on amelie.
        assertNull(winner(facts("Percee TV", "tcl_eu", "TCL", "TCL", 26, "TCL/tcl_eu/tcl_eu:8.0.0/OTT1.190101.001/x:user/release-keys")))
        assertTrue(winner(facts("POLYTRON2K", "hayward", "WLTV", "POLYTRON", 30)) != "motorola-mt4310")
        assertTrue(winner(facts("MiTV-MSSP0", "amelie", "Xiaomi", "Xiaomi", 29)) != "xiaomi-mitv-mssp0")
    }

    @Test fun theirHomeScreenDecisions() {
        val byId = BundledProfileSource.bundled.profiles.associateBy { it.id }
        assertEquals(Platform.FIRE_OS, byId.getValue("hisense-fire-tv-aftcibfbd1").platform)
        // PatchWall is the MiTV-ASTP0's own home: no known launcher, so the clean-up only.
        assertEquals(emptyList(), byId.getValue("xiaomi-mitv-astp0").stockLaunchers)
        assertTrue(byId.getValue("xiaomi-mitv-astp0").neverDisable.any { it.value == "com.mitv.tvhome" })
        for (id in reported.map { it.second }) {
            val p = byId.getValue(id)
            assertTrue(p.tested != null && !p.isCandidate, id)
            assertTrue(p.groups.any { it.default == GroupDefault.DISABLE }, id)
            assertTrue(id in SureSetGenerator.REVIEW_PENDING, id)
        }
        // The setup wizard is never in the MotorolaTV list's rows: it saved the Wi-Fi on that set.
        assertTrue(byId.getValue("motorola-mt4310").neverDisable.any { it.value == "com.google.android.tungsten.setupwraith" })
        // The header's Do NOT disable and Never open names, the factory menu among them.
        for (pkg in listOf("mediatek.factorymenu.ui", "com.at.factory.factorymanager", "com.cvte.hotkey.receiver", "com.cvte.tv.infoprovider", "com.cvte.autobacklight", "com.airoha.android.btd"))
            assertTrue(byId.getValue("motorola-mt4310").neverDisable.any { it.value == pkg }, pkg)
        assertIs<ProfileResolution.Exact>(source.resolveNow(reported.first().first))
    }
}
