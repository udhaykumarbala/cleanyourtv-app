package tv.cleanyour.testing.fakeadbd

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class FakePackage(
    val name: String,
    val path: String,
    var state: String = "enabled",      // enabled, disabled-user, disabled, default
    val versionCode: Long = 1,
    val signer: String? = null,          // sha256 hex of the signing certificate, when known
    // Updated system apps also live under /data/app, so a capture says which are third party.
    val system: Boolean = !path.startsWith("/data/app"),
) {
    fun copy(): FakePackage = FakePackage(name, path, state, versionCode, signer, system)
}

// The device model behind the fake adbd. The shell interpreter changes it, so what `pm list
// packages -d` prints after `pm disable-user` is real (ARCHITECTURE 6.4).
class FakeTv(
    val name: String,
    propLines: List<String>,
    packages: List<FakePackage>,
    settings: Map<String, List<Pair<String, String>>>,
    home: String,
    val features: Set<String>,
    val maxPayload: Int = 1024 * 1024,
    val synthetic: Boolean = false,
) {
    private val lock = Any()

    // getprop lines in their original order: parsed ones as key to value, the rest verbatim.
    private val propOrder = ArrayList<Any>()   // String key or RawLine
    private class RawLine(val text: String)
    val props = LinkedHashMap<String, String>()

    val packages = LinkedHashMap<String, FakePackage>()
    val settings = LinkedHashMap<String, LinkedHashMap<String, String>>()
    @Volatile var home: String = home
    val files = ConcurrentHashMap<String, ByteArray>()
    val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        add("/"); add("/data"); add("/data/local"); add("/data/local/tmp"); add("/sdcard"); add("/sdcard/Android"); add("/sdcard/Android/data")
    }

    // What an install of these bytes installs (keyed by sha256 hex of the APK); anything else fails.
    val apkCatalog = ConcurrentHashMap<String, FakePackage>()
    @Volatile var installFailure: String? = null     // e.g. INSTALL_FAILED_INSUFFICIENT_STORAGE, overrides the catalog
    // RFC-INSTALL-1: packages on the TV but not installed for user 0, kept by a second user (10). `pm
    // list packages` and `pm path` (user 0) leave them out, `am start` cannot find them, and the dump
    // says installed=false for user 0 beside user 10's installed=true.
    val notForUser0: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Observations for tests.
    val armedScripts = CopyOnWriteArrayList<String>()
    val startedActivities = CopyOnWriteArrayList<String>()
    val forceStopped = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()
    val homeKeyPresses = CopyOnWriteArrayList<Long>()
    @Volatile var rebootCount = 0
    @Volatile var bootId: String = "0f1e2d3c-0000-4000-8000-000000000001"
    @Volatile var bootCompleted = true
    @Volatile var pmReady = true

    // Tried before the built-in vocabulary; return null to fall through.
    val handlers = CopyOnWriteArrayList<(argv: List<String>, line: String) -> ExecOutcome?>()

    init {
        for (line in propLines) {
            val m = PROP_LINE.matchEntire(line)
            if (m != null) { props[m.groupValues[1]] = m.groupValues[2]; propOrder += m.groupValues[1] } else propOrder += RawLine(line)
        }
        for (p in packages) this.packages[p.name] = p
        for ((ns, kvs) in settings) this.settings[ns] = LinkedHashMap<String, String>().apply { for ((k, v) in kvs) put(k, v) }
        for (ns in listOf("global", "secure", "system")) this.settings.putIfAbsent(ns, LinkedHashMap())
    }

    val serial: String get() = props["ro.serialno"] ?: ""
    val device: String get() = props["ro.product.device"] ?: ""
    val model: String get() = props["ro.product.model"] ?: ""
    val sdk: Int get() = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0
    val adbSecure: Boolean get() = props["ro.adb.secure"] == "1"

    // D37: first 16 hex of sha256(ro.serialno + ro.product.device).
    fun tvIdHex(): String = sha256Hex((serial + device).encodeToByteArray()).take(16)

    fun getpropText(): String = synchronized(lock) {
        propOrder.joinToString("\n") { if (it is RawLine) it.text else "[$it]: [${props[it]}]" } + "\n"
    }

    fun setProp(key: String, value: String) = synchronized(lock) {
        if (key !in props) propOrder += key
        props[key] = value
    }

    fun banner(): String {
        val productName = props["ro.product.name"] ?: device
        return "device::ro.product.name=$productName;ro.product.model=$model;ro.product.device=$device;features=${features.joinToString(",")}"
    }

    fun homeActivityText(): String = "priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n$home\n"

    fun settingsText(ns: String): String = synchronized(lock) {
        settings[ns]?.entries?.joinToString("") { "${it.key}=${it.value}\n" } ?: ""
    }

    fun listPackages(flags: Set<String>): String = synchronized(lock) {
        val sb = StringBuilder()
        for (p in packages.values) {
            if (p.name in notForUser0) continue
            if ("-3" in flags && p.system) continue
            if ("-s" in flags && !p.system) continue
            if ("-d" in flags && p.state == "enabled") continue
            if ("-e" in flags && p.state != "enabled") continue
            sb.append("package:")
            if ("-f" in flags) sb.append(p.path).append('=')
            sb.append(p.name).append('\n')
        }
        sb.toString()
    }

    // The result line `pm install` or `cmd package install` prints for these APK bytes. `forUser0`:
    // the install named `--user 0`. Without a user it is "for all users", and Android 11 and 12 (SDK
    // 32 and below) then keep a package that is on the TV but not installed for user 0 that way
    // (PackageManagerService.updateSettingsInternalLI); Android 13 installs it for every user.
    fun installApk(bytes: ByteArray, forUser0: Boolean = false): String {
        installFailure?.let { return "Failure [$it]" }
        val entry = apkCatalog[sha256Hex(bytes)] ?: return "Failure [INSTALL_PARSE_FAILED_NOT_APK: not an APK the fake knows]"
        synchronized(lock) {
            packages[entry.name] = entry.copy()
            if (forUser0 || sdk >= 33) notForUser0.remove(entry.name)
        }
        return "Success"
    }

    fun mkdir(path: String) {
        var p = ""
        for (seg in path.trim('/').split('/')) { p += "/$seg"; dirs += p }
    }

    fun dirExists(path: String): Boolean = path.trimEnd('/').ifEmpty { "/" } in dirs

    fun parentDir(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    companion object {
        val PROP_LINE = Regex("^\\[([^\\]]+)\\]: \\[(.*)\\]$")
    }
}

