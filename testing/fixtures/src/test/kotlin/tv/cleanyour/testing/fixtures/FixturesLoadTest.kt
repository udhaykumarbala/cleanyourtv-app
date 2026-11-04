package tv.cleanyour.testing.fixtures

import kotlinx.coroutines.runBlocking
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// W1 definition of done: the fixtures load on the JVM.
class FixturesLoadTest {

    @Test fun miTvCaptureLoads() {
        val tv = Fixtures.miTv()
        val f = tv.facts()
        assertEquals("MiTV-AXSO0", f.model)
        assertEquals("dangal", f.device)
        assertEquals("Xiaomi", f.brand)
        assertEquals(28, f.sdk)
        assertEquals("9", f.release)
        assertEquals("0001", f.serialLast4)
        assertTrue(f.adbSecure)
        assertTrue(f.hasGms)
        assertEquals(listOf("armeabi-v7a", "armeabi"), f.abis)
        assertEquals(107, tv.packages.size)
        assertEquals(18, tv.disabledAfter.size)
        assertEquals("com.google.android.tvlauncher/.MainActivity", tv.homeActivityText.lines()[1])
        assertEquals("1", tv.settings("global")["adb_enabled"])
        assertEquals(16, tv.tvId().value.length)
    }

    @Test fun miTvSnapshotRoundTripsThroughTheCodec() {
        val s = Samples.miTvSnapshot()
        assertEquals(107, s.packages.size, "every captured package, the framework's \"android\" included (RFC-W1-2)")
        assertEquals(s, SnapshotCodec.decode(SnapshotCodec.canonical(s)))
    }

    @Test fun upstreamSubmoduleFixturesLoad() {
        val examples = Fixtures.upstreamExamples()
        assertEquals(324, examples.size, "every upstream examples/*/debloat.txt at the pinned commit (0cb195a)")
        assertTrue(examples.all { it.debloatTxt.isNotBlank() })
        assertTrue(examples.any { it.folder == "xiaomi-mitv-axso0" })
        val themes = Fixtures.upstreamThemes().associateBy { it.name }
        assertEquals(setOf("aurora", "default", "dussehra", "glass", "nature-calm", "retro", "ruska", "scenes", "six-columns", "sweden"), themes.keys)
        assertNotNull(themes.getValue("aurora").wallpaperJpg)
        assertNotNull(themes.getValue("dussehra").wallpaperJpg)
        assertTrue(Fixtures.upstreamConfigJava().readText().contains("class Config"))
    }

    @Test fun moduleCardDigestsLoad() {
        val d = Fixtures.moduleCardDigests()
        assertEquals(22, d.size)
        assertEquals("ec8f5bf71162d27a7cd869eaeb648728b2be40daa446726ea23dfbc98bbf9c96", d.getValue("cardtestb.apk").signer.hex)
        assertEquals(2, d.values.map { it.signer }.toSet().size, "keys A and B")
    }

    @OptIn(RawShell::class)
    @Test fun fakeSessionAnswersFromTheMiTvRecording() = runBlocking<Unit> {
        val s = FakeTvSession(recording = Recording.miTv())
        assertEquals(Fixtures.miTv().getpropText, s.exec(ShellLine("'getprop'")).stdout)
        assertEquals(0, s.exec(ShellLine("pm   list packages  -d")).exit)
        assertEquals(127, s.exec(ShellLine("reboot bootloader")).exit)
        assertEquals(listOf("'getprop'", "pm   list packages  -d", "reboot bootloader"), s.execLines)
    }
}
