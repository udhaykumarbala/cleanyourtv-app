package tv.cleanyour.testing.fakeadbd

import kotlinx.coroutines.delay

class ExecOutcome(
    val stdout: ByteArray,
    val stderr: ByteArray = EMPTY,
    val exit: Int = 0,
    val effect: Effect? = null,
) {
    constructor(stdout: String, exit: Int = 0, stderr: String = "", effect: Effect? = null) :
        this(stdout.encodeToByteArray(), stderr.encodeToByteArray(), exit, effect)

    companion object { val EMPTY = ByteArray(0) }
}

// What a command did to adbd itself.
sealed interface Effect {
    data object Reboot : Effect
    data object AdbOff : Effect
    data object Hang : Effect
}

// A small POSIX-ish tokenizer: single quotes, double quotes, backslashes, the separators `;`,
// newline, `&&`, `||`, `&`, and redirections (dropped). Pipes and command substitution are not
// supported, which keeps the fake honest about what the renderer may emit.
object ShellParser {
    class Command(val argv: List<String>, val joiner: String?, val background: Boolean)
    class Parsed(val commands: List<Command>, val chained: Boolean, val unsupported: String?)

    fun parse(line: String): Parsed {
        val commands = ArrayList<Command>()
        var argv = ArrayList<String>()
        var cur = StringBuilder()
        var inToken = false
        var joiner: String? = null
        var unsupported: String? = null
        var i = 0
        val n = line.length

        fun endToken() {
            if (inToken) { argv += cur.toString(); cur = StringBuilder(); inToken = false }
        }
        fun endCommand(nextJoiner: String?, background: Boolean = false) {
            endToken()
            val cleaned = stripRedirections(argv)
            if (cleaned.isNotEmpty()) commands += Command(cleaned, joiner, background)
            argv = ArrayList()
            joiner = nextJoiner
        }

        while (i < n) {
            val c = line[i]
            when {
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1)
                    if (end < 0) { unsupported = "unterminated single quote"; break }
                    cur.append(line, i + 1, end); inToken = true; i = end + 1
                }
                c == '"' -> {
                    i++
                    inToken = true
                    while (i < n && line[i] != '"') {
                        if (line[i] == '\\' && i + 1 < n && line[i + 1] in "\"\\$`") { cur.append(line[i + 1]); i += 2 } else { cur.append(line[i]); i++ }
                    }
                    if (i >= n) { unsupported = "unterminated double quote"; break }
                    i++
                }
                c == '\\' && i + 1 < n -> { cur.append(line[i + 1]); inToken = true; i += 2 }
                c == ';' || c == '\n' -> { endCommand(";"); i++ }
                // 2>&1 and >&2: the ampersand belongs to the redirection token.
                c == '&' && inToken && (cur.endsWith(">") || cur.endsWith("<")) -> { cur.append(c); i++ }
                c == '&' && i + 1 < n && line[i + 1] == '&' -> { endCommand("&&"); i += 2 }
                c == '|' && i + 1 < n && line[i + 1] == '|' -> { endCommand("||"); i += 2 }
                c == '&' -> { endCommand(";", background = true); i++ }
                c == '|' -> { unsupported = "pipes are not supported"; break }
                c == '`' -> { unsupported = "command substitution is not supported"; break }
                c == '$' && i + 1 < n && line[i + 1] == '(' -> { unsupported = "command substitution is not supported"; break }
                c == '#' && !inToken -> break
                c == ' ' || c == '\t' -> { endToken(); i++ }
                else -> { cur.append(c); inToken = true; i++ }
            }
        }
        if (unsupported == null) endCommand(null)
        return Parsed(commands, commands.size > 1 || commands.any { it.background }, unsupported)
    }

    private fun stripRedirections(argv: List<String>): List<String> {
        val out = ArrayList<String>()
        var skipNext = false
        for (a in argv) {
            if (skipNext) { skipNext = false; continue }
            when {
                REDIRECT_WITH_TARGET.matches(a) -> continue
                REDIRECT_BARE.matches(a) -> skipNext = true
                else -> out += a
            }
        }
        return out
    }

    private val REDIRECT_WITH_TARGET = Regex("^[0-9]?[<>]{1,2}&?\\S+$")
    private val REDIRECT_BARE = Regex("^[0-9]?[<>]{1,2}$")
}

