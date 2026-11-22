package tv.cleanyour.core.ops

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.CurtainGroup
import tv.cleanyour.core.model.CurtainStage
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.SettingsNs
import tv.cleanyour.core.model.Sha256
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// SECURITY 3.4 item 4 as property tests over seeded generators (deterministic, no library): for any
// op built from arguments that pass the grammars, the rendered line is in the safe alphabet, has
// nothing outside quotes but single spaces, tokenizes back to its argv, and contains no quote except
// the wrapping ones; for any argument that fails a grammar the renderer is never reached (the value
// class refuses) or refuses itself.
class RendererPropertyTest {
    private val renderer = ShellRenderer()
    private val rnd = Random(20261019)

    private val lower = "abcdefghijklmnopqrstuvwxyz"
    private val alnum = lower + lower.uppercase() + "0123456789_"

    private fun word(min: Int = 1, max: Int = 12): String = lower[rnd.nextInt(26)] + buildString { repeat(rnd.nextInt(min, max)) { append(alnum[rnd.nextInt(alnum.length)]) } }
    private fun pkg(): PackageName {
        while (true) {
            val p = PackageName((1..rnd.nextInt(2, 5)).joinToString(".") { word() })
            if (Denylist.staticReason(p.value) == null && p.value !in Denylist.setupWizards) return p
        }
    }
    private fun cls(): String = (if (rnd.nextBoolean()) "." else "") + word().replaceFirstChar { it.uppercase() } + (if (rnd.nextBoolean()) "\$Inner" else "")
    private fun comp() = Component(pkg(), cls())
    private fun sha() = Sha256(buildString { repeat(64) { append("0123456789abcdef"[rnd.nextInt(16)]) } })
    private fun token() = RunToken(buildString { repeat(16) { append("0123456789abcdef"[rnd.nextInt(16)]) } })
    private fun fileName() = (lower + "0123456789")[rnd.nextInt(36)] + buildString { repeat(rnd.nextInt(0, 20)) { append((alnum + "._-")[rnd.nextInt(alnum.length + 3)]) } }
    private fun path(dir: RemoteDir = RemoteDir.entries[rnd.nextInt(3)]) = RemotePath(dir, (1..rnd.nextInt(1, 4)).map { fileName() }.let { if (it.any { s -> s.contains("..") }) listOf("x") else it })
    // RFC-THEME-1: the two files the launcher's provider takes from this app.
    private fun themeFile() = RemotePath(RemoteDir.OVERLAY, listOf(ThemeProviderLines.FILES.toList()[rnd.nextInt(ThemeProviderLines.FILES.size)]))
    private fun blob(size: Long = rnd.nextLong(1, 200L * 1024 * 1024)) = sha().let { BlobRef(it, size, "blobs/" + it.hex) }
    private fun componentList() = (1..rnd.nextInt(1, 4)).joinToString(":") { comp().flat }
    private fun deviceName() = buildString { repeat(rnd.nextInt(1, 33)) { append((alnum + " ._-")[rnd.nextInt(alnum.length + 4)]) } }.trim().ifEmpty { "TV" }

    private fun settingsPut(): Op.SettingsPut {
        val key = SettingKey.entries[rnd.nextInt(SettingKey.entries.size)]
        val value: String? = when (key) {
            SettingKey.SCREENSAVER_ENABLED, SettingKey.ACCESSIBILITY_ENABLED -> listOf("0", "1", null)[rnd.nextInt(3)]
            SettingKey.SCREENSAVER_COMPONENTS, SettingKey.ENABLED_ACCESSIBILITY -> if (rnd.nextInt(4) == 0) null else componentList()
            SettingKey.ADB_ENABLED, SettingKey.ADB_WIFI_ENABLED -> "0"
            SettingKey.DEVICE_NAME -> if (rnd.nextInt(4) == 0) null else deviceName()
            SettingKey.PRIVATE_DNS_MODE -> listOf("off", "opportunistic", "hostname", null)[rnd.nextInt(4)]
            SettingKey.PRIVATE_DNS_SPECIFIER -> listOf("dns.adguard-dns.com", "one.one.one.one", null)[rnd.nextInt(3)]
        }
        return Op.SettingsPut(key, value)
    }