object Personas {
    val MI_TV_FEATURES = setOf("shell_v2", "cmd", "stat_v2", "ls_v2", "fixed_push_mkdir", "fixed_push_symlink_timestamp")
    val ANDROID_11_FEATURES = MI_TV_FEATURES + setOf("apex", "abb", "abb_exec", "remount_shell", "track_app", "sendrecv_v2", "sendrecv_v2_brotli", "sendrecv_v2_lz4", "sendrecv_v2_zstd", "sendrecv_v2_dry_run_send")

    // The reference TV from the redacted capture. debloated = false gives the TV as it came out of
    // the box (every package enabled); true gives the state the capture recorded after the hand
    // debloat (packages-disabled-after.txt).
    fun miTv(debloated: Boolean = false): FakeTv = fromCaptureDir(captureDir("mitv-axso0-2026-10-18"), "Mi TV 4A", debloated)

    fun captureDir(name: String): File {
        val root = System.getProperty("cleanyourtv.repoRoot") ?: error("cleanyourtv.repoRoot is not set; the build sets it for every test")
        return File(root, "fixtures/conformance/$name").also { require(it.isDirectory) { "no capture at $it" } }
    }

    fun fromCaptureDir(dir: File, name: String, debloated: Boolean): FakeTv {
        fun read(f: String) = File(dir, f).readText()
        val thirdParty = read("packages-3rdparty.txt").lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet()
        val disabled = if (debloated) read("packages-disabled-after.txt").lines().filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toSet() else emptySet()
        val packages = read("packages-all.txt").lines().filter { it.startsWith("package:") }.map { line ->
            val body = line.removePrefix("package:")
            val path = body.substringBeforeLast('=')
            val pkg = body.substringAfterLast('=')
            FakePackage(pkg, path, if (pkg in disabled) "disabled-user" else "enabled", system = pkg !in thirdParty)
        }
        val settings = listOf("global", "secure", "system").associateWith { ns -> parseSettings(read("settings-$ns.txt")) }
        val home = read("home-activity.txt").lines().firstOrNull { '/' in it && !it.contains('=') }?.trim() ?: "com.google.android.tvlauncher/.MainActivity"
        val propLines = read("getprop.txt").lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        return FakeTv(name, propLines, packages, settings, home, MI_TV_FEATURES)
    }

    // `settings list` output: a line that starts with a key and '=' begins an entry; any other line
    // continues the previous value (JSON values span lines on the Mi TV).
    fun parseSettings(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val lines = text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        for (line in lines) {
            if (SETTING_START.containsMatchIn(line) || out.isEmpty()) out += line.substringBefore('=') to line.substringAfter('=', "")
            else out[out.size - 1] = out.last().let { it.first to it.second + "\n" + line }
        }
        return out
    }

    private val SETTING_START = Regex("^[A-Za-z0-9_.:-]+=")