// The command vocabulary the renderer and the probe use, interpreted against a FakeTv so state
// changes are real. `pm` writes are never accepted inside a chained line (SECURITY 3.5).
class FakeShell(private val tv: FakeTv, private val faults: Faults) {

    suspend fun run(line: String): ExecOutcome {
        val parsed = ShellParser.parse(line)
        parsed.unsupported?.let { return ExecOutcome("", 2, "/system/bin/sh: syntax error: $it\n") }
        if (parsed.chained && parsed.commands.any { isPmWrite(it.argv) }) {
            tv.violations += line
            return ExecOutcome("", 126, "fake-adbd: a pm write inside a chained line is refused\n")
        }
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        var exit = 0
        for (cmd in parsed.commands) {
            when (cmd.joiner) {
                "&&" -> if (exit != 0) continue
                "||" -> if (exit == 0) continue
            }
            val r = runOne(cmd.argv, line)
            stdout.write(r.stdout)
            stderr.write(r.stderr)
            exit = r.exit
            if (r.effect != null) return ExecOutcome(stdout.toByteArray(), stderr.toByteArray(), exit, r.effect)
        }
        return ExecOutcome(stdout.toByteArray(), stderr.toByteArray(), exit)
    }

    private fun isPmWrite(argv: List<String>): Boolean {
        if (argv.isEmpty()) return false
        val verbIndex = when {
            argv[0] == "pm" -> 1
            argv[0] == "cmd" && argv.getOrNull(1) == "package" -> 2
            else -> return false
        }
        return argv.getOrNull(verbIndex) in PM_WRITE_VERBS
    }

    private suspend fun runOne(argv0: List<String>, line: String): ExecOutcome {
        for (h in tv.handlers) h(argv0, line)?.let { return it }
        var argv = argv0
        // "( cmd & )", the watchdog's subshell variant: the parentheses only group.
        while (argv.isNotEmpty() && argv[0] in listOf("nohup", "setsid", "exec", "(")) argv = argv.drop(1)
        if (argv.lastOrNull() == ")") argv = argv.dropLast(1)
        if (argv.isEmpty()) return ExecOutcome("")
        return when (argv[0]) {
            "getprop" -> getprop(argv)
            "pm" -> pm(argv.drop(1))
            "cmd" -> cmd(argv.drop(1))
            "settings" -> settings(argv.drop(1))
            "am" -> am(argv.drop(1))
            "input" -> input(argv.drop(1))
            "dumpsys" -> dumpsys(argv.drop(1))
            "echo" -> ExecOutcome(argv.drop(1).joinToString(" ") + "\n")
            "cat" -> cat(argv.drop(1))
            "sha256sum" -> sha256sum(argv.drop(1))
            "mkdir" -> mkdir(argv.drop(1))
            "rm" -> rm(argv.drop(1))
            "ls" -> ls(argv.drop(1))
            "sleep" -> { delay((argv.getOrNull(1)?.toDoubleOrNull() ?: 0.0).times(1000).toLong()); ExecOutcome("") }
            "reboot" -> { tv.rebootCount++; ExecOutcome("", effect = Effect.Reboot) }
            "sh" -> sh(argv.drop(1))
            "pkill", "pgrep" -> ExecOutcome("", 1)
            "true", ":" -> ExecOutcome("")
            "false" -> ExecOutcome("", 1)
            "id" -> ExecOutcome("uid=2000(shell) gid=2000(shell) groups=2000(shell)\n")
            "whoami" -> ExecOutcome("shell\n")
            "df" -> ExecOutcome("Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-0   5160576 3011040   2149536  59% /data\n")
            "screencap" -> screencap(argv.drop(1))
            "test", "[" -> test(argv.drop(1))
            "uptime" -> ExecOutcome(" 10:00:00 up 1 day,  2:03,  0 users,  load average: 0.5, 0.4, 0.3\n")
            else -> ExecOutcome("", 127, "/system/bin/sh: ${argv[0]}: inaccessible or not found\n")
        }
    }

    private fun getprop(argv: List<String>): ExecOutcome =
        if (argv.size == 1) ExecOutcome(tv.getpropText()) else ExecOutcome((tv.props[argv[1]] ?: "") + "\n")