    private fun read(): Op.Read {
        val kind = ProbeKind.entries[rnd.nextInt(ProbeKind.entries.size)]
        val arg = when (kind) {
            ProbeKind.SETTINGS -> ReadArg.Ns(SettingsNs.entries[rnd.nextInt(3)])
            ProbeKind.SHA256_OF_FILE -> ReadArg.Path(path())
            ProbeKind.LAUNCHER_FILE_SHA256 -> ReadArg.Path(themeFile())
            ProbeKind.SHA256_OF_INSTALLED, ProbeKind.PACKAGE_DUMP -> ReadArg.Pkg(pkg())
            ProbeKind.WATCHDOG_ALIVE -> ReadArg.Token(token())
            else -> null
        }
        return Op.Read(kind, arg)
    }

    private val curtain = Component(PackageName("tv.cleanyour.curtain"), ".CueReceiver")
    private fun cue() = Op.CurtainCue(curtain, token(), CurtainStage.entries[rnd.nextInt(CurtainStage.entries.size)], rnd.nextInt(0, 1000), rnd.nextInt(0, 1000),
        CurtainGroup.entries[rnd.nextInt(CurtainGroup.entries.size)], rnd.nextInt(0, 1000), rnd.nextInt(0, 1000))

    // RFC-HELPER-1: the helper's three ops, with lists that do and do not name it.
    private val service = HelperLines.SERVICE
    private fun listWithout(): String? = if (rnd.nextInt(3) == 0) null else componentList()
    private fun listWith(): String = (HelperLines.entries(listWithout()) + service.flat).shuffled(rnd).joinToString(":")
    private fun helperOp(): Op = when (rnd.nextInt(4)) {
        0 -> Op.AccessibilityAdd(listWithout(), service)
        1 -> Op.AccessibilityRemove(listWith(), service)
        2 -> Op.AccessibilityRemove(null, service, rnd.nextBoolean())
        else -> Op.ArmHelperWatchdog(service, rnd.nextInt(30, 301), token(), rnd.nextBoolean())
    }

    private fun anyOp(): Op = when (rnd.nextInt(24)) {
        0 -> Op.Disable(pkg())
        1 -> Op.Enable(pkg())
        2 -> Op.DefaultState(pkg())
        3 -> sha().let { Op.Install(RemotePath(RemoteDir.TMP, listOf(it.hex.take(40) + ".apk")), it, pkg(), rnd.nextLong(0, 1_000_000), sha(), if (rnd.nextBoolean()) setOf(InstallFlag.REPLACE) else emptySet()) }
        4 -> blob().let { Op.InstallStream(it, it.sha256, pkg(), rnd.nextLong(0, 1_000_000), sha()) }
        5 -> Op.Uninstall(pkg())
        6 -> settingsPut()
        7 -> Op.SetHome(comp())
        8 -> Op.VerifyHome(comp())
        9 -> Op.ArmHomeWatchdog(comp(), rnd.nextInt(30, 301), token())
        10 -> Op.ConfirmHome(token())
        11 -> Op.KillWatchdog(token())
        12 -> Op.PressHome
        13 -> Op.Mkdir(RemoteDir.entries[rnd.nextInt(3)])
        14 -> blob().let { if (rnd.nextBoolean()) Op.PushAsset(it, path(), it.sha256) else Op.PushAsset(it, themeFile(), it.sha256, FileRoute.THEME_PROVIDER) }
        15 -> if (rnd.nextBoolean()) Op.RemoveFile(path()) else Op.RemoveFile(themeFile(), FileRoute.THEME_PROVIDER)
        16 -> Op.RestartHome(comp())
        17 -> Op.StartActivity(comp(), rnd.nextBoolean())
        18 -> read()
        19 -> Op.Settle(rnd.nextInt(1, 61))
        20 -> Op.Reboot
        21 -> cue()
        22 -> helperOp()
        else -> Op.DebugOff
    }

