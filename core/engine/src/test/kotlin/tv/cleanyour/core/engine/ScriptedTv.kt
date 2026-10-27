package tv.cleanyour.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.session.BootOutcome
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.PushResult
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.session.TvIdentity
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Recording
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of

// A TV as a small model that the rendered commands change, so the executor's own lines drive it:
// packages, HOME, settings, files, the watchdog process and the token file. Knobs make it refuse a
// package, crash the launcher, lose the connection at the Nth command or fail an install.
class ScriptedTv(initial: Snapshot = Samples.miTvSnapshot()) : TvSession {
    val packages: MutableMap<PackageName, PackageState> = initial.packages.toMutableMap()
    var home: Component? = initial.home.resolved
    val claimants: MutableList<Component> = initial.home.claimants.map { it.component }.toMutableList()
    val settings: MutableMap<SettingKey, String?> = initial.settings.toMutableMap()
    val files = mutableMapOf<String, ByteArray>()
    val watchdogs = mutableSetOf<String>()
    val blobs = mutableMapOf<Sha256, ByteArray>()
    val facts: DeviceFacts = initial.facts
    val execLog = mutableListOf<String>()
    var execCount = 0
    var focus: Component? = null
    var screenshot: ByteArray? = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    // Knobs.
    var refuse: (PackageName) -> Boolean = { false }
    var resolveHomeAs: ((Component?) -> Component?) = { it }   // what resolve-activity answers (a crashing launcher persona answers something else)
    var dropAtExec: Int? = null                                // the Nth exec (1-based) throws Transport.Closed and the session goes Reconnecting
    var dropOnLine: ((String) -> Boolean)? = null
    var installOutcome: InstallOutcome = InstallOutcome.Installed
    // Issues #80, #367: the streamed install ends with this error while the connection stays up;
    // with installStreamInstallsFirst the TV installed the APK before the error.
    var installStreamError: AdbError? = null
    var installStreamInstallsFirst = false
    var persistentOverride: Set<PackageName> = emptySet()
    var startFails = false
    var requestedPermissions: Map<PackageName, List<String>> = emptyMap()   // printed under "requested permissions:" in the package's dump
    var answer: ((String) -> ExecResult?)? = null              // a scripted answer for a line (normalized), before the model's own
    var onHomeKey: () -> Unit = {}                             // 0.4.6: what a Home key press does to the TV (focus)

    override val tvId: TvId = initial.tvId
    override val identity: TvIdentity = TvIdentity(tvId, facts.serialLast4, facts.device, facts.model, facts.sdk, facts.adbSecure, facts.adbFeatures, 1024 * 1024)
    private val connected = ConnectionState.Connected(Endpoint.PlainTcp("192.0.2.10"), facts.adbFeatures, 1024 * 1024)
    private val mutableState = MutableStateFlow<ConnectionState>(connected)
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    var holds = 0

    fun disconnect() { mutableState.value = ConnectionState.Reconnecting(AdbError.Transport.Closed, 1, 0) }
    fun reconnect() { mutableState.value = connected }