    private suspend fun pm(args: List<String>): ExecOutcome {
        if (faults.pmDelayMs > 0) delay(faults.pmDelayMs)
        if (faults.pmBusy) return ExecOutcome("", 1, "Error: Package manager is busy\n")
        if (!tv.pmReady) return ExecOutcome("", 1, "cmd: Can't find service: package\n")
        val verb = args.firstOrNull() ?: return ExecOutcome("", 1, "Error: no verb\n")
        val rest = args.drop(1)
        val flags = rest.filter { it.startsWith("-") }.toSet()
        val positional = rest.filterIndexed { i, a -> !a.startsWith("-") && (i == 0 || rest[i - 1] != "--user") }
        return when (verb) {
            "list" -> if (rest.firstOrNull() == "packages") ExecOutcome(tv.listPackages(flags)) else ExecOutcome("", 1, "Error: unknown list\n")
            "disable-user", "disable", "enable", "default-state" -> {
                val pkg = positional.lastOrNull() ?: return ExecOutcome("", 1, "Error: no package specified\n")
                val p = synchronized(tv) { tv.packages[pkg] } ?: return ExecOutcome("", 1, "Error: java.lang.IllegalArgumentException: Unknown package: $pkg\n")
                p.state = when (verb) { "disable-user" -> "disabled-user"; "disable" -> "disabled"; "enable" -> "enabled"; else -> "default" }
                ExecOutcome("Package $pkg new state: ${p.state}\n")
            }
            "path" -> {
                val pkg = positional.lastOrNull() ?: return ExecOutcome("", 1)
                tv.packages[pkg]?.takeIf { pkg !in tv.notForUser0 }?.let { ExecOutcome("package:${it.path}\n") } ?: ExecOutcome("", 1)
            }
            "install" -> {
                val path = positional.lastOrNull() ?: return ExecOutcome("", 1, "Error: must specify an APK\n")
                val bytes = tv.files[path] ?: return ExecOutcome("", 1, "Failure [INSTALL_FAILED_INVALID_URI: $path does not exist]\n")
                val result = tv.installApk(bytes, forUser0 = rest.getOrNull(rest.indexOf("--user") + 1) == "0" && "--user" in rest)
                ExecOutcome(result + "\n", if (result.startsWith("Success")) 0 else 1)
            }
            "uninstall" -> {
                val pkg = positional.lastOrNull() ?: return ExecOutcome("", 1)
                val p = tv.packages[pkg] ?: return ExecOutcome("Failure [DELETE_FAILED_INTERNAL_ERROR]\n", 1)
                if (p.system) return ExecOutcome("Failure [DELETE_FAILED_INTERNAL_ERROR]\n", 1)
                synchronized(tv) { tv.packages.remove(pkg) }
                ExecOutcome("Success\n")
            }
            "dump" -> dumpsysPackage(positional.lastOrNull())
            else -> ExecOutcome("", 1, "Error: unknown command '$verb'\n")
        }
    }

    private suspend fun cmd(args: List<String>): ExecOutcome {
        val service = args.firstOrNull() ?: return ExecOutcome("", 1)
        val rest = args.drop(1)
        return when (service) {
            "package" -> when (rest.firstOrNull()) {
                "resolve-activity" -> if ("android.intent.category.HOME" in rest) ExecOutcome(tv.homeActivityText()) else ExecOutcome("No activity found\n")
                "set-home-activity" -> {
                    val component = rest.lastOrNull { !it.startsWith("-") && it != rest.getOrNull(rest.indexOf("--user") + 1) } ?: return ExecOutcome("", 1)
                    val pkg = component.substringBefore('/')
                    val p = tv.packages[pkg] ?: return ExecOutcome("Error: Unknown package: $pkg\n", 1)
                    if (p.state != "enabled" && p.state != "default") return ExecOutcome("Error: Package $pkg is disabled\n", 1)
                    tv.home = component
                    ExecOutcome("Success\n")
                }
                else -> pm(rest)
            }
            else -> ExecOutcome("", 1, "cmd: Can't find service: $service\n")
        }
    }