    private fun props(vararg kv: Pair<String, String>): List<String> = kv.map { "[${it.first}]: [${it.second}]" }

    // Synthetic Android 11 TCL set (no capture exists; marked synthetic). Pairing-only wireless
    // debugging in reality, so a plain 5555 connect is what the Allow dialog tests exercise.
    fun tclG05(): FakeTv = FakeTv(
        name = "TCL G05 (synthetic)",
        propLines = props(
            "ro.adb.secure" to "1", "ro.serialno" to "FIXTURESERIAL0002", "ro.boot.serialno" to "FIXTURESERIAL0002",
            "ro.product.brand" to "TCL", "ro.product.manufacturer" to "TCL", "ro.product.model" to "Smart TV",
            "ro.product.device" to "G05", "ro.product.name" to "G05", "ro.build.version.sdk" to "30",
            "ro.build.version.release" to "11", "ro.build.fingerprint" to "TCL/G05/G05:11/RP1A/1:user/release-keys",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi", "ro.product.locale" to "en-US",
            "sys.boot_completed" to "1",
        ),
        packages = listOf(
            FakePackage("android", "/system/framework/framework-res.apk"),
            FakePackage("com.google.android.tvlauncher", "/system/priv-app/TVLauncher/TVLauncher.apk"),
            FakePackage("com.google.android.tvrecommendations", "/system/priv-app/TVRecommendations/TVRecommendations.apk"),
            FakePackage("com.google.android.gms", "/system/priv-app/PrebuiltGmsCore/PrebuiltGmsCore.apk"),
            FakePackage("com.android.vending", "/system/priv-app/Phonesky/Phonesky.apk"),
            FakePackage("com.tcl.tv", "/system/priv-app/TclTv/TclTv.apk"),
            FakePackage("com.tcl.browser", "/system/app/TclBrowser/TclBrowser.apk"),
            FakePackage("com.netflix.ninja", "/system/app/Netflix/Netflix.apk"),
            FakePackage("com.google.android.youtube.tv", "/system/app/YouTubeTV/YouTubeTV.apk"),
            FakePackage("com.android.settings", "/system/priv-app/Settings/Settings.apk"),
        ),
        settings = mapOf(
            "global" to listOf("adb_enabled" to "1", "device_name" to "TCL Smart TV"),
            "secure" to listOf("screensaver_enabled" to "1", "accessibility_enabled" to "0"),
            "system" to listOf("screen_off_timeout" to "600000"),
        ),
        home = "com.google.android.tvlauncher/.MainActivity",
        features = ANDROID_11_FEATURES,
        synthetic = true,
    )

    // Synthetic Google TV Chromecast (Android 12); marked synthetic.
    fun chromecast(): FakeTv = FakeTv(
        name = "Chromecast with Google TV (synthetic)",
        propLines = props(
            "ro.adb.secure" to "1", "ro.serialno" to "FIXTURESERIAL0003", "ro.boot.serialno" to "FIXTURESERIAL0003",
            "ro.product.brand" to "google", "ro.product.manufacturer" to "Google", "ro.product.model" to "Chromecast",
            "ro.product.device" to "sabrina", "ro.product.name" to "sabrina", "ro.build.version.sdk" to "31",
            "ro.build.version.release" to "12", "ro.build.fingerprint" to "google/sabrina/sabrina:12/STTL/1:user/release-keys",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi", "ro.product.locale" to "en-US",
            "sys.boot_completed" to "1",
        ),
        packages = listOf(
            FakePackage("android", "/system/framework/framework-res.apk"),
            FakePackage("com.google.android.apps.tv.launcherx", "/system/priv-app/LauncherX/LauncherX.apk"),
            FakePackage("com.google.android.gms", "/system/priv-app/PrebuiltGmsCore/PrebuiltGmsCore.apk"),
            FakePackage("com.android.vending", "/system/priv-app/Phonesky/Phonesky.apk"),
            FakePackage("com.google.android.apps.tv.dreamx", "/system/priv-app/DreamX/DreamX.apk"),
            FakePackage("com.netflix.ninja", "/system/app/Netflix/Netflix.apk"),
            FakePackage("com.google.android.youtube.tv", "/system/app/YouTubeTV/YouTubeTV.apk"),
            FakePackage("com.android.tv.settings", "/system/priv-app/TvSettings/TvSettings.apk"),
        ),
        settings = mapOf(
            "global" to listOf("adb_enabled" to "1", "adb_wifi_enabled" to "1", "device_name" to "Chromecast"),
            "secure" to listOf("screensaver_enabled" to "1", "accessibility_enabled" to "0"),
            "system" to listOf("screen_off_timeout" to "600000"),
        ),
        home = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
        features = ANDROID_11_FEATURES,
        synthetic = true,
    )
}
