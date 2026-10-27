package tv.cleanyour.core.backup

import kotlinx.coroutines.runBlocking
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoreBundleTest {
    private val clock = FakeClock(Samples.NOW_MS + 3_600_000)
    private val baseline = Scenario.baseline()
    private val changes = Scenario.changes()
    private val tv = Scenario.tv()
    private val overlay = """{"dock":["com.netflix.ninja"]}""".encodeToByteArray()
    private val wallpaper = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    private fun zip(tv: Tv = this.tv, base: Snapshot = baseline, ch: List<ChangeRecord> = changes, ov: ByteArray? = overlay, wp: ByteArray? = wallpaper): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { ZipRestoreBundleWriter(ShellRenderer(), clock, "0.1.0").write(tv, base, ch, ov, wp, out) }
        return out.toByteArray()
    }

    private fun entries(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }

    private fun rezip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun exportRoundTrip() {
        val bytes = zip()
        val read = RestoreBundleReader.read(ByteArrayInputStream(bytes))
        assertEquals(tv.id, read.tvId)
        assertEquals("MiTV-AXSO0", read.model)
        assertEquals("0.1.0", read.appVersion)
        assertEquals(clock.nowMs(), read.createdAtMs)
        assertContentEquals(ChangeJson.encode(changes.sortedBy { it.atMs }), ChangeJson.encode(read.changes))
        assertContentEquals(SnapshotCodec.canonical(RestoreBundle.redact(baseline)), SnapshotCodec.canonical(read.baseline))
        assertEquals(baseline.packages, read.baseline.packages)
        assertEquals("", read.baseline.facts.serialLast4)
        assertContentEquals(overlay, read.entries[RestoreBundle.LAUNCHER_CONFIG])
        assertContentEquals(wallpaper, read.entries[RestoreBundle.LAUNCHER_WALLPAPER])
        assertEquals(RestoreBundle.ENTRIES, entries(bytes).keys.toList(), "entry order")
        assertContentEquals(bytes, zip(), "same inputs, same bytes")
    }

    @Test fun theUndoFromTheReadBackZipEqualsTheUndoFromThePhone() {
        // what an import (0.2) would plan from equals what the phone has now: TvState replays both to the baseline
        val read = RestoreBundleReader.read(ByteArrayInputStream(zip()))
        val r = ShellRenderer()
        assertEquals(RestoreCommands(r).steps(changes).map { it.lines }, RestoreCommands(r).steps(read.changes).map { it.lines })
        val after = TvState.of(baseline).apply(read.changes)
        val undo = RestoreCommands(r).steps(read.changes).filter { it.kind != RestoreStepKind.NOT_FROM_LAPTOP }
            .mapIndexed { i, s -> ChangeRecord(s.change.planId, i, s.inverse!!, null, i.toLong()) }
        assertTrue(after != TvState.of(read.baseline))
        assertEquals(TvState.of(read.baseline), after.apply(undo), "apply then undo everything equals the baseline")
    }

    @Test fun launcherFilesAreOptional() {
        val read = RestoreBundleReader.read(ByteArrayInputStream(zip(ov = null, wp = null)))
        assertEquals(RestoreBundle.REQUIRED.toSet(), read.entries.keys)
    }

    @Test fun theManifestListsEveryEntryWithItsHash() {
        val e = entries(zip())
        val manifest = e.getValue(RestoreBundle.MANIFEST).decodeToString()
        for ((name, bytes) in e) {
            if (name == RestoreBundle.MANIFEST) continue
            assertTrue(manifest.contains("\"name\":\"$name\",\"size\":${bytes.size},\"sha256\":\"${RestoreBundle.sha256(bytes).hex}\""), name)
        }
        assertTrue(manifest.contains("\"schema\":1") && manifest.contains("\"tvId\":\"${tv.id.value}\""))
    }

    @Test fun aTamperedZipIsRefused() {
        val good = entries(zip()).toList()
        fun refused(z: ByteArray) = assertFailsWith<RestoreBundleException> { RestoreBundleReader.read(ByteArrayInputStream(z)) }

        refused(rezip(good.map { (n, b) -> if (n == RestoreBundle.RESTORE_SH) n to (b + "rm -rf /\n".encodeToByteArray()) else n to b }))
        refused(rezip(good.filter { it.first != RestoreBundle.CHANGES }))
        refused(rezip(good + ("../../evil.sh" to byteArrayOf(1))))
        refused(rezip(good + ("extra.txt" to byteArrayOf(1))))
        refused(rezip(good.filter { it.first != RestoreBundle.LAUNCHER_WALLPAPER }))   // listed in the manifest, missing from the zip
        refused(rezip(good.map { (n, b) -> if (n == RestoreBundle.MANIFEST) n to b.decodeToString().replace("\"schema\":1", "\"schema\":9").encodeToByteArray() else n to b }))
        refused(byteArrayOf(1, 2, 3))
    }

    @Test fun aManifestThatListsAnEntryTwiceIsRefused() {
        val good = entries(zip()).toList()
        val doubled = good.map { (n, b) ->
            if (n != RestoreBundle.MANIFEST) return@map n to b
            val m = b.decodeToString()
            val first = Regex("\\{\"name\":\"${RestoreBundle.RESTORE_SH}\"[^}]*\\}").find(m)!!.value
            n to m.replace(first, "$first,$first").encodeToByteArray()
        }
        val e = assertFailsWith<RestoreBundleException> { RestoreBundleReader.read(ByteArrayInputStream(rezip(doubled))) }
        assertTrue(e.message!!.contains("twice"), e.message)
    }

    @Test fun theBytesDoNotDependOnTheDefaultTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = zip()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertContentEquals(utc, zip())
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertContentEquals(utc, zip())
            // Each entry carries the writer zone's (UTC) local time, to the zip format's two seconds.
            val expected = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.nowMs()), java.time.ZoneOffset.UTC)
                .withNano(0).let { it.withSecond(it.second - it.second % 2) }
            ZipInputStream(ByteArrayInputStream(utc)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    assertEquals(expected, e.timeLocal, e.name)
                }
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun anOversizedEntryIsRefused() {
        val good = entries(zip()).toList()
        val bomb = good.map { (n, b) -> if (n == RestoreBundle.LAUNCHER_WALLPAPER) n to ByteArray((RestoreBundle.MAX_ENTRY_BYTES + 1).toInt()) else n to b }
        val e = assertFailsWith<RestoreBundleException> { RestoreBundleReader.read(ByteArrayInputStream(rezip(bomb))) }
        assertTrue(e.message!!.contains("too large"), e.message)
    }

    @Test fun noSerialOrNetworkDataInAnyFile() {
        val serial = Fixtures.miTv().serial
        val ip = (tv.endpoints.first()).host
        val subnet = tv.lastSubnet!!
        val forbidden = listOf(serial, ip, subnet, subnet.substringBefore('/'), "192.0.2.", tv.trust.adbKeyId, "\"serialLast4\":\"${tv.facts.serialLast4}\"", "android_id")
        val files = entries(zip()).toMutableMap()
        val tvFiles = DefaultTvRestoreFiles(clock)
        files["tv:" + DefaultTvRestoreFiles.RESTORE_POINT_FILE] = tvFiles.restorePointJson(tv, changes)
        files["tv:" + DefaultTvRestoreFiles.RESTORE_TXT_FILE] = tvFiles.restoreTxt(tv, changes, null)
        for ((name, bytes) in files) {
            val text = bytes.decodeToString().lowercase()
            for (f in forbidden) assertFalse(text.contains(f.lowercase()), "$name contains $f")
        }
        assertTrue(files.getValue(RestoreBundle.SNAPSHOT).decodeToString().contains("\"serialLast4\":\"\""))
    }

    @Test fun fileNameFollowsArchitecture() {
        assertEquals("cleanyourtv-restore-MiTV-AXSO0-20251009.zip", RestoreBundle.fileName(tv, Samples.NOW_MS))
        val odd = tv.copy(facts = tv.facts.copy(model = "../My TV/'x"))
        assertEquals("cleanyourtv-restore-My-TV-x-20251009.zip", RestoreBundle.fileName(odd, Samples.NOW_MS))
    }

    @Test fun aBaselineForAnotherTvIsRefused() {
        val other = Samples.snapshot(tvId = TvId("ffffffffffffffff"))
        assertFailsWith<IllegalArgumentException> { zip(base = other) }
    }
}
