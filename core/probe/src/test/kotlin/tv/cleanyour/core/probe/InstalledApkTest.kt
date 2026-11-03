package tv.cleanyour.core.probe

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.PackageName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-INSTALL-1: the install check's second read, after the installed APK's hash read nothing.
class InstalledApkTest {
    private val pkg = PackageName("com.gathin.tvhome")
    private val prober = AdbProber(ProbeFixtures.clock())
    private val script = ProbeScripts.installedApkState(pkg).text

    // The state lines as Android 11 prints them.
    private fun user(id: Int, installed: Boolean, hidden: Boolean = false, stopped: Boolean = true, enabled: Int = 0) =
        "    User $id: ceDataInode=0 installed=$installed hidden=$hidden suspended=false distractionFlags=0 stopped=$stopped notLaunched=true enabled=$enabled instant=false virtual=false"

    private fun out(base: String, hash: String, current: String, vararg users: String) =
        "$base\n@@cyt@@ hash\n$hash\n@@cyt@@ current\n$current\n@@cyt@@ users\n" + users.joinToString("") { "$it\n" }

    // Issues #2743, #1918, #1510 and the rest: on the TV, versionCode 9, but not for user 0. The
    // second Android user keeps it, so `pm uninstall --user 0` left it there, and Android 11 and 12
    // install over it "for all users" without installing it for user 0 again.
    @Test fun aPackageOnTheTvButNotInstalledForUser0() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        s.recording.on(script, out("0", "1", "0", user(0, installed = false), user(10, installed = true)))
        val a = prober.installedApk(s, pkg)
        assertEquals(InstalledApk(0, 1, 0, listOf(
            InstalledApk.UserState(0, installed = false, hidden = false, suspended = false, stopped = true, enabled = 0),
            InstalledApk.UserState(10, installed = true, hidden = false, suspended = false, stopped = true, enabled = 0),
        )), a)
        assertEquals(InstalledApk.Cause.NOT_INSTALLED_FOR_USER_0, a.cause)
        assertEquals("on the TV but not installed for user 0, installed for 1 other user", a.why())
        assertEquals("pm path base.apk lines 0, hash exit 1, current user 0, user 0 installed no, hidden no, suspended no, stopped yes, enabled 0, " +
            "2 users listed, installed for 1 other", a.summary())
        assertEquals(1, s.execLines.count { it == script }, "one read-only call")
    }

    @Test fun theOtherCauses() {
        fun parse(base: String, hash: String, vararg users: String) =
            InstalledApkParser.parse(AdbProber(ProbeFixtures.clock()).sections(ProbeStage.LAUNCHER, out(base, hash, "0", *users), listOf("hash", "current", "users")))
        assertEquals(InstalledApk.Cause.NOT_ON_TV, parse("0", "1").cause)
        assertEquals("the app is not on the TV", parse("0", "1").why())
        assertEquals(InstalledApk.Cause.HIDDEN_FOR_USER_0, parse("0", "1", user(0, installed = true, hidden = true)).cause)
        assertEquals(InstalledApk.Cause.NO_PATH, parse("0", "1", user(0, installed = true)).cause)
        assertEquals(InstalledApk.Cause.APK_UNREADABLE, parse("1", "1", user(0, installed = true)).cause)
        assertEquals(InstalledApk.Cause.UNKNOWN, parse("1", "0", user(0, installed = true)).cause)
        // Not installed for user 0 and nobody else: no "other user" words.
        assertEquals("on the TV but not installed for user 0", parse("0", "1", user(0, installed = false)).why())
        assertEquals(", installed for 2 other users", parse("0", "1", user(0, false), user(10, true), user(11, true)).why().substringAfter("user 0"))
    }

    // What a TV prints is untrusted: odd lines are skipped, a value that is not a number is unknown,
    // and the first line of a user id counts (the dump repeats "User N:" under other headings).
    @Test fun untrustedOutput() {
        val a = InstalledApkParser.parse(mapOf(
            "" to listOf("Error: something"), "hash" to listOf("0; rm -rf /"), "current" to listOf(""),
            "users" to listOf("User 0: installed=true", user(0, installed = false), user(0, installed = true), "    User x: installed=false", "    User 10: " + "a".repeat(LINE_CAP)),
        ))
        assertNull(a.basePaths)
        assertNull(a.hashExit)
        assertNull(a.currentUser)
        assertEquals(listOf(0), a.users.map { it.id })
        assertFalse(a.user0!!.installed!!)
        assertTrue("pm path base.apk lines ?, hash exit ?, current user ?" in a.summary())
        assertEquals("no user 0 line", InstalledApkParser.parse(emptyMap()).summary().substringAfter("current user ?, ").substringBefore(','))
    }

    // A firmware where the script stopped early is a ProbeException, never a guess.
    @Test fun aScriptThatStoppedEarlyIsAProbeException() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        s.recording.on(script, "0\n@@cyt@@ hash\n1\n")
        assertFailsWith<ProbeException> { prober.installedApk(s, pkg) }
    }
}