    private fun settings(args: List<String>): ExecOutcome {
        val verb = args.firstOrNull() ?: return ExecOutcome("", 1)
        val rest = args.drop(1).filterIndexed { i, a -> !(a == "--user" || (i > 0 && args.drop(1)[i - 1] == "--user")) }
        val ns = rest.getOrNull(0) ?: return ExecOutcome("", 1)
        val table = tv.settings[ns] ?: return ExecOutcome("", 1, "Invalid namespace '$ns'\n")
        return when (verb) {
            "list" -> ExecOutcome(tv.settingsText(ns))
            "get" -> ExecOutcome((synchronized(tv) { table[rest.getOrNull(1)] } ?: "null") + "\n")
            "put" -> {
                val key = rest.getOrNull(1) ?: return ExecOutcome("", 1)
                val value = rest.getOrNull(2) ?: return ExecOutcome("", 1)
                synchronized(tv) { table[key] = value }
                if (ns == "global" && key == "adb_enabled" && value == "0") ExecOutcome("", effect = Effect.AdbOff) else ExecOutcome("")
            }
            "delete" -> {
                val removed = synchronized(tv) { table.remove(rest.getOrNull(1)) }
                ExecOutcome("Deleted ${if (removed != null) 1 else 0} rows\n")
            }
            else -> ExecOutcome("", 1)
        }
    }

    private fun am(args: List<String>): ExecOutcome = when (args.firstOrNull()) {
        "force-stop" -> { args.lastOrNull()?.let { tv.forceStopped += it }; ExecOutcome("") }
        "start" -> {
            val component = args.getOrNull(args.indexOf("-n") + 1)?.takeIf { args.indexOf("-n") >= 0 }
            val wait = "-W" in args
            if (component == null) {
                if ("android.intent.category.HOME" in args) { tv.startedActivities += tv.home; ExecOutcome("Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }\n" + if (wait) "Status: ok\n" else "") }
                else ExecOutcome("Error: no component\n", 255)
            } else {
                val pkg = component.substringBefore('/')
                val p = tv.packages[pkg]
                if (p == null || pkg in tv.notForUser0 || p.state == "disabled-user" || p.state == "disabled") ExecOutcome("Error type 3\nError: Activity class {$component} does not exist.\n", 255)
                else {
                    tv.startedActivities += component
                    if (pkg == "com.gathin.tvhome") tv.mkdir("/sdcard/Android/data/com.gathin.tvhome/files")
                    ExecOutcome("Starting: Intent { cmp=$component }\n" + if (wait) "Status: ok\nLaunchState: COLD\nActivity: $component\nTotalTime: 420\nWaitTime: 430\nComplete\n" else "")
                }
            }
        }
        "broadcast" -> ExecOutcome("Broadcasting: Intent { }\nBroadcast completed: result=0\n")
        else -> ExecOutcome("", 1, "Error: unknown am command\n")
    }

    private fun input(args: List<String>): ExecOutcome {
        if (args.firstOrNull() == "keyevent" && (args.getOrNull(1) == "KEYCODE_HOME" || args.getOrNull(1) == "3")) {
            tv.homeKeyPresses += System.nanoTime()
            tv.startedActivities += tv.home
        }
        return ExecOutcome("")
    }

    private fun dumpsys(args: List<String>): ExecOutcome = when (args.firstOrNull()) {
        "package" -> dumpsysPackage(args.getOrNull(1))
        null -> ExecOutcome("", 1)
        else -> ExecOutcome("fake-adbd: no dump for ${args[0]}\n")
    }

    private fun dumpsysPackage(pkg: String?): ExecOutcome {
        val p = pkg?.let { tv.packages[it] } ?: return ExecOutcome("Unable to find package: $pkg\n", 1)
        val flags = if (p.system) "[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]" else "[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]"
        val enabled = when (p.state) { "enabled" -> 1; "disabled" -> 2; "disabled-user" -> 3; else -> 0 }
        return ExecOutcome(
            "Packages:\n  Package [${p.name}] (fake):\n    userId=10042\n    pkg=Package{fake ${p.name}}\n    codePath=${p.path.substringBeforeLast('/')}\n" +
                "    versionCode=${p.versionCode} minSdk=${tv.sdk} targetSdk=${tv.sdk}\n    versionName=1.0\n    flags=$flags\n" +
                (p.signer?.let { "    signatures=PackageSignatures{fake version:3, signatures:[$it]}\n" } ?: "") +
                "    User 0: ceDataInode=0 installed=${p.name !in tv.notForUser0} hidden=false suspended=false stopped=false notLaunched=false enabled=$enabled instant=false virtual=false\n" +
                (if (p.name in tv.notForUser0) "    User 10: ceDataInode=0 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=$enabled instant=false virtual=false\n" else ""),
        )
    }