    fun snapshot(reason: SnapshotReason = SnapshotReason.PRE_PLAN): Snapshot = Samples.snapshot(
        tvId = tvId, facts = facts, packages = packages.toMap(),
        home = HomeResolution(home, claimants.map { HomeClaimant(it, if (it == home) 2 else 1) }), reason = reason,
    ).copy(settings = settings.toMap()).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    fun isOff(pkg: PackageName) = packages[pkg]?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }
    fun state(pkg: PackageName) = packages[pkg]?.enabled

    private fun setEnabled(pkg: PackageName, e: EnabledState): ExecResult {
        val p = packages[pkg] ?: return ExecResult(1, "", "Error: Unknown package: ${pkg.value}", false, 1)
        if (refuse(pkg)) return ExecResult(1, "", "Error: Shell cannot change component state for ${pkg.value} to $e", false, 1)
        packages[pkg] = p.copy(enabled = e)
        return ok("Package ${pkg.value} new state: $e")
    }

    private fun ok(out: String = "") = ExecResult(0, out, "", false, 1)
    private fun fail(err: String) = ExecResult(1, "", err, false, 1)

    fun dump(pkg: PackageName): String {
        val p = packages[pkg] ?: return ""
        val flags = buildList { if (p.system) add("SYSTEM"); add("HAS_CODE"); if (p.persistent || pkg in persistentOverride) add("PERSISTENT") }
        val enabled = when (p.enabled) { EnabledState.DEFAULT -> 0; EnabledState.ENABLED -> 1; EnabledState.DISABLED -> 2; EnabledState.DISABLED_USER -> 3 }
        val requested = requestedPermissions[pkg]?.let { l -> "    requested permissions:\n" + l.joinToString("") { "      $it\n" } + "    install permissions:\n" } ?: ""
        return "Packages:\n  Package [${pkg.value}] (1a2b3c):\n    versionCode=${p.versionCode} minSdk=21 targetSdk=28\n    flags=[ ${flags.joinToString(" ")} ]\n" + requested +
            "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=$enabled instant=false virtual=false\n"
    }

    fun run(lineText: String): ExecResult {
        val line = Recording.normalize(lineText)
        val tokens = line.split(' ')
        fun pkg(i: Int) = PackageName(tokens[i])
        answer?.invoke(line)?.let { return it }
        return when {
            line.startsWith("pm disable-user --user 0 ") -> setEnabled(pkg(4), EnabledState.DISABLED_USER)
            line.startsWith("pm enable --user 0 ") -> setEnabled(pkg(4), EnabledState.ENABLED)
            line.startsWith("pm default-state --user 0 ") -> setEnabled(pkg(4), EnabledState.DEFAULT)
            line == "pm list packages -d" -> ok(packages.filter { isOff(it.key) }.keys.joinToString("") { "package:${it.value}\n" })
            line == "pm list packages -f" -> ok(packages.keys.joinToString("") { "package:/data/app/${it.value}-1/base.apk=${it.value}\n" })
            line.startsWith("pm uninstall --user 0 ") -> { val p = pkg(4); if (packages.remove(p) == null) fail("Failure [not installed]") else { claimants.removeAll { it.pkg == p }; if (home?.pkg == p) home = claimants.firstOrNull(); ok("Success") } }
            line.startsWith("pm install ") -> install(tokens.last())
            line.startsWith("pm path ") -> ok(if (pkg(2) in packages) "package:/data/app/${tokens[2]}-1/base.apk\n" else "")
            line.startsWith("dumpsys package ") && tokens.size == 3 && tokens[2] != "packages" -> ok(dump(pkg(2)))
            line.startsWith("cmd package set-home-activity --user 0 ") -> {
                val c = component(tokens[5])
                if (c.pkg !in packages || isOff(c.pkg)) fail("Error: component not enabled") else { home = c; if (c !in claimants) claimants += c; ok() }
            }
            line.startsWith("cmd package resolve-activity --brief") -> {
                val h = resolveHomeAs(home)
                ok(if (h == null) "No activity found\n" else "priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n${h.flat}\n")
            }
            line.startsWith("nohup setsid sh -c ") || line.startsWith("( sh -c ") -> { watchdogs += Regex("cleanyourtv-rollback-([0-9a-f]{16})").find(line)!!.groupValues[1]; ok() }
            // pgrep and pkill match the pattern as a regex against the watchdog command lines the TV holds.
            line.startsWith("pgrep -f ") -> { val re = Regex(line.removePrefix("pgrep -f ")); val hits = watchdogs.filter { re.containsMatchIn(watchdogLine(it)) }; if (hits.isNotEmpty()) ok("4321\n") else ExecResult(1, "", "", false, 1) }
            line.startsWith("pkill -f ") -> { val re = Regex(line.removePrefix("pkill -f ")); val hits = watchdogs.filter { re.containsMatchIn(watchdogLine(it)) }; if (hits.isNotEmpty()) { watchdogs -= hits.toSet(); ok() } else ExecResult(1, "", "", false, 1) }
            line.startsWith("touch ") -> { files[tokens[1]] = ByteArray(0); ok() }
            line.startsWith("sha256sum ") -> files[tokens[1]]?.let { ok("${sha256Of(it).hex}  ${tokens[1]}\n") } ?: fail("sha256sum: ${tokens[1]}: No such file or directory")
            line.startsWith("rm -f ") -> { files.remove(tokens[2]); ok() }
            line.startsWith("mkdir -p ") -> ok()
            // am start -W reports "Status: ok" when the activity came up (launcher v2.4.0's check); a
            // package the TV does not have fails the way a real TV does, and startFails makes an
            // installed one fail the way a crashing activity does.
            line.startsWith("am start -W -n ") -> {
                val target = component(tokens[4])
                if (startFails || target.pkg !in packages) ok("Starting: Intent\nError type 3\nError: Activity class {${target.flat}} does not exist.\n")
                else ok("Starting: Intent\nStatus: ok\nComplete\n")
            }
            line.startsWith("am start ") || line.startsWith("am force-stop ") -> ok("Starting: Intent\n")
            line.startsWith("am broadcast ") -> ok("Broadcasting: Intent { act=${tokens[4]} }\nBroadcast completed: result=0\n")
            line == "input keyevent 3" -> { onHomeKey(); ok() }
            line.startsWith("settings put ") -> { val key = SettingKey.entries.first { it.key == tokens[3] }; settings[key] = tokens.drop(4).joinToString(" "); ok() }
            line.startsWith("settings delete ") -> { val key = SettingKey.entries.first { it.key == tokens[3] }; settings.remove(key); ok() }
            line.startsWith("settings list ") -> ok(settings.filter { it.key.ns.name.lowercase() == tokens[2] }.entries.joinToString("") { "${it.key.key}=${it.value}\n" })
            line == "reboot" -> ok()
            else -> ExecResult(127, "", "/system/bin/sh: ${tokens[0]}: not found", false, 1)
        }
    }

    // The command line a running watchdog shows in `ps`: `sh -c <body> cleanyourtv-rollback-<token>`.
    private fun watchdogLine(token: String) = "sh -c sleep 120 & wait; ... cleanyourtv-rollback-$token"

    private fun install(path: String): ExecResult {
        val bytes = files[path] ?: return fail("Failure [INSTALL_FAILED_INVALID_URI]")
        return installBytes(bytes)
    }

    fun installBytes(bytes: ByteArray): ExecResult {
        val sha = sha256Of(bytes)
        val (pkg, versionCode, signer) = installable[sha] ?: return fail("Failure [INSTALL_PARSE_FAILED_NOT_APK]")
        packages[pkg] = PackageState(EnabledState.ENABLED, false, versionCode, signer, false, false, null, true)
        installed[pkg] = sha
        return ok("Success")
    }

    // APK bytes the TV accepts: sha256 to (package, versionCode, signer).
    val installable = mutableMapOf<Sha256, Triple<PackageName, Long, Sha256>>()
    val installed = mutableMapOf<PackageName, Sha256>()

    private fun component(flat: String) = Component(PackageName(flat.substringBefore('/')), flat.substringAfter('/'))

    private fun gate(what: String) {
        execCount++
        execLog += what
        if (dropAtExec == execCount || dropOnLine?.invoke(what) == true) { disconnect(); throw AdbException(AdbError.Transport.Closed) }
        if (mutableState.value !is ConnectionState.Connected) throw AdbException(AdbError.Transport.Closed)
    }

    override suspend fun exec(line: ShellLine, spec: ExecSpec): ExecResult {
        gate(line.text)
        val r = run(line.text)
        return if (r.stdout.length > spec.stdoutCap) r.copy(stdout = r.stdout.take(spec.stdoutCap), truncated = true) else r
    }

    override fun lines(line: ShellLine, spec: ExecSpec): Flow<String> = flow { gate(line.text); run(line.text).stdout.lines().forEach { emit(it) } }

    override suspend fun push(src: BlobRef, dest: RemotePath, onProgress: (Long) -> Unit): PushResult {
        gate("push ${dest.absolute}")
        val bytes = blobs[src.sha256] ?: error("no blob ${src.sha256.hex}")
        files[dest.absolute] = bytes
        return PushResult(bytes.size.toLong(), 1, sha256Of(bytes))
    }

    override suspend fun pull(src: RemotePath, maxBytes: Long): ByteArray {
        gate("pull ${src.absolute}")
        return files[src.absolute]?.copyOf() ?: throw AdbException(AdbError.NonZeroExit(1, "remote object '${src.absolute}' does not exist"))
    }

    override suspend fun installStream(apk: BlobRef, size: Long, onProgress: (Long) -> Unit): InstallOutcome {
        gate("install-stream ${apk.sha256.hex}")
        installStreamError?.let { e ->
            if (installStreamInstallsFirst) installBytes(blobs[apk.sha256] ?: error("no blob"))
            throw AdbException(e)
        }
        val o = installOutcome
        if (o is InstallOutcome.Failed) return o
        val r = installBytes(blobs[apk.sha256] ?: error("no blob"))
        return if (r.exit == 0) InstallOutcome.Installed else InstallOutcome.Failed("INSTALL_FAILED", r.stderr)
    }

    override suspend fun reboot() { gate("reboot"); mutableState.value = ConnectionState.WaitingForBoot(0, 0) }
    override suspend fun awaitBoot(timeoutMs: Long): BootOutcome { mutableState.value = connected; return BootOutcome.Back("boot-2") }
    override fun hold(): SessionHold { holds++; return object : SessionHold { override fun close() { holds-- } } }
    override suspend fun close() { mutableState.value = ConnectionState.Idle }

    fun execs(prefix: String): Int = execLog.count { Recording.normalize(it).startsWith(prefix) }

    // Register APK bytes for a launcher artifact so the install ladder works.
    fun acceptApk(bytes: ByteArray, pkg: PackageName, versionCode: Long, signer: Sha256): Sha256 {
        val sha = sha256Of(bytes)
        blobs[sha] = bytes
        installable[sha] = Triple(pkg, versionCode, signer)
        return sha
    }
}

