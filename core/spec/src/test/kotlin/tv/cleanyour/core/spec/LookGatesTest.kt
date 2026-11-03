package tv.cleanyour.core.spec

import org.junit.Test
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals

class LookGatesTest {
    private val all = listOf(StyleId.CLASSIC, StyleId.GLASS, StyleId.SPOTLIGHT, StyleId.ALL_APPS, StyleId.CALM)

    // Launcher 2.7 is versionCode 8: below it only Classic and no 2.7 rows; 8, later or not known
    // (the bundled launcher, 3.0 at versionCode 9) gets all five styles.
    @Test fun byVersionCode() {
        for ((vc, styles, reads) in listOf(
            Triple(5L, listOf(StyleId.CLASSIC), false),
            Triple(7L, listOf(StyleId.CLASSIC), false),
            Triple(8L, all, true),
            Triple(9L, all, true),
            Triple(null, all, true),
        )) {
            assertEquals(styles, LookGates.styles(vc), "versionCode $vc")
            assertEquals(reads, LookGates.reads27(vc), "versionCode $vc")
        }
        assertEquals(8L, LookGates.FIRST_27_VERSION_CODE)
        // The submodule's launcher (3.3, versionCode 12 at d7b0d4a5) still reads the 2.7 keys. The bundled
        // APK's own versionCode is pinned in core/trust (Pins.kt, LauncherPinTest).
        val gradle = File(Fixtures.upstreamDir, "launcher/app/build.gradle").readText()
        val vc = Regex("versionCode\\s+(\\d+)\\b").find(gradle)!!.groupValues[1].toLong()
        assertEquals(12L, vc)
        assertEquals(true, LookGates.reads27(vc))
        assertEquals(true, LookGates.reads30(vc))
    }

    // Launcher 3.0 is versionCode 9: the LookExtras keys are read from 9 on, or when the version is
    // not known (the bundled launcher); 2.7 (8) and older never get them.
    @Test fun reads30ByVersionCode() {
        for ((vc, reads) in listOf(5L to false, 7L to false, 8L to false, 9L to true, 10L to true, null to true)) {
            assertEquals(reads, LookGates.reads30(vc), "versionCode $vc")
        }
        assertEquals(9L, LookGates.FIRST_30_VERSION_CODE)
    }

    // Movement: Quick at 1.5 GB or less, else Lively; Lift and zoom shown from 2 GB, when the
    // memory is not known, or when it is already on.
    @Test fun byMemory() {
        for ((ram, movement, lift) in listOf(
            Triple(1024, Movement.QUICK, false),
            Triple(1536, Movement.QUICK, false),
            Triple(1537, Movement.LIVELY, false),
            Triple(2047, Movement.LIVELY, false),
            Triple(2048, Movement.LIVELY, true),
            Triple(null, Movement.LIVELY, true),
        )) {
            assertEquals(movement, LookGates.defaultMovement(ram), "$ram MB")
            assertEquals(lift, LookGates.liftShown(ram, liftOn = false), "$ram MB")
            assertEquals(true, LookGates.liftShown(ram, liftOn = true), "$ram MB, already on")
        }
        assertEquals(1536 to 2048, LookGates.LOW_RAM_MB to LookGates.LIFT_MIN_RAM_MB)
    }

    @Test fun halfGb() {
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0), listOf(1024, 1536, 2048, 3072).map(LookGates::halfGb))
        // What a TV reports is a little under its size: still the nearest half GB.
        assertEquals(listOf(1.0, 1.5, 2.0, 0.5), listOf(980, 1480, 1950, 400).map(LookGates::halfGb))
    }
}
