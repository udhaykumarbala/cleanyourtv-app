package tv.cleanyour.core.spec

import org.junit.Test
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Plan WP5 step 10: every app_keys entry of the upstream per-TV config.json files at the pin (28 of
// them have app_keys) reads with the 0.4.5 grammar: no false warning, every entry a key and a target
// the launcher acts on.
class AppKeysUpstreamTest {
    @Test fun everyUpstreamAppKeysEntryReads() {
        val files = File(Fixtures.upstreamDir, "examples").listFiles()!!.map { File(it, "config.json") }.filter { it.isFile }
            .filter { "\"app_keys\"" in it.readText() }.sortedBy { it.parentFile.name }
        assertEquals(28, files.size, files.joinToString { it.parentFile.name })
        var entries = 0
        for (f in files) {
            val label = f.parentFile.name
            val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(f.readBytes()), label)
            assertEquals(emptyList(), ok.warnings.filter { it.startsWith("app_keys") }, label)
            val keys = assertNotNull(ok.doc.appKeys, label)
            assertTrue(keys.isNotEmpty(), label)
            for (e in keys) {
                val k = assertNotNull(ConfigCodecImpl.appKeyEntry(e), "$label: $e")
                assertTrue(ConfigCodecImpl.appKeyTarget(k.target), "$label: $e")
                entries++
            }
        }
        println("app_keys: $entries entries in ${files.size} upstream files, no warning")
    }
}