// A prober over the scripted TV's model (W4's parsers are not needed to test the engine).
class ScriptedProber(private val tv: ScriptedTv) : Prober {
    val calls = mutableListOf<String>()
    var screenshotFails = false
    var screenshotOkCount = Int.MAX_VALUE   // after this many successes, screenshots fail
    var installedShaOverride: Sha256? = null  // what the D40 hash of an installed APK reads, when set
    private var screenshots = 0
    override suspend fun facts(session: TvSession): DeviceFacts = tv.facts
    override suspend fun snapshot(session: TvSession, reason: SnapshotReason, onStage: (ProbeStage) -> Unit): Snapshot {
        calls += "snapshot $reason"; tv.exec(ShellLine0.props, ExecSpec.Default)   // one command, so a lost connection surfaces here too
        return tv.snapshot(reason)
    }
    override suspend fun homeNow(session: TvSession): HomeResolution { calls += "homeNow"; return tv.snapshot().home }
    override suspend fun homeFocus(session: TvSession): Component? { calls += "homeFocus"; tv.exec(ShellLine0.focus, ExecSpec.Default); return tv.focus }
    override suspend fun screenshot(session: TvSession): ByteArray? { calls += "screenshot"; screenshots++; return if (screenshotFails || screenshots > screenshotOkCount) null else tv.screenshot }
    override suspend fun fileSha256(session: TvSession, path: RemotePath): Sha256? = tv.files[path.absolute]?.let { sha256Of(it) }
    override suspend fun installedSha256(session: TvSession, pkg: PackageName): Sha256? { calls += "installedSha256 ${pkg.value}"; installedShaOverride?.let { return it }; return tv.installed[pkg] ?: tv.packages[pkg]?.let { Sha256("9".repeat(64)) } }
    override suspend fun freeDataMb(session: TvSession): Int = 900
    override suspend fun installRefusal(session: TvSession, noopApk: BlobRef): Boolean = false
    override suspend fun watchdogAlive(session: TvSession, token: RunToken): Boolean = token.value in tv.watchdogs
    override suspend fun bootId(session: TvSession): String = "boot-1"
}

// The probe's own fixed lines, so the scripted session logs them like any other command.
@OptIn(tv.cleanyour.core.model.RawShell::class)
object ShellLine0 {
    val props = ShellLine("'getprop'")
    val focus = ShellLine("'dumpsys' 'activity' 'activities'")
}

// A clock that follows the test scheduler's virtual time, so delays in the engine and the
// deadlines it computes agree.
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val wallAtZero: Long = Samples.NOW_MS) : Clock {
    override fun nowMs(): Long = wallAtZero + scheduler.currentTime
    override fun monotonicMs(): Long = scheduler.currentTime
}
