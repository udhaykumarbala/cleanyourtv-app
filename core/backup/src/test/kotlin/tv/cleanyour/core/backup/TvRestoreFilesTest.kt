package tv.cleanyour.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TvRestoreFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock(Samples.NOW_MS + 3_600_000)
    private val files = DefaultTvRestoreFiles(clock)
    private val tv = Scenario.tv()
    private val changes = Scenario.changes()

    // A profile in the shape W4's converter produces: groups from the comment headers of the
    // upstream example, notes from the trailing comments.
    private fun upstreamProfile(folder: String): Profile {
        val text = Fixtures.upstreamExamples().single { it.folder == folder }.debloatTxt
        val groups = ArrayList<PackageGroup>()
        var title = "Ungrouped"
        var entries = ArrayList<PackageEntry>()
        fun flush() { if (entries.isNotEmpty()) groups += PackageGroup("g${groups.size}", title, GroupDefault.DISABLE, entries); entries = ArrayList() }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#") -> { flush(); title = line.trimStart('#').trim() }
                else -> {
                    val name = line.substringBefore('#').trim()
                    if (Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(name)) {
                        entries += PackageEntry(PackageName(name), line.substringAfter('#', "").trim().ifEmpty { null })
                    }
                }
            }
        }
        flush()
        return Profile(folder, ProfileMatch(listOf("MiTV-AXSO0"), emptyList(), "Xiaomi", null, null, null), Platform.ANDROID_TV,
            listOf(Samples.stockLauncher), null, null, emptySet(), groups, emptySet(), emptyList(), null, Samples.zeroHash)
    }

    private fun listed(txt: String): List<String> =
        txt.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    @Test fun restoreTxtListsWhatIsOffNow() {
        val txt = files.restoreTxt(tv, changes, null).decodeToString()
        assertEquals(Scenario.expectedOff().map { it.value }, listed(txt))
        assertTrue(txt.lines().first().startsWith("# Clean Your TV restore list for Mi TV 4A (MiTV-AXSO0), 2025-10-09."), txt)
        assertTrue(txt.contains("tools/restore.sh restore.txt com.google.android.tvlauncher"), txt)
        assertTrue(txt.lines().all { Regex("^[ -~]*$").matches(it) })
    }

    @Test fun restoreTxtUsesTheProfileGroupsAndNotes() {
        val profile = upstreamProfile("xiaomi-mitv-axso0")
        val txt = files.restoreTxt(tv, changes, profile).decodeToString()
        assertEquals(Scenario.expectedOff().map { it.value }.sorted(), listed(txt).sorted())
        val inProfile = profile.groups.flatMap { g -> g.packages.map { it.name } }.toSet()
        assertTrue(Scenario.expectedOff().any { it in inProfile }, "the scenario should overlap the profile")
        profile.groups.filter { g -> g.packages.any { it.name in Scenario.expectedOff() } }.forEach { g -> assertTrue(txt.contains("# ${g.title}"), g.title) }
    }

    // D44: upstream tools/restore.sh reads our restore.txt unchanged. Since v2.4.0 restore.sh hands
    // the list to tools/recovery.py, which validates it and, with no record of the TV's state from
    // upstream's own disable.sh (always the case for a TV cleaned by this app), prints a batch that
    // enables the whole list; tools/tv.py runs that batch. The test runs the pinned restore.sh and
    // recovery.py for real (bash and python3, HOME a temp dir so no record is found) and captures
    // only tv.py. Without python3 on the PATH (the forge build image has none) the list is checked
    // against recovery.py's own package grammar instead; CI has python3 and must run it for real.
    @Test fun upstreamRestoreShReadsIt() {
        val bash = Shells.which("bash") ?: fail("bash is needed to run upstream tools/restore.sh")
        val list = File(tmp.root, "restore.txt").also { it.writeBytes(files.restoreTxt(tv, changes, upstreamProfile("xiaomi-mitv-axso0"))) }
        val expected = (Scenario.expectedOff().map { it.value } + "com.google.android.tvlauncher").sorted()
        val recovery = File(Fixtures.upstreamDir, "tools/recovery.py")
        val python = Shells.which("python3")
        if (python == null) {
            if (System.getenv("CI") != null) fail("python3 is needed to run upstream tools/recovery.py")
            assertEquals(expected, recoveryReadList(recovery.readText(), list.readText()).plus("com.google.android.tvlauncher").sorted())
            return
        }
        val repo = tmp.newFolder("upstream")
        File(repo, "tools").mkdirs()
        File(Fixtures.upstreamDir, "tools/restore.sh").copyTo(File(repo, "tools/restore.sh"))
        recovery.copyTo(File(repo, "tools/recovery.py"))
        val home = tmp.newFolder("home")
        // tools/venv/bin/python: the real interpreter for recovery.py, a capture for tv.py.
        val py = File(repo, "tools/venv/bin/python").also { it.parentFile.mkdirs() }
        val captured = File(repo, "captured.txt")
        py.writeText(
            "#!/bin/sh\n" +
                "for a in \"$@\"; do case \"\$a\" in tools/tv.py) cat > '${captured.absolutePath}'; exit 0;; esac; done\n" +
                "exec '${python.absolutePath}' \"$@\"\n",
        )
        check(py.setExecutable(true))
        val pb = ProcessBuilder(bash.absolutePath, File(repo, "tools/restore.sh").absolutePath, list.absolutePath, "com.google.android.tvlauncher")
            .redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["TV_HOST"] = "192.0.2.10"
        val p = pb.start()
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        assertTrue("no recovery record" in out, out)
        val enabled = captured.readLines().filter { it.startsWith("pm enable ") }.map { it.removePrefix("pm enable ") }
        assertEquals(expected, enabled.sorted())
    }

    // recovery.py's read_list, with its PACKAGE_RE read from the pinned file: a trailing # note and
    // blank lines dropped, the first word of each line a package name, anything else refused.
    private fun recoveryReadList(source: String, list: String): List<String> {
        val pattern = Regex("""PACKAGE_RE = re\.compile\(r"([^"]+)"\)""").find(source)?.groupValues?.get(1) ?: fail("PACKAGE_RE not found in recovery.py")
        val re = Regex(pattern)
        return list.removePrefix("\uFEFF").lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { line ->
            line.split(Regex("\\s+")).first().also { assertTrue(re.matches(it), "recovery.py would refuse $it") }
        }
    }

    @Test fun restorePointJsonSchema1() {
        val bytes = files.restorePointJson(tv, changes)
        val o = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(1, o.getValue("schema").jsonPrimitive.content.toInt())
        assertEquals(DefaultTvRestoreFiles.KIND, o.getValue("kind").jsonPrimitive.content)
        assertEquals(tv.id.value, o.getValue("tvId").jsonPrimitive.content)
        assertEquals(clock.nowMs(), o.getValue("createdAtMs").jsonPrimitive.content.toLong())
        assertEquals(Scenario.expectedOff().map { it.value }, o.getValue("switchedOff").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Scenario.stock.flat, o.getValue("homeBefore").jsonPrimitive.content)
        assertEquals(Scenario.launcher.flat, o.getValue("homeNow").jsonPrimitive.content)
        assertEquals(listOf("com.gathin.tvhome"), o.getValue("installed").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("/sdcard/Android/data/com.gathin.tvhome/files/config.json"), o.getValue("files").jsonArray.map { it.jsonPrimitive.content })
        val settings = o.getValue("settings").jsonArray.map { it.jsonObject }
        assertEquals(listOf("screensaver_components", "screensaver_enabled"), settings.map { it.getValue("name").jsonPrimitive.content })
        val changesBack = ChangeJson.decodeArray(o.getValue("changes").jsonArray)
        assertContentEquals(ChangeJson.encode(changes), ChangeJson.encode(changesBack))
        assertTrue(!bytes.decodeToString().contains(tv.nickname), "no nickname on the TV's shared storage")
    }

    // QA 0.4.5-beta1: after Undo everything with "Remove it from the TV", restore-point.json still
    // listed the launcher's config.json and wallpaper.jpg under `files`, though the uninstall had
    // taken the launcher's folder with it. `files` is what is on the TV now, as the fake TV replays it.
    @Test fun filesGoWithTheAppThatHoldsThem() {
        fun listedFiles(changes: List<ChangeRecord>) =
            Json.parseToJsonElement(files.restorePointJson(tv, changes).decodeToString()).jsonObject.getValue("files").jsonArray.map { it.jsonPrimitive.content }
        val onTv = { changes: List<ChangeRecord> -> TvState(emptyMap(), null, emptyMap(), emptySet()).apply(changes).files.sorted() }

        val removed = Scenario.m1Changes()
        assertTrue(removed.last().op == Op.Uninstall(Scenario.launcher.pkg), "the scenario ends with the launcher removed")
        assertEquals(emptyList(), listedFiles(removed))
        assertEquals(onTv(removed), listedFiles(removed).sorted())
        val o = Json.parseToJsonElement(files.restorePointJson(tv, removed).decodeToString()).jsonObject
        assertTrue(o.getValue("installed").jsonArray.none { it.jsonPrimitive.content == Scenario.launcher.pkg.value })

        // Set up again: a look pushed after the reinstall is on the TV, and listed.
        val plan = PlanId("01SETUPAGAIN00000000000007")
        val t = removed.maxOf { it.atMs } + 1_000
        val apk = BlobRef(sha256Of("launcher apk"), 260_000, "bundled:launcher.apk")
        val config = BlobRef(sha256Of("{\"columns\":5}"), 13, "mem:config")
        val configPath = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        val again = removed + listOf(
            ChangeRecord(plan, 0, Op.InstallStream(apk, apk.sha256, Scenario.launcher.pkg, 1, Samples.zeroHash),
                Prior(present = false), t),
            ChangeRecord(plan, 1, Op.PushAsset(config, configPath, config.sha256),
                Prior(present = false), t + 1_000),
        )
        assertEquals(listOf(configPath.absolute), listedFiles(again))
        assertEquals(onTv(again), listedFiles(again).sorted())

        // A file outside the removed app's folder stays listed.
        val tmpFile = RemotePath(RemoteDir.TMP, listOf("kept.bin"))
        val other = listOf(ChangeRecord(plan, 0, Op.PushAsset(config, tmpFile, config.sha256),
            Prior(present = false), 1)) + removed
        assertEquals(listOf(tmpFile.absolute), listedFiles(other))
    }

    @Test fun nothingSwitchedOff() {
        val txt = files.restoreTxt(tv, emptyList(), null).decodeToString()
        assertTrue(listed(txt).isEmpty() && txt.contains("Nothing is switched off"), txt)
    }
}