    @Test fun everyRenderedLineIsSafeAndTokenizesBackToItsArgv() {
        var lines = 0
        repeat(5000) {
            val op = anyOp()
            val r = renderer.render(op)
            if (op is Op.ArmHomeWatchdog) {
                // Two lines: the stale token file removal (an ordinary quoted line), then the template.
                assertEquals(2, r.lines.size)
                ShellTokens.assertLine(r.lines[0].text)
                assertEquals(listOf("rm", "-f", "/data/local/tmp/cleanyourtv/home-ok-" + op.token.value), ShellTokens.tokenize(r.lines[0].text))
                assertTrue(ShellTokens.LINE_RE.matches(r.lines[1].text))
                assertTrue(Watchdog.matches(WatchdogVariant.NOHUP_SETSID, r.lines[1].text, op.stock, op.seconds, op.token))
                return@repeat
            }
            // RFC-THEME-1: the provider's write is its template filled with the file, after the push to its staging file.
            if (op is Op.PushAsset && op.route == FileRoute.THEME_PROVIDER) {
                assertTrue(ThemeProviderLines.matchesWrite(r.lines.single().text, op.dest), r.lines.single().text)
                assertEquals(Transfer.Push(op.src, ThemeProviderLines.stage(op.dest), 0x1A4), r.transfer)
                assertTrue(ThemeProviderLines.matchesRead(renderer.render(r.verify!!).lines.single().text, op.dest))
                return@repeat
            }
            if (op is Op.Read && op.kind == ProbeKind.LAUNCHER_FILE_SHA256) {
                assertTrue(ThemeProviderLines.matchesRead(r.lines.single().text, (op.arg as ReadArg.Path).path), r.lines.single().text)
                return@repeat
            }
            // RFC-HELPER-1: one HelperLines template each (the timer after its token file removal).
            if (op is Op.AccessibilityAdd || op is Op.AccessibilityRemove || op is Op.ArmHelperWatchdog) {
                r.lines.forEach { assertTrue(ShellTokens.LINE_RE.matches(it.text), it.text) }
                assertTrue(helperLineIsItsTemplate(op, r.lines.last().text), r.lines.last().text)
                return@repeat
            }
            for (line in r.lines) {
                lines++
                val text = line.text
                assertTrue(ShellTokens.LINE_RE.matches(text), text)
                ShellTokens.assertLine(text)
                val argv = ShellTokens.tokenize(text)
                assertEquals(text, argv.joinToString(" ") { "'$it'" }, "argv quoted again is the line")
                assertTrue(argv.none { it.contains('\'') }, "no quote inside a token")
                assertTrue(argv.all { ShellTokens.isSafeToken(it) })
                // Outside the quotes: nothing but single spaces (the belt and braces of 3.4 item 3).
                val outside = text.replace(Regex("'[^']*'"), "")
                assertTrue(outside.all { it == ' ' } && outside.length == argv.size - 1, text)
            }
            if (op is Op.Settle) assertTrue(r.lines.isEmpty() && r.transfer == null)
            if (op is Op.PushAsset || op is Op.InstallStream) assertTrue(r.lines.isEmpty() && r.transfer != null)
            r.verify?.takeUnless { it.kind == ProbeKind.LAUNCHER_FILE_SHA256 }?.let { v -> renderer.render(v).lines.forEach { ShellTokens.assertLine(it.text) } }
        }
        assertTrue(lines > 3500)
    }

