package tv.cleanyour.core.ops

import tv.cleanyour.core.model.CLASS_RE
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PACKAGE_RE
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.RUN_TOKEN_RE
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SHA256_RE
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.SettingsNs
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import java.security.MessageDigest

// The one renderer (SECURITY 3, PRODUCT-SPEC 5.2). An op is a verb with typed arguments; this
// turns it into exactly one argv list of constant words plus validated tokens, quotes every token,
// joins with single spaces and asserts the result (ShellTokens). The only lines with more than one
// command are the watchdog template (Watchdog), the launcher provider's write and read
// (ThemeProviderLines, RFC-THEME-1) and, since RFC-HELPER-1, the Home button helper's templates
// (HelperLines). Verb words are constants in this file, never data.
//
// Every write op carries its verify read and `check` decides from that read's output; an op with
// no verify read is checked against its own output (VerifyHome) or always passes.
@OptIn(RawShell::class)
class ShellRenderer(private val watchdogVariant: WatchdogVariant = WatchdogVariant.NOHUP_SETSID) : OpRenderer {

    override fun render(op: Op): Rendered = try {
        renderOrThrow(op)
    } catch (e: RenderRefused) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw RenderRefused(op, e.message ?: "invalid argument")
    } catch (e: IllegalStateException) {
        throw RenderRefused(op, "renderer assertion failed: " + (e.message ?: "unknown"))
    }

    private fun renderOrThrow(op: Op): Rendered = when (op) {
        is Op.Disable -> Rendered(op, lines(line(PM, "disable-user", USER, "0", writePkg(op.pkg))), null, Privilege.PROFILE_STEP,
            Op.Read(ProbeKind.DISABLED), T_PM, CAP_DEFAULT)
        is Op.Enable -> Rendered(op, lines(line(PM, "enable", USER, "0", pkg(op.pkg))), null, Privilege.PROFILE_STEP,
            Op.Read(ProbeKind.DISABLED), T_PM, CAP_DEFAULT)
        is Op.DefaultState -> Rendered(op, lines(line(PM, "default-state", USER, "0", writePkg(op.pkg))), null, Privilege.PROFILE_STEP,
            Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(op.pkg)), T_PM, CAP_DEFAULT)
        is Op.Install -> {
            writePkg(op.pkg)   // the framework package and the static denylist are never installed over
            require(op.apk.dir == RemoteDir.TMP) { "install source must be under ${RemoteDir.TMP.path}" }
            require(op.apk.segments.last().endsWith(".apk")) { "install source must be an .apk file" }
            require(op.versionCode >= 0) { "versionCode must not be negative" }
            sha(op.sha256); sha(op.signer)
            val argv = mutableListOf(PM, "install")
            if (InstallFlag.REPLACE in op.flags) argv += "-r"
            argv += listOf(USER, "0", op.apk.absolute)
            Rendered(op, lines(line(*argv.toTypedArray())), null, Privilege.COMMUNITY,
                Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(pkgName(op.pkg))), T_INSTALL, CAP_DEFAULT)
        }
        is Op.InstallStream -> {
            writePkg(op.pkg)
            require(op.blob.sha256 == op.sha256) { "blob sha256 differs from the op's sha256" }
            require(op.blob.size in 1..MAX_APK_BYTES) { "apk size must be 1 to $MAX_APK_BYTES bytes" }
            require(op.versionCode >= 0) { "versionCode must not be negative" }
            sha(op.signer)
            Rendered(op, emptyList(), Transfer.StreamInstall(op.blob, op.blob.size), Privilege.COMMUNITY,
                Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(pkgName(op.pkg))), T_INSTALL + op.blob.size / 50, CAP_DEFAULT)
        }
        is Op.Uninstall -> Rendered(op, lines(line(PM, "uninstall", USER, "0", writePkg(op.pkg))), null, Privilege.PROFILE_STEP,
            Op.Read(ProbeKind.PACKAGES), T_PM, CAP_DEFAULT)
        is Op.SettingsPut -> {
            val ns = op.key.ns.name.lowercase()
            val value: String? = op.value
            val argv: List<String> = if (value == null) {
                listOf(SETTINGS, "delete", ns, op.key.key)
            } else {
                require(op.key.valueShape.matches(value)) { "value does not match the shape of ${op.key.key}" }
                listOf(SETTINGS, "put", ns, op.key.key, value)
            }
            val privilege = if (op.key == SettingKey.ADB_ENABLED || op.key == SettingKey.ADB_WIFI_ENABLED) Privilege.SESSION_END else Privilege.PROFILE_STEP
            Rendered(op, lines(line(*argv.toTypedArray())), null, privilege, Op.Read(ProbeKind.SETTINGS, ReadArg.Ns(op.key.ns)), T_DEFAULT, CAP_DEFAULT)
        }
        is Op.SetHome -> Rendered(op, lines(line(CMD, "package", "set-home-activity", USER, "0", comp(op.component))), null,
            Privilege.TAKEOVER, Op.Read(ProbeKind.HOME), T_DEFAULT, CAP_DEFAULT)
        is Op.VerifyHome -> { comp(op.expected); Rendered(op, lines(line(*HOME_ARGV)), null, Privilege.READ, null, T_DEFAULT, CAP_DEFAULT) }
        is Op.ArmHomeWatchdog -> {
            // A token file left by an earlier run of the same plan would defuse this watchdog from
            // the start, so it is removed first (R1); then the template, the one multi-command line.
            val text = Watchdog.render(watchdogVariant, op.stock, op.seconds, op.token)
            Rendered(op, listOf(line("rm", "-f", tokenFile(op.token).absolute), ShellLine(text)), null, Privilege.TAKEOVER,
                Op.Read(ProbeKind.WATCHDOG_ALIVE, ReadArg.Token(op.token)), T_DEFAULT, CAP_DEFAULT)
        }
        is Op.ConfirmHome -> {
            val file = tokenFile(op.token)
            Rendered(op, lines(line("touch", file.absolute)), null, Privilege.TAKEOVER, Op.Read(ProbeKind.SHA256_OF_FILE, ReadArg.Path(file)), T_DEFAULT, CAP_DEFAULT)
        }
        is Op.KillWatchdog -> Rendered(op, lines(line("pkill", "-f", processPattern(op.token))), null, Privilege.TAKEOVER,
            Op.Read(ProbeKind.WATCHDOG_ALIVE, ReadArg.Token(op.token)), T_DEFAULT, CAP_DEFAULT)
        Op.PressHome -> Rendered(op, lines(line("input", "keyevent", "3")), null, Privilege.PROFILE_STEP, null, T_DEFAULT, CAP_DEFAULT)
        is Op.Mkdir -> Rendered(op, lines(line("mkdir", "-p", op.dir.path)), null, Privilege.COMMUNITY, null, T_DEFAULT, CAP_DEFAULT)
        is Op.PushAsset -> {
            require(op.src.sha256 == op.sha256) { "blob sha256 differs from the op's sha256" }
            require(op.src.size in 1..MAX_APK_BYTES) { "push size must be 1 to $MAX_APK_BYTES bytes" }
            when (op.route) {
                FileRoute.SYNC -> Rendered(op, emptyList(), Transfer.Push(op.src, op.dest, 0x1A4), Privilege.COMMUNITY,
                    Op.Read(ProbeKind.SHA256_OF_FILE, ReadArg.Path(op.dest)), T_PUSH, CAP_DEFAULT)
                // RFC-THEME-1: the sync push stages the file in TMP (the push runs before the line), the template
                // writes it through the launcher's provider, and the check reads it back the same way.
                FileRoute.THEME_PROVIDER -> Rendered(op, listOf(ShellLine(ThemeProviderLines.write(op.dest))),
                    Transfer.Push(op.src, ThemeProviderLines.stage(op.dest), 0x1A4), Privilege.COMMUNITY,
                    Op.Read(ProbeKind.LAUNCHER_FILE_SHA256, ReadArg.Path(op.dest)), T_PUSH, CAP_DEFAULT)
            }
        }
        is Op.RemoveFile -> when (op.route) {
            FileRoute.SYNC -> Rendered(op, lines(line("rm", "-f", op.path.absolute)), null, Privilege.COMMUNITY,
                Op.Read(ProbeKind.SHA256_OF_FILE, ReadArg.Path(op.path)), T_DEFAULT, CAP_DEFAULT)
            // RFC-THEME-1: the launcher deletes its own file (an ordinary quoted line), checked as the launcher reads it.
            FileRoute.THEME_PROVIDER -> Rendered(op, lines(line("content", "delete", "--uri", ThemeProviderLines.uri(op.path))), null, Privilege.COMMUNITY,
                Op.Read(ProbeKind.LAUNCHER_FILE_SHA256, ReadArg.Path(op.path)), T_DEFAULT, CAP_DEFAULT)
        }
        is Op.RestartHome -> Rendered(op,
            listOf(line(AM, "force-stop", pkg(op.component.pkg)), line(AM, "start", "-n", comp(op.component))),
            null, Privilege.COMMUNITY, null, T_DEFAULT, CAP_DEFAULT)
        is Op.StartActivity -> if (op.wait) {
            Rendered(op, lines(line(AM, "start", "-W", "-n", comp(op.component))), null, Privilege.PROFILE_STEP, null, T_START_WAIT, CAP_DEFAULT)
        } else {
            Rendered(op, lines(line(AM, "start", "-n", comp(op.component))), null, Privilege.PROFILE_STEP, null, T_DEFAULT, CAP_DEFAULT)
        }
        is Op.Read -> read(op)
        is Op.Settle -> {
            require(op.seconds in 1..60) { "settle seconds must be 1 to 60" }
            Rendered(op, emptyList(), null, Privilege.READ, null, op.seconds * 1000L, CAP_DEFAULT)
        }
        Op.Reboot -> Rendered(op, lines(line("reboot")), null, Privilege.TAKEOVER, null, T_REBOOT, CAP_DEFAULT)
        Op.DebugOff -> Rendered(op, lines(line(SETTINGS, "put", "global", SettingKey.ADB_ENABLED.key, "0")), null, Privilege.SESSION_END, null, T_DEFAULT, CAP_DEFAULT)
        is Op.CurtainCue -> cue(op)
        // RFC-HELPER-1: the Home button helper's three ops, each one HelperLines template.
        is Op.AccessibilityAdd -> Rendered(op, lines(ShellLine(HelperLines.add(op.expected, op.component))), null, Privilege.TAKEOVER,
            Op.Read(ProbeKind.SETTINGS, ReadArg.Ns(SettingsNs.SECURE)), T_DEFAULT, CAP_DEFAULT)
        is Op.AccessibilityRemove -> {
            val expected = op.expected
            val text = if (expected != null) {
                require(!op.offIfEmpty) { "the compare-and-set removal leaves accessibility_enabled to its own step" }
                HelperLines.casRemove(expected, op.component)
            } else HelperLines.tvRemove(op.component, op.offIfEmpty)
            Rendered(op, lines(ShellLine(text)), null, Privilege.PROFILE_STEP, Op.Read(ProbeKind.SETTINGS, ReadArg.Ns(SettingsNs.SECURE)), T_DEFAULT, CAP_DEFAULT)
        }
        is Op.ArmHelperWatchdog -> {
            val text = HelperLines.watchdog(watchdogVariant, op.component, op.seconds, op.token, op.offIfEmpty)
            Rendered(op, listOf(line("rm", "-f", tokenFile(op.token).absolute), ShellLine(text)), null, Privilege.TAKEOVER,
                Op.Read(ProbeKind.WATCHDOG_ALIVE, ReadArg.Token(op.token)), T_DEFAULT, CAP_DEFAULT)
        }
    }

    // RFC-CURTAIN-1: one am broadcast to the curtain's receiver. The action and every extra name are
    // constants here; the values are the run token, two enum names and four numbers, each checked
    // against its grammar again, so no free text can reach the TV. Only our curtain package is a
    // receiver: a cue can never be aimed at another app.
    private fun cue(op: Op.CurtainCue): Rendered {
        require(op.receiver.pkg.value == CURTAIN_PACKAGE) { "a cue goes to $CURTAIN_PACKAGE only" }
        val receiver = comp(op.receiver)
        val stage = op.stage.name
        val group = op.group.name
        require(CUE_WORD_RE.matches(stage) && CUE_WORD_RE.matches(group)) { "cue stage or group fails ${CUE_WORD_RE.pattern}" }
        val numbers = listOf(op.count, op.total, op.groupCount, op.groupTotal)
        require(numbers.all { it in 0..CUE_MAX }) { "cue counts must be 0 to $CUE_MAX" }
        val argv = listOf(AM, "broadcast", "-n", receiver, "-a", CUE_ACTION,
            ES, "token", token(op.token).value, ES, "stage", stage, ES, "group", group,
            EI, "count", op.count.toString(), EI, "total", op.total.toString(),
            EI, "gcount", op.groupCount.toString(), EI, "gtotal", op.groupTotal.toString(),
            // The receiver runs in the foreground queue, not behind the package broadcasts the
            // switch-offs just sent, so a cue returns quickly (RF-CURTAIN-1 F6).
            RECEIVER_FOREGROUND)
        return Rendered(op, lines(line(*argv.toTypedArray())), null, Privilege.PROFILE_STEP, null, T_DEFAULT, CAP_DEFAULT)
    }

    // R0: 3 the kind and the argument must pair; every command is fixed with at most one token.
    private fun read(op: Op.Read): Rendered {
        val arg = op.arg
        fun none() { require(arg == null) { "${op.kind} takes no argument" } }
        fun pkgArg(): PackageName { require(arg is ReadArg.Pkg) { "${op.kind} takes a package" }; return pkgName(arg.pkg) }
        fun pathArg(): RemotePath { require(arg is ReadArg.Path) { "${op.kind} takes a path" }; return arg.path }
        fun tokenArg(): RunToken { require(arg is ReadArg.Token) { "${op.kind} takes a run token" }; return token(arg.token) }
        fun nsArg(): String { require(arg is ReadArg.Ns) { "${op.kind} takes a settings namespace" }; return arg.ns.name.lowercase() }
        fun r(argv: List<String>, cap: Int = CAP_DEFAULT, timeout: Long = T_DEFAULT, transfer: Transfer? = null) =
            Rendered(op, lines(line(*argv.toTypedArray())), transfer, Privilege.READ, null, timeout, cap)
        return when (op.kind) {
            ProbeKind.PROPS -> { none(); r(listOf("getprop"), CAP_LIST) }
            ProbeKind.PACKAGES -> { none(); r(listOf(PM, "list", "packages", "-f"), CAP_BIG) }
            ProbeKind.DISABLED -> { none(); r(listOf(PM, "list", "packages", "-d"), CAP_LIST) }
            ProbeKind.HOME -> { none(); r(HOME_ARGV.toList()) }
            ProbeKind.HOME_FOCUS -> { none(); r(listOf(DUMPSYS, "activity", "activities"), CAP_DUMP, T_DUMPSYS) }
            ProbeKind.TIF_INPUTS -> { none(); r(listOf(DUMPSYS, "tv_input"), CAP_DUMP, T_DUMPSYS) }
            ProbeKind.CEC -> { none(); r(listOf(DUMPSYS, "hdmi_control"), CAP_DUMP, T_DUMPSYS) }
            ProbeKind.SETTINGS -> r(listOf(SETTINGS, "list", nsArg()), CAP_BIG)
            ProbeKind.IME -> { none(); r(listOf(SETTINGS, "get", "secure", "default_input_method")) }
            ProbeKind.SCREENSAVER -> { none(); r(listOf(SETTINGS, "get", "secure", "screensaver_components")) }
            ProbeKind.SCREENCAP -> { none(); r(listOf("screencap", "-p", SCREEN_PNG.absolute), CAP_DEFAULT, T_DEFAULT, Transfer.Pull(SCREEN_PNG, MAX_PNG_BYTES)) }
            ProbeKind.SHA256_OF_FILE -> r(listOf("sha256sum", pathArg().absolute))
            ProbeKind.SHA256_OF_INSTALLED -> r(listOf(PM, "path", pkgArg().value))   // the prober hashes the printed path (D40 script)
            ProbeKind.PACKAGE_DUMP -> r(listOf(DUMPSYS, "package", pkgArg().value), CAP_DUMP, T_DUMPSYS)
            ProbeKind.PERSISTENT -> { none(); r(listOf(DUMPSYS, "package", "packages"), CAP_BIG, T_DUMPSYS) }
            ProbeKind.DF_DATA -> { none(); r(listOf("df", "/data")) }
            ProbeKind.WATCHDOG_ALIVE -> r(listOf("pgrep", "-f", processPattern(tokenArg())))
            ProbeKind.BOOT_ID -> { none(); r(listOf("cat", "/proc/sys/kernel/random/boot_id")) }
            ProbeKind.BOOT_COMPLETED -> { none(); r(listOf("getprop", "sys.boot_completed")) }
            ProbeKind.LAUNCHER_DIR -> { none(); r(listOf("ls", "-l", RemoteDir.OVERLAY.path)) }
            // RFC-THEME-1: the one read line with a pipe, a template like the watchdog's (ThemeProviderLines).
            ProbeKind.LAUNCHER_FILE_SHA256 -> Rendered(op, listOf(ShellLine(ThemeProviderLines.read(pathArg()))), null, Privilege.READ, null, T_DEFAULT, CAP_DEFAULT)
        }
    }

    override fun inverse(op: Op, prior: Prior): Op? = when (op) {
        is Op.Disable -> when (prior.packageState) {
            null, EnabledState.ENABLED -> Op.Enable(op.pkg)
            EnabledState.DEFAULT -> Op.DefaultState(op.pkg)
            EnabledState.DISABLED_USER, EnabledState.DISABLED -> null   // it was off before us
        }
        is Op.Enable -> when (prior.packageState) {
            EnabledState.DISABLED_USER, EnabledState.DISABLED -> Op.Disable(op.pkg)
            EnabledState.DEFAULT -> Op.DefaultState(op.pkg)
            null, EnabledState.ENABLED -> null   // it was on before us
        }
        is Op.DefaultState -> when (prior.packageState) {
            EnabledState.ENABLED -> Op.Enable(op.pkg)
            EnabledState.DISABLED_USER, EnabledState.DISABLED -> Op.Disable(op.pkg)
            null, EnabledState.DEFAULT -> null
        }
        is Op.Install -> if (prior.present == false) Op.Uninstall(op.pkg) else null   // reinstalling the previous APK needs a blob we do not keep in 0.1
        is Op.InstallStream -> if (prior.present == false) Op.Uninstall(op.pkg) else null
        is Op.SettingsPut -> if (op.key == SettingKey.ADB_ENABLED || op.key == SettingKey.ADB_WIFI_ENABLED) null   // never 1 from the app: the user turns debugging on from the TV
            else Op.SettingsPut(op.key, prior.settingValue)
        is Op.SetHome -> prior.home?.let { Op.SetHome(it) }
        is Op.ArmHomeWatchdog -> Op.KillWatchdog(op.token)
        is Op.PushAsset -> if (prior.present == false) Op.RemoveFile(op.dest, op.route) else null
        // RFC-HELPER-1: undoing the add takes out only our entry from the list the TV has then; a
        // removal is never undone (Undo never writes a non-empty list back), the timer is killed.
        is Op.AccessibilityAdd -> Op.AccessibilityRemove(null, op.component)
        is Op.ArmHelperWatchdog -> Op.KillWatchdog(op.token)
        is Op.AccessibilityRemove -> null
        is Op.Uninstall, is Op.RemoveFile, is Op.RestartHome, is Op.StartActivity, is Op.Mkdir,
        is Op.ConfirmHome, is Op.KillWatchdog, is Op.VerifyHome, is Op.Read, is Op.Settle,
        Op.PressHome, Op.Reboot, Op.DebugOff, is Op.CurtainCue -> null
    }

    override fun check(op: Op, verifyOutput: String, prior: Prior?): VerifyResult {
        val lines = verifyOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun mismatch(expected: String) = VerifyResult.Mismatch(expected, verifyOutput.take(200))
        return when (op) {
            is Op.Disable -> if (("package:" + op.pkg.value) in lines) VerifyResult.Ok else mismatch("package:${op.pkg.value} listed by pm list packages -d")
            is Op.Enable -> if (("package:" + op.pkg.value) !in lines) VerifyResult.Ok else mismatch("package:${op.pkg.value} not listed by pm list packages -d")
            is Op.DefaultState -> if (PackageDump.enabledSetting(verifyOutput) == 0) VerifyResult.Ok else mismatch("enabled=0 in dumpsys package ${op.pkg.value}")
            is Op.Install -> installed(op.pkg, op.versionCode, verifyOutput)
            is Op.InstallStream -> installed(op.pkg, op.versionCode, verifyOutput)
            // 0.5.0: a list without the install paths (pm list packages --user 0, the probe's last fallback)
            // names a package as "package:<name>", so both forms count as still installed.
            is Op.Uninstall -> if (lines.none { it == "package:" + op.pkg.value || (it.startsWith("package:") && it.endsWith("=" + op.pkg.value)) }) VerifyResult.Ok
                else mismatch("${op.pkg.value} absent from pm list packages -f")
            is Op.SettingsPut -> {
                val prefix = op.key.key + "="
                val found = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
                when {
                    op.value == null && found == null -> VerifyResult.Ok
                    // The verify lines are trimmed, so a value with trailing spaces (a typed device
                    // name, R1 R-12) is compared without them; leading spaces survive the trim.
                    op.value != null && found == op.value?.trimEnd() -> VerifyResult.Ok
                    else -> VerifyResult.Mismatch(prefix + (op.value ?: "<unset>"), found?.let { prefix + it } ?: "<unset>")
                }
            }
            is Op.SetHome -> homeIs(op.component, lines)
            is Op.VerifyHome -> homeIs(op.expected, lines)
            is Op.ArmHomeWatchdog -> if (lines.any { PID_RE.matches(it) }) VerifyResult.Ok else mismatch("a pid from pgrep -f ${processPattern(op.token)}")
            is Op.KillWatchdog -> if (lines.none { PID_RE.matches(it) }) VerifyResult.Ok else mismatch("no pid from pgrep -f ${processPattern(op.token)}")
            is Op.ConfirmHome -> if (lines.any { it.startsWith(EMPTY_FILE_SHA256) }) VerifyResult.Ok else mismatch("$EMPTY_FILE_SHA256 (empty token file)")
            // RFC-HELPER-1: the add holds when the list is exactly the one it wrote; a removal when
            // the entry is gone, whoever took it out (step R's state check, issue #2).
            is Op.AccessibilityAdd -> {
                val want = HelperLines.appended(op.expected, op.component)
                val found = settingOf(lines, HelperLines.LIST_KEY)
                if (found == want) VerifyResult.Ok else VerifyResult.Mismatch("${HelperLines.LIST_KEY}=$want", found?.let { "${HelperLines.LIST_KEY}=$it" } ?: "<unset>")
            }
            is Op.AccessibilityRemove -> {
                val found = settingOf(lines, HelperLines.LIST_KEY)
                if (!HelperLines.listed(found, op.component)) VerifyResult.Ok
                else VerifyResult.Mismatch("no ${op.component.flat} in ${HelperLines.LIST_KEY}", "${HelperLines.LIST_KEY}=$found")
            }
            is Op.ArmHelperWatchdog -> if (lines.any { PID_RE.matches(it) }) VerifyResult.Ok else mismatch("a pid from pgrep -f ${processPattern(op.token)}")
            is Op.PushAsset -> if (lines.any { it.substringBefore(' ') == op.sha256.hex }) VerifyResult.Ok else mismatch(op.sha256.hex)
            // RFC-THEME-1: through the provider a file that is gone reads as nothing, so its hash is the empty file's.
            is Op.RemoveFile -> if (lines.none { SHA256_RE.matches(it.substringBefore(' ')) &&
                    !(op.route == FileRoute.THEME_PROVIDER && it.substringBefore(' ') == EMPTY_FILE_SHA256) }) VerifyResult.Ok
                else mismatch("no hash (file removed)")
            // `am start -W` (launcher v2.4.0's precondition, build-and-install.sh): the activity came
            // up only when it says "Status: ok" and no line starts with Error; some builds answer
            // "Status: ok" for a task merely brought to the front, so both halves count.
            is Op.StartActivity -> if (!op.wait || ("Status: ok" in lines && lines.none { it.startsWith("Error") })) VerifyResult.Ok
                else mismatch("Status: ok and no Error from am start -W")
            is Op.Mkdir, is Op.RestartHome, is Op.Read, is Op.Settle,
            Op.PressHome, Op.Reboot, Op.DebugOff, is Op.CurtainCue -> VerifyResult.Ok
        }
    }

    private fun settingOf(lines: List<String>, key: String): String? = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    private fun installed(pkg: PackageName, versionCode: Long, dump: String): VerifyResult {
        val found = PackageDump.versionCode(dump)
        return if (PackageDump.exists(pkg, dump) && found == versionCode) VerifyResult.Ok
        else VerifyResult.Mismatch("Package [${pkg.value}] with versionCode=$versionCode", found?.let { "versionCode=$it" } ?: "not installed")
    }

    private fun homeIs(expected: Component, lines: List<String>): VerifyResult {
        val last = lines.lastOrNull()
        return if (last == expected.flat) VerifyResult.Ok else VerifyResult.Mismatch(expected.flat, last ?: "<no output>")
    }

    // R0: 5, PRODUCT-SPEC 5.2: every rendered line then "\n"; a transfer as one "sync:" line.
    override fun planHash(rendered: List<Rendered>): Sha256 {
        val sb = StringBuilder()
        for (r in rendered) {
            // RFC-THEME-1: a push runs before the op's lines (the provider's write reads the staged file), so it is
            // hashed in that order; until then no op had both, so no earlier plan's hash changes.
            (r.transfer as? Transfer.Push)?.let { t -> sb.append("sync:").append(t.dest.absolute).append(' ').append(t.src.sha256.hex).append('\n') }
            r.lines.forEach { sb.append(it.text).append('\n') }
            when (val t = r.transfer) {
                is Transfer.Push -> {}
                is Transfer.Pull -> sb.append("sync:").append(t.src.absolute).append(' ').append(t.maxBytes).append('\n')
                is Transfer.StreamInstall -> sb.append("sync:").append(t.src.path).append(' ').append(t.size).append('\n')
                null -> {}
            }
        }
        return sha256Of(sb.toString().encodeToByteArray())
    }

    // Argument re-validation (belt and braces: the value classes already did it).
    private fun pkgName(p: PackageName): PackageName {
        require(p.value.length <= 255 && (PACKAGE_RE.matches(p.value) || p.value == Denylist.FRAMEWORK)) { "package name fails PACKAGE_RE" }
        return p
    }
    private fun pkg(p: PackageName): String {
        pkgName(p)
        require(p.value != Denylist.FRAMEWORK) { "the framework package is never a target" }
        return p.value
    }
    // Disable, DefaultState, Uninstall and the installs: the static denylist is refused here as well
    // as in the planner and the executor.
    private fun writePkg(p: PackageName): String {
        val v = pkg(p)
        val reason = Denylist.staticReason(v)
        require(reason == null) { "protected package $v: $reason" }
        return v
    }
    private fun comp(c: Component): String {
        pkg(c.pkg)
        require(CLASS_RE.matches(c.cls) && c.flat.length <= 300) { "component class fails CLASS_RE" }
        return c.flat
    }
    private fun sha(s: Sha256) { require(SHA256_RE.matches(s.hex)) { "sha256 fails SHA256_RE" } }
    private fun token(t: RunToken): RunToken { require(RUN_TOKEN_RE.matches(t.value)) { "run token fails RUN_TOKEN_RE" }; return t }
    private fun processPattern(t: RunToken): String = Watchdog.processPattern(token(t))
    private fun tokenFile(t: RunToken): RemotePath = RemotePath(RemoteDir.TMP, listOf(Watchdog.tokenFileName(token(t))))

    private fun line(vararg argv: String): ShellLine = ShellLine(ShellTokens.line(argv.toList()))
    private fun lines(vararg l: ShellLine): List<ShellLine> = l.toList()

    companion object {
        private const val PM = "pm"
        private const val CMD = "cmd"
        private const val AM = "am"
        private const val SETTINGS = "settings"
        private const val DUMPSYS = "dumpsys"
        private const val USER = "--user"
        private val HOME_ARGV = arrayOf(CMD, "package", "resolve-activity", "--brief", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN")
        private val SCREEN_PNG = RemotePath(RemoteDir.TMP, listOf("screen.png"))
        private val PID_RE = Regex("^[0-9]{1,10}$")
        // RFC-CURTAIN-1: the curtain's package, its cue action and the extra flags of am.
        const val CURTAIN_PACKAGE = "tv.cleanyour.curtain"
        const val CUE_ACTION = "tv.cleanyour.curtain.CUE"
        const val CUE_MAX = 999
        private const val ES = "--es"
        private const val EI = "--ei"
        private const val RECEIVER_FOREGROUND = "--receiver-foreground"
        private val CUE_WORD_RE = Regex("^[A-Z_]{1,16}$")
        const val EMPTY_FILE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        // Timeouts (SECURITY 3.5, ARCHITECTURE 3.3) and output caps (SECURITY 3.5, ARCHITECTURE 3.7).
        const val T_DEFAULT = 30_000L
        const val T_PM = 30_000L
        const val T_DUMPSYS = 20_000L
        const val T_INSTALL = 120_000L
        const val T_START_WAIT = 180_000L
        const val T_PUSH = 600_000L
        const val T_REBOOT = 300_000L
        const val CAP_DEFAULT = 64 * 1024
        const val CAP_LIST = 256 * 1024
        const val CAP_DUMP = 1024 * 1024
        const val CAP_BIG = 4 * 1024 * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        // Issue #1273: a 4K TV's screencap PNG is 5 to 15 MB (a 1080p one at most about 1.5 MB).
        const val MAX_PNG_BYTES = 16L * 1024 * 1024

        fun sha256Of(bytes: ByteArray): Sha256 =
            Sha256(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
}

// The few facts the executor and the verify checks read from `dumpsys package PKG` (SECURITY 3.5:
// the live denylist check consults the dump of the package at hand). Tolerant of layout changes:
// a missing field reads as unknown, never as a pass.
object PackageDump {
    private val VERSION_CODE_RE = Regex("\\bversionCode=([0-9]{1,19})\\b")
    private val ENABLED_RE = Regex("\\benabled=([0-9])\\b")
    private val FLAGS_RE = Regex("\\b(?:pkgFlags|flags)=\\[([^\\]]*)\\]")

    fun exists(pkg: PackageName, dump: String): Boolean = dump.contains("Package [${pkg.value}]")
    fun versionCode(dump: String): Long? = VERSION_CODE_RE.find(dump)?.groupValues?.get(1)?.toLongOrNull()
    // The per-user enabled setting (0 default, 1 enabled, 2 disabled, 3 disabled-user, 4 disabled-until-used).
    fun enabledSetting(dump: String): Int? = ENABLED_RE.find(dump)?.groupValues?.get(1)?.toIntOrNull()
    fun isPersistent(dump: String): Boolean = FLAGS_RE.findAll(dump).any { m -> m.groupValues[1].split(' ').any { it == "PERSISTENT" } }
    fun isSystem(dump: String): Boolean = FLAGS_RE.findAll(dump).any { m -> m.groupValues[1].split(' ').any { it == "SYSTEM" } }

    // Upstream #421 (tools/disable.sh, v2.5.0): the package itself asks for TV_INPUT_HARDWARE in a
    // "requested permissions:" block, which runs until "install permissions:", "runtime permissions:",
    // a "User N:" line or the next package. A shared user's permissions are printed elsewhere, so a
    // vendor app that only shares a UID with an input driver does not count.
    fun requestsTvInputHardware(dump: String): Boolean {
        var inRequested = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            when {
                line == "requested permissions:" -> inRequested = true
                line.endsWith("permissions:") || USER_RE.matches(line) || line.startsWith("Package [") -> inRequested = false
                inRequested && (line == TV_INPUT_HARDWARE || line.startsWith("$TV_INPUT_HARDWARE:")) -> return true
            }
        }
        return false
    }
    private const val TV_INPUT_HARDWARE = "android.permission.TV_INPUT_HARDWARE"
    private val USER_RE = Regex("^User [0-9]+:.*")

    fun enabledState(dump: String): EnabledState? = when (enabledSetting(dump)) {
        0 -> EnabledState.DEFAULT
        1 -> EnabledState.ENABLED
        2, 4 -> EnabledState.DISABLED
        3 -> EnabledState.DISABLED_USER
        else -> null
    }
}
