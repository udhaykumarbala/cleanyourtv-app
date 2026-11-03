package tv.cleanyour.core.ops

import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath

// RFC-THEME-1: the launcher's ThemeProvider (launcher 2.5, versionCode 6, and later; ThemeProvider.java and
// ThemeFiles.java in third_party/cleanyourtv/launcher), the way upstream's tools/theme.sh writes a theme. Exported,
// authority com.gathin.tvhome.theme, guarded by WRITE_SECURE_SETTINGS (the adb shell has it, ordinary apps do not);
// it opens config.json, wallpaper.jpg, theme_revision and wallpapers/<image> under the launcher's external files
// folder as the launcher. This app sends only the first two.
//
// Why: Android 11 firmware of Xiaomi and TCL locks the shell out of Android/data, so the sync push into the launcher's
// folder fails (issues #1279, #1649); on Android 14 the shell may write there, but the file it creates is the
// shell's and the launcher gets EACCES opening it, so the look is ignored (#1290). A file the provider writes is the
// launcher's own.
//
// Two lines here run more than one command under a single exec, like the watchdog (SECURITY 3.6): each is a string
// constant with placeholders, filled from one validated value (a file name of the two-entry list, which admits no
// quote, space or metacharacter), checked against its template again and covered by a golden file.
object ThemeProviderLines {
    const val AUTHORITY = "com.gathin.tvhome.theme"
    // The launcher versionCode that first has the provider (v2.5.0).
    const val FIRST_VERSION_CODE = 6L
    // `content write` exists from Android 10; Android 8 and 9 print "Unsupported operation: write" and exit 0.
    const val MIN_SDK = 29
    const val STAGE_PREFIX = "theme-"
    val FILES: Set<String> = setOf("config.json", "wallpaper.jpg")

    // The write: remove the shell's own copy first (the provider cannot truncate a file the shell owns), write the
    // staged file through the provider, and only when that answers anything at all (the content tool prints its
    // errors and still exits 0) copy the staged file in directly, the old way. The staged file always goes. The exit
    // status is the write's or the copy's, so a TV where neither works answers non-zero.
    // 0.5.0 (issues #4232, #4250 and six more on Android 11 and 12): when neither works, the copy's error ("cp: ...
    // No such file or directory") was all a report showed; what the provider answered was thrown away. Its first
    // exception class name (dotted, so "Error while accessing provider" itself never matches) now follows on
    // stderr as "provider: java.io.FileNotFoundException", or "provider: no exception named". Only on failure.
    const val WRITE_TEMPLATE = "rm -f DEST 2>/dev/null; o=\$(content write --uri URI < STAGE 2>&1) && [ -z \"\$o\" ] || cp STAGE DEST; " +
        "r=\$?; [ \$r -eq 0 ] || { e=\$(echo \"\$o\" | grep -o -E \"[A-Za-z0-9_]+[.][A-Za-z0-9_.]+(Exception|Error)\" | head -n 1); " +
        "echo \"provider: \${e:-no exception named}\" >&2; }; rm -f STAGE; exit \$r"

    // The check: the file as the launcher reads it, hashed on the TV. A file the launcher cannot open (or no file)
    // reads as nothing, so its hash is the empty file's and never the one expected.
    const val READ_TEMPLATE = "content read --uri URI | sha256sum"

    init {
        check(RemoteDir.OVERLAY.path == "/sdcard/Android/data/com.gathin.tvhome/files") { "the provider's folder differs from RemoteDir.OVERLAY" }
    }

    fun isThemeFile(path: RemotePath): Boolean = path.dir == RemoteDir.OVERLAY && path.segments.size == 1 && path.segments[0] in FILES

    fun uri(path: RemotePath): String {
        require(isThemeFile(path)) { "the launcher's provider takes only ${FILES.joinToString()} in ${RemoteDir.OVERLAY.path}" }
        return "content://$AUTHORITY/" + path.segments[0]
    }

    // Where a file is staged before the provider reads it: theme-config.json, theme-wallpaper.jpg in TMP.
    fun stage(path: RemotePath): RemotePath {
        require(isThemeFile(path)) { "only a theme file is staged" }
        return RemotePath(RemoteDir.TMP, listOf(STAGE_PREFIX + path.segments[0]))
    }

    fun write(dest: RemotePath): String = checked(fill(WRITE_TEMPLATE, dest), WRITE_TEMPLATE, dest)

    fun read(path: RemotePath): String = checked(fill(READ_TEMPLATE, path), READ_TEMPLATE, path)

    // The inverse check for the renderer's self-assertion and the tests: exactly the template with this file.
    fun matchesWrite(line: String, dest: RemotePath): Boolean = isThemeFile(dest) && line == fill(WRITE_TEMPLATE, dest)
    fun matchesRead(line: String, path: RemotePath): Boolean = isThemeFile(path) && line == fill(READ_TEMPLATE, path)

    private val PLACEHOLDER = Regex("DEST|URI|STAGE")

    // One pass, so a value is never rescanned for a placeholder.
    private fun fill(template: String, path: RemotePath): String {
        val uri = uri(path)
        val values = mapOf("DEST" to path.absolute, "URI" to uri, "STAGE" to stage(path).absolute)
        values.values.forEach { v -> require(SAFE.matches(v)) { "a theme path or uri fails ${SAFE.pattern}" } }
        return PLACEHOLDER.replace(template) { m -> values.getValue(m.value) }
    }

    private fun checked(line: String, template: String, path: RemotePath): String {
        check(ShellTokens.LINE_RE.matches(line)) { "theme provider line is not printable ASCII" }
        check(line.count { it == '\'' } == 0 && line.count { it == '"' } == template.count { it == '"' }) { "theme provider line has a quote of its own" }
        check(line == fill(template, path)) { "theme provider line does not match its template" }
        return line
    }

    // No quote, space or shell metacharacter: letters, digits and / : . _ -
    private val SAFE = Regex("^[A-Za-z0-9/:._-]{1,200}$")
}