    // SECURITY.md, Launcher 3.0: the app never approves the launcher's notification listener
    // (GuardRevival needs `cmd notification allow_listener`), never grants it WRITE_SECURE_SETTINGS
    // (`pm grant`, which also guards GuardConfig) and never broadcasts to it; its only accessibility
    // writes are SettingsPut of the two accessibility settings with the op's own value, which the
    // planner uses only to put back the list the TV had (restoreAccessibility, Launcher24PlanTest),
    // and, since RFC-HELPER-1 (test build), the Home button helper's templates for HomeKeyService alone.
    // Pinned for every op kind, random ones and ones aimed at the launcher's 3.0 components.
    @Test fun noOpApprovesGrantsOrSwitchesOnTheLaunchersHelpers() {
        val tvhome = PackageName("com.gathin.tvhome")
        val parts = listOf(".GuardRevival", ".GuardConfig", ".AppLaunchActivity", ".HomeKeyService", ".InputKeyService", ".MainActivity")
            .map { Component(tvhome, it) }
        val aimed = parts.flatMap { c ->
            listOf(Op.Enable(tvhome), Op.DefaultState(tvhome), Op.SetHome(c), Op.VerifyHome(c), Op.RestartHome(c),
                Op.StartActivity(c, true), Op.StartActivity(c, false), Op.ArmHomeWatchdog(c, 60, token()),
                Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, c.flat), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"),
                Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(tvhome)), Op.Read(ProbeKind.SETTINGS, ReadArg.Ns(SettingsNs.SECURE)))
        }
        val forbidden = setOf("notification", "allow_listener", "allow_assistant", "grant", "appops")
        // No setting the app can write is a notification one (enabled_notification_listeners and the like).
        for (k in SettingKey.entries) assertTrue("notification" !in k.key, k.key)
        for (op in aimed + List(5000) { anyOp() }) {
            val r = try { renderer.render(op) } catch (e: RenderRefused) { continue }
            val rendered = r.lines + listOfNotNull(r.verify).flatMap { renderer.render(it).lines }
            for (line in rendered) {
                // RFC-THEME-1: the provider's two templates name only content, rm, cp, sha256sum, the file and its uri.
                if (ThemeProviderLines.FILES.any { f -> RemotePath(RemoteDir.OVERLAY, listOf(f)).let { ThemeProviderLines.matchesWrite(line.text, it) || ThemeProviderLines.matchesRead(line.text, it) } }) {
                    assertTrue(forbidden.none { it in line.text } && "WRITE_SECURE_SETTINGS" !in line.text && "accessibility" !in line.text, line.text)
                    continue
                }
                if (op is Op.ArmHomeWatchdog && line.text == renderer.render(op).lines.last().text) {
                    assertTrue(forbidden.none { it in line.text } && "WRITE_SECURE_SETTINGS" !in line.text, line.text)
                    continue
                }
                // RFC-HELPER-1: the only lines that write the list outside SettingsPut are the helper's
                // templates, for our HomeKeyService alone, and none of them switches accessibility on.
                if ((op is Op.AccessibilityAdd || op is Op.AccessibilityRemove || op is Op.ArmHelperWatchdog) && line.text == renderer.render(op).lines.last().text) {
                    assertTrue(forbidden.none { it in line.text } && "WRITE_SECURE_SETTINGS" !in line.text, line.text)
                    assertTrue(helperLineIsItsTemplate(op, line.text), line.text)
                    assertTrue("accessibility_enabled 1" !in line.text && "accessibility_enabled '1'" !in line.text, line.text)
                    continue
                }
                val argv = ShellTokens.tokenize(line.text)
                assertTrue(argv.none { it in forbidden }, line.text)
                assertTrue(argv.none { "WRITE_SECURE_SETTINGS" in it || "BIND_NOTIFICATION_LISTENER" in it }, line.text)
                if (argv.take(2) == listOf("am", "broadcast")) assertEquals("tv.cleanyour.curtain/.CueReceiver", argv[3], line.text)
                // Any line naming accessibility, but the settings list read, is one of the two SettingsPut forms.
                if (argv.any { "accessibility" in it } && argv.take(2) != listOf("settings", "list")) {
                    val put = assertIs<Op.SettingsPut>(op, line.text)
                    assertTrue(put.key == SettingKey.ENABLED_ACCESSIBILITY || put.key == SettingKey.ACCESSIBILITY_ENABLED, line.text)
                    val want = if (put.value == null) listOf("settings", "delete", "secure", put.key.key)
                        else listOf("settings", "put", "secure", put.key.key, put.value!!)
                    assertEquals(want, argv)
                }
            }
        }
    }

    private fun helperLineIsItsTemplate(op: Op, line: String): Boolean = when (op) {
        is Op.AccessibilityAdd -> op.component == service && HelperLines.matchesAdd(line, op.expected, op.component)
        is Op.AccessibilityRemove -> op.component == service && line == (op.expected?.let { HelperLines.casRemove(it, op.component) } ?: HelperLines.tvRemove(op.component, op.offIfEmpty))
        is Op.ArmHelperWatchdog -> op.component == service && HelperLines.matchesWatchdog(line, WatchdogVariant.NOHUP_SETSID, op.component, op.seconds, op.token, op.offIfEmpty)
        else -> false
    }

    // RFC-CURTAIN-1: every cue is one fixed argv: constant words, the token, two enum names and four
    // decimal numbers; nothing out of range or aimed at another package renders.
    @Test fun everyCueIsOneFixedArgv() {
        repeat(2000) {
            val op = cue()
            val r = renderer.render(op)
            val argv = ShellTokens.tokenize(r.lines.single().text)
            assertEquals(listOf("am", "broadcast", "-n", "tv.cleanyour.curtain/.CueReceiver", "-a", "tv.cleanyour.curtain.CUE",
                "--es", "token", op.token.value, "--es", "stage", op.stage.name, "--es", "group", op.group.name,
                "--ei", "count", "${op.count}", "--ei", "total", "${op.total}", "--ei", "gcount", "${op.groupCount}", "--ei", "gtotal", "${op.groupTotal}", "--receiver-foreground"), argv)
            assertEquals(Privilege.PROFILE_STEP, r.privilege)
            assertEquals(null, r.verify)
            assertEquals(null, r.transfer)
            assertEquals(null, renderer.inverse(op, tv.cleanyour.core.model.Prior(present = false)))
        }
        val t = RunToken("0123456789abcdef")
        val bad = listOf(
            Op.CurtainCue(curtain, t, CurtainStage.OFF, -1, 3), Op.CurtainCue(curtain, t, CurtainStage.OFF, 1, 1000),
            Op.CurtainCue(curtain, t, CurtainStage.OFF, 1, 3, CurtainGroup.ADVERTS, 1000, 1), Op.CurtainCue(curtain, t, CurtainStage.OFF, 1, 3, CurtainGroup.ADVERTS, 1, -5),
            Op.CurtainCue(Component(PackageName("com.gathin.tvhome"), ".CueReceiver"), t, CurtainStage.SCAN, 0, 0),
            Op.CurtainCue(Component(PackageName("tv.cleanyour.curtainx"), ".CueReceiver"), t, CurtainStage.SCAN, 0, 0),
        )
        for (op in bad) assertFailsWith<RenderRefused>(op.toString()) { renderer.render(op) }
    }

    // A plan with a cue hashes differently from the same plan without it (the cue lines are in the hash).
    @Test fun cueLinesAreInThePlanHash() {
        val base = listOf(Op.Disable(PackageName("com.miui.tv.analytics")), Op.Settle(3)).map { renderer.render(it) }
        val withCue = base + renderer.render(Op.CurtainCue(curtain, RunToken("0123456789abcdef"), CurtainStage.FINALE, 0, 0))
        assertTrue(renderer.planHash(base) != renderer.planHash(withCue))
        val otherStage = base + renderer.render(Op.CurtainCue(curtain, RunToken("0123456789abcdef"), CurtainStage.INSTALL, 0, 0))
        assertTrue(renderer.planHash(withCue) != renderer.planHash(otherStage))
    }

    @Test fun inverseOfAnInverseRendersToo() {
        repeat(2000) {
            val op = anyOp()
            val prior = tv.cleanyour.core.model.Prior(
                packageState = tv.cleanyour.core.model.EnabledState.entries[rnd.nextInt(4)],
                settingValue = listOf(null, "0", "1", deviceName())[rnd.nextInt(4)].takeIf { op !is Op.SettingsPut || (op.key.valueShape.matches(it ?: "0")) },
                home = comp(), present = rnd.nextBoolean(),
            )
            val inverse = renderer.inverse(op, prior) ?: return@repeat
            val r = renderer.render(inverse)
            // RFC-HELPER-1: the add's inverse is the helper's TV-side removal template.
            if (inverse is Op.AccessibilityRemove) { assertTrue(helperLineIsItsTemplate(inverse, r.lines.single().text)); return@repeat }
            r.lines.forEach { ShellTokens.assertLine(it.text) }
        }
    }

    @Test fun argumentsThatFailAGrammarNeverReachALine() {
        // The value classes refuse before the renderer is called.
        for (bad in listOf("nodots", "com.x;rm", "com.x y", "-com.x", "com.x'", "com.\$x", "com.x\n", "", "com.x.")) {
            assertFailsWith<IllegalArgumentException>(bad) { PackageName(bad) }
        }
        for (bad in listOf("", "a'b", "a b", "a;b", "Main\$Activity ", ".Main/Other")) {
            assertFailsWith<IllegalArgumentException>(bad) { Component(PackageName("com.a.b"), bad) }
        }
        for (bad in listOf("", "0123", "0123456789ABCDEF", "0123456789abcdefg", "'123456789abcdef")) {
            assertFailsWith<IllegalArgumentException>(bad) { RunToken(bad) }
        }
        for (bad in listOf(listOf(""), listOf("a..b"), listOf("-x"), listOf("a b"), listOf("a'b"), listOf("a", "b", "c", "d"), emptyList(), listOf("a/b"))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { RemotePath(RemoteDir.TMP, bad) }
        }
        // And the renderer refuses what the types cannot express as a bad combination.
        val launcher = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
        val refused = listOf<Op>(
            Op.SettingsPut(SettingKey.ADB_ENABLED, "1"),
            Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "2"),
            Op.SettingsPut(SettingKey.DEVICE_NAME, "a'b"),
            Op.SettingsPut(SettingKey.DEVICE_NAME, "x".repeat(33)),
            Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, "notacomponent"),
            Op.ArmHomeWatchdog(launcher, 29, RunToken("0123456789abcdef")),
            Op.ArmHomeWatchdog(launcher, 301, RunToken("0123456789abcdef")),
            Op.Settle(0), Op.Settle(61),
            Op.Install(RemotePath(RemoteDir.OVERLAY, listOf("x.apk")), Sha256("a".repeat(64)), launcher.pkg, 1, Sha256("b".repeat(64)), emptySet()),
            Op.Install(RemotePath(RemoteDir.TMP, listOf("x.bin")), Sha256("a".repeat(64)), launcher.pkg, 1, Sha256("b".repeat(64)), emptySet()),
            Op.InstallStream(BlobRef(Sha256("a".repeat(64)), 10, "p"), Sha256("c".repeat(64)), launcher.pkg, 1, Sha256("b".repeat(64))),
            Op.InstallStream(BlobRef(Sha256("a".repeat(64)), 0, "p"), Sha256("a".repeat(64)), launcher.pkg, 1, Sha256("b".repeat(64))),
            Op.PushAsset(BlobRef(Sha256("a".repeat(64)), 10, "p"), RemotePath(RemoteDir.TMP, listOf("f")), Sha256("c".repeat(64))),
            Op.PushAsset(BlobRef(Sha256("a".repeat(64)), 201L * 1024 * 1024, "p"), RemotePath(RemoteDir.TMP, listOf("f")), Sha256("a".repeat(64))),
            Op.Read(ProbeKind.PROPS, ReadArg.Pkg(launcher.pkg)),
            Op.Read(ProbeKind.SETTINGS),
            Op.Read(ProbeKind.SHA256_OF_FILE, ReadArg.Pkg(launcher.pkg)),
            Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Ns(SettingsNs.SECURE)),
            Op.Read(ProbeKind.WATCHDOG_ALIVE, ReadArg.Pkg(launcher.pkg)),
            Op.Read(ProbeKind.WATCHDOG_ALIVE),
            // RFC-THEME-1: the launcher's provider takes only config.json and wallpaper.jpg in its folder.
            Op.PushAsset(BlobRef(Sha256("a".repeat(64)), 10, "p"), RemotePath(RemoteDir.TMP, listOf("config.json")), Sha256("a".repeat(64)), FileRoute.THEME_PROVIDER),
            Op.PushAsset(BlobRef(Sha256("a".repeat(64)), 10, "p"), RemotePath(RemoteDir.OVERLAY, listOf("theme_revision")), Sha256("a".repeat(64)), FileRoute.THEME_PROVIDER),
            Op.PushAsset(BlobRef(Sha256("a".repeat(64)), 10, "p"), RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "a.jpg")), Sha256("a".repeat(64)), FileRoute.THEME_PROVIDER),
            Op.RemoveFile(RemotePath(RemoteDir.RESTORE, listOf("wallpaper.jpg")), FileRoute.THEME_PROVIDER),
            Op.Read(ProbeKind.LAUNCHER_FILE_SHA256, ReadArg.Path(RemotePath(RemoteDir.OVERLAY, listOf("order.txt")))),
            Op.Read(ProbeKind.LAUNCHER_FILE_SHA256),
            Op.Disable(PackageName("com.android.systemui")),
            Op.Disable(PackageName("com.android.providers.media.module")),
            Op.Disable(PackageName("com.android.wifi.resources")),
            Op.Uninstall(PackageName("com.google.android.gms")),
            Op.DefaultState(PackageName("com.android.settings")),
            // Installing over a protected package is refused too (a signed index entry naming one).
            Op.Install(RemotePath(RemoteDir.TMP, listOf("x.apk")), Sha256("a".repeat(64)), PackageName("com.android.vending"), 1, Sha256("b".repeat(64)), setOf(InstallFlag.REPLACE)),
            Op.InstallStream(BlobRef(Sha256("a".repeat(64)), 10, "p"), Sha256("a".repeat(64)), PackageName("com.google.android.gms"), 1, Sha256("b".repeat(64))),
        )
        for (op in refused) {
            val e = assertFailsWith<RenderRefused>(op.toString()) { renderer.render(op) }
            assertEquals(op, e.op)
            assertTrue(e.reason.isNotBlank())
        }
    }
}