    private fun cat(args: List<String>): ExecOutcome {
        val path = args.lastOrNull() ?: return ExecOutcome("", 1)
        if (path == "/proc/sys/kernel/random/boot_id") return ExecOutcome(tv.bootId + "\n")
        val bytes = tv.files[path] ?: return ExecOutcome("", 1, "cat: $path: No such file or directory\n")
        return ExecOutcome(bytes)
    }

    private fun sha256sum(args: List<String>): ExecOutcome {
        val path = args.lastOrNull() ?: return ExecOutcome("", 1)
        val bytes = tv.files[path] ?: return ExecOutcome("", 1, "sha256sum: $path: No such file or directory\n")
        return ExecOutcome(sha256Hex(bytes) + "  " + path + "\n")
    }

    private fun mkdir(args: List<String>): ExecOutcome {
        val path = args.lastOrNull { !it.startsWith("-") } ?: return ExecOutcome("", 1)
        if ("-p" !in args && !tv.dirExists(tv.parentDir(path))) return ExecOutcome("", 1, "mkdir: '$path': No such file or directory\n")
        tv.mkdir(path)
        return ExecOutcome("")
    }

    private fun rm(args: List<String>): ExecOutcome {
        val paths = args.filter { !it.startsWith("-") }
        val force = args.any { it.startsWith("-") && 'f' in it }
        val recursive = args.any { it.startsWith("-") && 'r' in it }
        var exit = 0
        val err = StringBuilder()
        for (p in paths) {
            val removed = tv.files.remove(p) != null
            val dirRemoved = if (recursive) { val hit = tv.dirs.filter { it == p || it.startsWith("$p/") }; tv.dirs.removeAll(hit.toSet()); tv.files.keys.filter { it.startsWith("$p/") }.forEach { tv.files.remove(it) }; hit.isNotEmpty() } else false
            if (!removed && !dirRemoved && !force) { exit = 1; err.append("rm: $p: No such file or directory\n") }
        }
        return ExecOutcome("", exit, err.toString())
    }

    private fun ls(args: List<String>): ExecOutcome {
        val path = args.lastOrNull { !it.startsWith("-") } ?: "/"
        if (tv.files.containsKey(path)) return ExecOutcome(path + "\n")
        if (!tv.dirExists(path)) return ExecOutcome("", 1, "ls: $path: No such file or directory\n")
        val prefix = path.trimEnd('/') + "/"
        val names = (tv.files.keys + tv.dirs).filter { it.startsWith(prefix) && it != prefix }.map { it.removePrefix(prefix).substringBefore('/') }.toSortedSet()
        return ExecOutcome(names.joinToString("") { it + "\n" })
    }

    private fun sh(args: List<String>): ExecOutcome {
        val script = args.getOrNull(args.indexOf("-c") + 1)?.takeIf { args.indexOf("-c") >= 0 } ?: return ExecOutcome("", 2)
        tv.armedScripts += script
        return ExecOutcome("")
    }

    private fun screencap(args: List<String>): ExecOutcome {
        val path = args.lastOrNull { !it.startsWith("-") } ?: return ExecOutcome("", 1)
        if (!tv.dirExists(tv.parentDir(path))) return ExecOutcome("", 1, "Error opening file: $path\n")
        tv.files[path] = PNG_HEADER + tv.home.encodeToByteArray()
        return ExecOutcome("")
    }

    private fun test(args: List<String>): ExecOutcome {
        val a = args.filter { it != "]" }
        val ok = when (a.getOrNull(0)) {
            "-f" -> tv.files.containsKey(a.getOrNull(1))
            "-d" -> a.getOrNull(1)?.let { tv.dirExists(it) } ?: false
            "-e" -> tv.files.containsKey(a.getOrNull(1)) || (a.getOrNull(1)?.let { tv.dirExists(it) } ?: false)
            else -> false
        }
        return ExecOutcome("", if (ok) 0 else 1)
    }

    companion object {
        val PM_WRITE_VERBS = setOf("disable-user", "disable", "enable", "default-state", "install", "install-create", "install-write",
            "install-commit", "install-abandon", "uninstall", "clear", "grant", "revoke", "set-home-activity", "hide", "unhide", "suspend", "unsuspend")
        val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
