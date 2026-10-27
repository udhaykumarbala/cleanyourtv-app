package tv.cleanyour.core.probe

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HdmiSource
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SHA256_RE
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.SettingsNs
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.SnapshotCodec

// The Prober over a TvSession: reads only, batched per stage (D40), seven shell calls and one
// pull for a full snapshot, plus one call for the launchable apps (LaunchableApps). Every method fails with AdbException like the session it wraps; a
// parser failure is ProbeException. installRefusal is the one exception to "reads only": it
// installs and uninstalls the bundled no-op APK, which is the only way to learn the answer.
//
// Signers (launcher v2.4.0 plan, finding 4; RF-CURTAIN-1 F9): for each package in `signed` that
// the TV has, the snapshot carries the installed APK's signer (ApkSigners). When the installed APK
// hashes to one of `known` (the pinned APKs, sha256 to signer), that is its signer and nothing is
// copied; otherwise the APK (16 MiB at most) is copied into the app's TMP folder under a name of
// its own, pulled and deleted again. These copies are the second exception to "reads only", beside
// installRefusal. A signer that cannot be read stays null (unknown). The default names the bundled
// launcher and the TV curtain, the two packages whose replacement rules need it.
class AdbProber(
    private val clock: Clock,
    private val signed: Set<PackageName> = SIGNED_PACKAGES,
    private val known: Map<Sha256, Sha256> = emptyMap(),
) : Prober, LaunchableApps, SavedWifiOwners {

    private suspend fun run(
        session: TvSession, line: ShellLine, stage: ProbeStage, cap: Int, timeoutMs: Long = 30_000, retry: RetryClass = RetryClass.IDEMPOTENT_READ,
    ): ExecResult {
        val r = session.exec(line, ExecSpec(timeoutMs = timeoutMs, stdoutCap = cap, retry = retry))
        if (r.truncated) throw ProbeException(stage, "output over $cap bytes")
        return r
    }

    private fun lines(s: String): Sequence<String> = s.split('\n').let { if (s.endsWith('\n')) it.dropLast(1) else it }.asSequence()

    // The output of a batched script, split at its marker lines. Section "" is before the first.
    internal fun sections(stage: ProbeStage, stdout: String, expected: List<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur = ""
        out[cur] = mutableListOf()
        for (l in lines(stdout)) {
            val t = l.removeSuffix("\r")
            if (t.startsWith(ProbeScripts.MARK + " ")) {
                val name = t.removePrefix(ProbeScripts.MARK + " ")
                if (name !in expected || name in out) throw ProbeException(stage, "unexpected section marker")   // the name is the TV's text, never logged
                cur = name; out[cur] = mutableListOf(); continue
            }
            out.getValue(cur) += t
        }
        val missing = expected - out.keys
        if (missing.isNotEmpty()) throw ProbeException(stage, "the script stopped before ${missing.joinToString()}")
        return out
    }

    override suspend fun facts(session: TvSession): DeviceFacts {
        val r = run(session, ProbeScripts.FACTS, ProbeStage.FACTS, 512 * 1024)
        val s = sections(ProbeStage.FACTS, r.stdout, listOf("meminfo", "gms"))
        val props = GetpropParser.parse(s.getValue("").asSequence())
        fun prop(k: String) = props[k]?.takeIf { it.isNotEmpty() } ?: throw ProbeException(ProbeStage.FACTS, "getprop has no $k")
        val memKb = s.getValue("meminfo").firstNotNullOfOrNull { Regex("^MemTotal:\\s+(\\d{1,12}) kB").find(it)?.groupValues?.get(1)?.toLong() }
            ?: throw ProbeException(ProbeStage.FACTS, "no MemTotal in /proc/meminfo")
        val id = session.identity
        return DeviceFacts(
            brand = prop("ro.product.brand"), manufacturer = prop("ro.product.manufacturer"), model = prop("ro.product.model"),
            device = prop("ro.product.device"), serialLast4 = id.serialLast4,
            sdk = prop("ro.build.version.sdk").toIntOrNull() ?: throw ProbeException(ProbeStage.FACTS, "ro.build.version.sdk is not a number"),
            release = prop("ro.build.version.release"), fingerprint = prop("ro.build.fingerprint"),
            abis = (props["ro.product.cpu.abilist"] ?: "").split(',').filter { it.isNotBlank() },
            ramMb = (memKb / 1024).toInt(),
            hasGms = s.getValue("gms").any { it.trim().startsWith("package:") },
            adbSecure = props["ro.adb.secure"] == "1",
            adbFeatures = id.features,
            installRefusal = false,   // learned by installRefusal(), never guessed from facts
            locale = props["persist.sys.locale"]?.takeIf { it.isNotEmpty() } ?: props["ro.product.locale"] ?: "",
        )
    }

    override suspend fun snapshot(session: TvSession, reason: SnapshotReason, onStage: (ProbeStage) -> Unit): Snapshot {
        onStage(ProbeStage.FACTS)
        val facts = facts(session)

        onStage(ProbeStage.PACKAGES)
        val listed = run(session, ProbeScripts.PACKAGES, ProbeStage.PACKAGES, 4 * 1024 * 1024).also { check(it, ProbeStage.PACKAGES) }
        val disabled = run(session, ProbeScripts.DISABLED, ProbeStage.PACKAGES, 4 * 1024 * 1024).also { check(it, ProbeStage.PACKAGES) }
        val flags = run(session, ProbeScripts.PACKAGE_FLAGS, ProbeStage.PACKAGES, 4 * 1024 * 1024)
        val all = PackagesParser.parse(lines(listed.stdout))
        val off = PackagesParser.parse(lines(disabled.stdout)).map { it.name }.toSet()
        val info = DumpsysPackageParser.parse(lines(flags.stdout))

        onStage(ProbeStage.HOME)
        val home = homeNow(session)

        onStage(ProbeStage.INPUTS)
        val inputsOut = run(session, ProbeScripts.INPUTS, ProbeStage.INPUTS, 1024 * 1024)
        val inputSections = sections(ProbeStage.INPUTS, inputsOut.stdout, listOf("hdmi"))
        val tif = TvInputParser.parse(inputSections.getValue("").asSequence())
        val cec = HdmiControlParser.parse(inputSections.getValue("hdmi").asSequence())

        onStage(ProbeStage.SETTINGS)
        val settingsOut = run(session, ProbeScripts.SETTINGS, ProbeStage.SETTINGS, 4 * 1024 * 1024)
        val settingSections = sections(ProbeStage.SETTINGS, settingsOut.stdout, listOf("global"))
        val tables = mapOf(
            SettingsNs.SECURE to SettingsListParser.parse(settingSections.getValue("").asSequence()),
            SettingsNs.GLOBAL to SettingsListParser.parse(settingSections.getValue("global").asSequence()),
        )
        val settings = SettingKey.entries.filter { it.ns in tables }.associateWith { tables.getValue(it.ns)[it.key] }
        val secure = tables.getValue(SettingsNs.SECURE)
        val ime = secure["default_input_method"]?.let(::component)
        val screensaver = secure["screensaver_components"]?.split(',')?.mapNotNull { component(it.trim()) } ?: emptyList()

        onStage(ProbeStage.LAUNCHER)
        val overlay = pullOrNull(session, ProbeScripts.overlayConfig, OVERLAY_CAP)

        val signers = signed.filter { pkg -> all.any { it.name == pkg.value } }.associateWith { signerOf(session, it) }

        val tvInputPkgs = tif.map { it.pkg }.toSet()
        val packages = LinkedHashMap<PackageName, PackageState>()
        for (p in all) {
            val name = packageName(p.name) ?: continue      // a name PackageName refuses; "android" is admitted (RFC-W1-2)
            val i = info[p.name]
            val path = p.path ?: i?.codePath
            packages[name] = PackageState(
                enabled = enabledState(p.name in off, i?.enabled),
                // Review of beta3: a flags line that came back empty says nothing, so the install path decides.
                system = i?.flags?.takeIf { it.isNotEmpty() }?.contains("SYSTEM") ?: SYSTEM_PATHS.any { path?.startsWith(it) == true },
                versionCode = i?.versionCode ?: 0,
                signer = signers[name],
                persistent = i?.flags?.contains("PERSISTENT") == true,
                tvInput = name in tvInputPkgs,
                label = null,
                installedByUs = false,
            )
        }
        onStage(ProbeStage.DONE)
        val draft = Snapshot(
            tvId = session.tvId, takenAtMs = clock.nowMs(), reason = reason, facts = facts, packages = packages, home = home,
            settings = settings, ime = ime, screensaver = screensaver, launcherOverlay = overlay, hdmi = hdmiSources(tif, cec),
            contentHash = ZERO,
        )
        return draft.copy(contentHash = SnapshotCodec.hash(draft))
    }

    private fun check(r: ExecResult, stage: ProbeStage) {
        if (r.exit != 0) throw ProbeException(stage, "exit ${r.exit}: ${r.stderr.take(200)}")
    }

    override suspend fun homeNow(session: TvSession): HomeResolution {
        val r = run(session, ProbeScripts.HOME, ProbeStage.HOME, 64 * 1024)
        val s = try {
            sections(ProbeStage.HOME, r.stdout, listOf("resolved"))
        } catch (e: ProbeException) {
            // A firmware where chained `cmd package` stops early: ask for the resolution alone.
            val alone = run(session, ProbeScripts.HOME_RESOLVED, ProbeStage.HOME, 64 * 1024)
            mapOf("" to lines(r.stdout).toList(), "resolved" to lines(alone.stdout).toList())
        }
        return HomeResolution(HomeParser.parseResolved(s.getValue("resolved").asSequence()), HomeParser.parse(s.getValue("").asSequence()))
    }

    override suspend fun launchable(session: TvSession): Set<PackageName> {
        val r = run(session, ProbeScripts.LAUNCHABLE, ProbeStage.LAUNCHER, 256 * 1024)
        val s = sections(ProbeStage.LAUNCHER, r.stdout, listOf("launcher"))
        return LaunchableParser.parse(s.getValue("").asSequence()) + LaunchableParser.parse(s.getValue("launcher").asSequence())
    }

    override suspend fun savedWifi(session: TvSession): SavedWifi {
        val r = run(session, ProbeScripts.WIFI_OWNERS, ProbeStage.LAUNCHER, 256 * 1024)
        return WifiOwnersParser.parse(lines(r.stdout))
    }

    // The installed APK's signer, or null when it cannot be read (a transport error included: the
    // snapshot must not fail for it). The copy in TMP goes whatever happened.
    private suspend fun signerOf(session: TvSession, pkg: PackageName): Sha256? {
        if (known.isNotEmpty()) {
            val sha = try { installedSha256(session, pkg) } catch (_: AdbException) { null } catch (_: ProbeException) { null }
            sha?.let { known[it] }?.let { return it }
        }
        val path = ProbeScripts.signerPath(token())
        return try {
            val copy = run(session, ProbeScripts.copyApk(pkg, path, SIGNER_APK_CAP), ProbeStage.LAUNCHER, 16 * 1024, timeoutMs = 60_000, retry = RetryClass.IDEMPOTENT_WRITE)
            if (copy.exit != 0) null else pullOrNull(session, path, SIGNER_APK_CAP)?.let { ApkSigners.signerSha256(it) }
        } catch (_: AdbException) {
            null
        } catch (_: ProbeException) {
            null
        } finally {
            withContext(NonCancellable) { bestEffort(session, ProbeScripts.signerCleanup(path)) }
        }
    }

    private fun token(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    override suspend fun homeFocus(session: TvSession): Component? {
        val r = run(session, ProbeScripts.HOME_FOCUS, ProbeStage.HOME, 64 * 1024)
        return ResumedActivityParser.parse(lines(r.stdout))
    }

    override suspend fun screenshot(session: TvSession): ByteArray? {
        val png = try {
            val r = run(session, ProbeScripts.SCREENSHOT, ProbeStage.DONE, 16 * 1024, timeoutMs = 15_000, retry = RetryClass.IDEMPOTENT_WRITE)
            if (r.exit != 0) null else pullOrNull(session, ProbeScripts.screenPath, SCREENSHOT_CAP)
        } finally {
            // R5: the temp file goes whatever happened above, cancellation included.
            withContext(NonCancellable) { bestEffort(session, ProbeScripts.SCREENSHOT_CLEANUP) }
        }
        return png?.takeIf { it.size >= 8 && it.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) }
    }

    override suspend fun fileSha256(session: TvSession, path: RemotePath): Sha256? =
        firstHash(run(session, ProbeScripts.fileSha256(path), ProbeStage.LAUNCHER, 4096))

    override suspend fun installedSha256(session: TvSession, pkg: PackageName): Sha256? =
        firstHash(run(session, ProbeScripts.installedSha256(pkg), ProbeStage.LAUNCHER, 4096))

    private fun firstHash(r: ExecResult): Sha256? {
        if (r.exit != 0) return null
        val h = r.stdout.trim().substringBefore(' ').lowercase()
        return if (SHA256_RE.matches(h)) Sha256(h) else null
    }

    override suspend fun freeDataMb(session: TvSession): Int {
        val r = run(session, ProbeScripts.FREE_DATA, ProbeStage.FACTS, 16 * 1024)
        val row = lines(r.stdout).map { it.trim().split(Regex("\\s+")) }.lastOrNull { it.size >= 6 && it.last() == "/data" }
            ?: throw ProbeException(ProbeStage.FACTS, "df printed no /data row")
        val kb = row[3].toLongOrNull() ?: throw ProbeException(ProbeStage.FACTS, "df Available is not a number: ${row[3]}")
        return (kb / 1024).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    override suspend fun installRefusal(session: TvSession, noopApk: BlobRef): Boolean {
        run(session, ProbeScripts.MKDIR_TMP, ProbeStage.LAUNCHER, 4096, retry = RetryClass.IDEMPOTENT_WRITE)
        // R5: until the install has clearly failed or the uninstall has clearly worked, the no-op
        // package may be on the TV, so the cleanup below uninstalls it again. A timeout, a lost
        // connection or a cancellation after `pm install` must not leave it behind.
        var mayBeInstalled = true
        try {
            session.push(noopApk, ProbeScripts.noopPath)
            val r = run(session, ProbeScripts.NOOP_INSTALL, ProbeStage.LAUNCHER, 16 * 1024, timeoutMs = 120_000, retry = RetryClass.IDEMPOTENT_WRITE)
            val out = r.stdout + "\n" + r.stderr
            if (r.exit == 0 && "Success" in out) {
                val u = run(session, ProbeScripts.NOOP_UNINSTALL, ProbeStage.LAUNCHER, 16 * 1024, timeoutMs = 60_000, retry = RetryClass.IDEMPOTENT_WRITE)
                mayBeInstalled = !(u.exit == 0 && "Success" in u.stdout + "\n" + u.stderr)
                return false
            }
            mayBeInstalled = false
            if ("INSUFFICIENT_STORAGE" in out) throw AdbException(AdbError.Device.NotEnoughSpace)
            return true
        } finally {
            withContext(NonCancellable) {
                if (mayBeInstalled) bestEffort(session, ProbeScripts.NOOP_UNINSTALL, timeoutMs = 60_000)
                bestEffort(session, ProbeScripts.NOOP_CLEANUP)
            }
        }
    }

    // Cleanup that must not mask the original outcome: transport and probe errors are dropped.
    private suspend fun bestEffort(session: TvSession, line: ShellLine, timeoutMs: Long = 10_000) {
        try {
            session.exec(line, ExecSpec(timeoutMs = timeoutMs, stdoutCap = 4096, retry = RetryClass.IDEMPOTENT_WRITE))
        } catch (_: AdbException) {
        } catch (_: ProbeException) {
        }
    }

    override suspend fun watchdogAlive(session: TvSession, token: RunToken): Boolean {
        val r = run(session, ProbeScripts.watchdogAlive(token), ProbeStage.HOME, 16 * 1024)
        if (r.exit == 0) return r.stdout.lines().any { it.trim().matches(Regex("\\d{1,7}")) }
        if (r.exit == 1) return false
        // No pgrep on this toybox: read the process list and look for the watchdog's name.
        val ps = run(session, ProbeScripts.PS_ARGS, ProbeStage.HOME, 1024 * 1024)
        return ps.stdout.lines().any { "cleanyourtv-rollback-${token.value}" in it }
    }

    override suspend fun bootId(session: TvSession): String {
        val id = run(session, ProbeScripts.BOOT_ID, ProbeStage.FACTS, 4096).stdout.trim()
        if (!BOOT_ID_RE.matches(id)) throw ProbeException(ProbeStage.FACTS, "boot_id is not a UUID")
        return id
    }

    private suspend fun pullOrNull(session: TvSession, path: RemotePath, cap: Long): ByteArray? = try {
        session.pull(path, cap)
    } catch (e: AdbException) {
        if (e.error is AdbError.NonZeroExit || e.error is AdbError.Transport.Protocol) null else throw e
    }

    companion object {
        const val OVERLAY_CAP = 256L * 1024
        const val SCREENSHOT_CAP = 2L * 1024 * 1024
        const val SIGNER_APK_CAP = 16L * 1024 * 1024
        private val random = java.security.SecureRandom()
        val SIGNED_PACKAGES: Set<PackageName> = setOf(PackageName("com.gathin.tvhome"), PackageName("tv.cleanyour.curtain"))
        private val ZERO = Sha256("0".repeat(64))
        private val BOOT_ID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val SYSTEM_PATHS = listOf("/system/", "/product/", "/vendor/", "/odm/", "/system_ext/", "/apex/")

        // User 0's state: 1 enabled, 2 disabled, 3 disabled by the user (pm disable-user), 4
        // disabled until used, 0 the manifest default; `pm list packages -d` is the ground truth
        // for whether it is off.
        fun enabledState(listedDisabled: Boolean, code: Int?): EnabledState = when {
            listedDisabled -> when (code) { 3, null -> EnabledState.DISABLED_USER; else -> EnabledState.DISABLED }
            code == 1 -> EnabledState.ENABLED
            else -> EnabledState.DEFAULT
        }

        // One row per HDMI port the TV knows of (CEC port list or TIF hardware), with the first
        // CEC name seen on it.
        fun hdmiSources(tif: List<TvInputParser.Input>, cec: HdmiControlParser.Report): List<HdmiSource> {
            val ports = (cec.ports.map { it.id } + tif.mapNotNull { it.hdmiPort } + cec.devices.filter { !it.isTvItself }.map { it.port }).filter { it > 0 }.distinct().sorted()
            return ports.map { port ->
                val devices = cec.devices.filter { !it.isTvItself && it.port == port }
                HdmiSource(
                    port = port,
                    cecName = devices.map { it.name }.firstOrNull { it.isNotBlank() },
                    tifInputId = tif.firstOrNull { it.hdmiPort == port }?.id,
                    behindSwitch = devices.any { it.behindSwitch },
                )
            }
        }
    }
}
