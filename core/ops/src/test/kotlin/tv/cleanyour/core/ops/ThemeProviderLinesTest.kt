package tv.cleanyour.core.ops

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.ops.ShellRenderer.Companion.EMPTY_FILE_SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// RFC-THEME-1: the launcher's ThemeProvider lines (issues #1279, #1649, #1094, #1290).
class ThemeProviderLinesTest {
    private val renderer = ShellRenderer()
    private val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
    private val wallpaper = RemotePath(RemoteDir.OVERLAY, listOf("wallpaper.jpg"))
    private val sha = Sha256("a".repeat(64))
    private val blob = BlobRef(sha, 900, "blobs/" + sha.hex)

    @Test fun theWriteIsUpstreamsWayWithTheCopyAsTheFallback() {
        assertEquals(
            "rm -f /sdcard/Android/data/com.gathin.tvhome/files/config.json 2>/dev/null; " +
                "o=\$(content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/cleanyourtv/theme-config.json 2>&1) && [ -z \"\$o\" ] || " +
                "cp /data/local/tmp/cleanyourtv/theme-config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json; " +
                "r=\$?; [ \$r -eq 0 ] || { e=\$(echo \"\$o\" | grep -o -E \"[A-Za-z0-9_]+[.][A-Za-z0-9_.]+(Exception|Error)\" | head -n 1); " +
                "echo \"provider: \${e:-no exception named}\" >&2; }; rm -f /data/local/tmp/cleanyourtv/theme-config.json; exit \$r",
            ThemeProviderLines.write(config))
        assertEquals("content read --uri content://com.gathin.tvhome.theme/wallpaper.jpg | sha256sum", ThemeProviderLines.read(wallpaper))
    }

    // Issues #4232 (Philips TPM191E, Android 12) and #4250 (SHIELD TV Pro, Android 11), and six more: the look's
    // send ended "nonzero_exit(1) cp: .../files/config.json: No such file or directory" and the provider's own
    // answer was lost. On failure the line now names the provider's first exception class, nothing else of its text.
    @Test fun aFailedWriteNamesTheProvidersExceptionOnly() {
        val line = ThemeProviderLines.write(config)
        val tail = line.substringAfter("r=\$?; ")
        assertTrue(tail.startsWith("[ \$r -eq 0 ] || {"), "only when the write and the copy both failed")
        assertTrue("grep -o -E \"[A-Za-z0-9_]+[.][A-Za-z0-9_.]+(Exception|Error)\" | head -n 1" in tail, "a dotted class name, the first one")
        assertTrue(tail.endsWith("exit \$r"), "the exit status stays the write's or the copy's")
        assertFalse("echo \"\$o\" >&2" in line, "the provider's text itself never leaves the TV")
    }

    @Test fun onlyTheTwoThemeFilesInTheLaunchersFolder() {
        for (p in listOf(RemotePath(RemoteDir.TMP, listOf("config.json")), RemotePath(RemoteDir.RESTORE, listOf("wallpaper.jpg")),
            RemotePath(RemoteDir.OVERLAY, listOf("theme_revision")), RemotePath(RemoteDir.OVERLAY, listOf("order.txt")),
            RemotePath(RemoteDir.OVERLAY, listOf("wallpapers", "a.jpg")))) {
            assertFalse(ThemeProviderLines.isThemeFile(p), p.absolute)
            assertFailsWith<IllegalArgumentException> { ThemeProviderLines.write(p) }
            assertFailsWith<IllegalArgumentException> { ThemeProviderLines.read(p) }
            assertFailsWith<RenderRefused> { renderer.render(Op.PushAsset(blob, p, sha, FileRoute.THEME_PROVIDER)) }
        }
    }

    @Test fun aPushThroughTheProviderStagesThenWritesThenReadsBackAsTheLauncher() {
        val r = renderer.render(Op.PushAsset(blob, config, sha, FileRoute.THEME_PROVIDER))
        assertEquals(Transfer.Push(blob, RemotePath(RemoteDir.TMP, listOf("theme-config.json")), 0x1A4), r.transfer)
        assertTrue(ThemeProviderLines.matchesWrite(r.lines.single().text, config))
        assertEquals(Op.Read(ProbeKind.LAUNCHER_FILE_SHA256, ReadArg.Path(config)), r.verify)
        // The check passes on the launcher's own hash only; nothing (the launcher cannot open it) is the empty hash.
        val op = r.op
        assertIs<tv.cleanyour.core.ops.VerifyResult.Ok>(renderer.check(op, "${sha.hex}  -\n", null))
        assertIs<VerifyResult.Mismatch>(renderer.check(op, "$EMPTY_FILE_SHA256  -\n", null))
        // Undo removes it the same way.
        assertEquals(Op.RemoveFile(config, FileRoute.THEME_PROVIDER), renderer.inverse(op, Prior(present = false)))
    }

    @Test fun aRemovalThroughTheProviderIsOneQuotedLineAndTheEmptyHashMeansGone() {
        val op = Op.RemoveFile(wallpaper, FileRoute.THEME_PROVIDER)
        val r = renderer.render(op)
        assertEquals(listOf("content", "delete", "--uri", "content://com.gathin.tvhome.theme/wallpaper.jpg"), ShellTokens.tokenize(r.lines.single().text))
        assertIs<VerifyResult.Ok>(renderer.check(op, "$EMPTY_FILE_SHA256  -\n", null))
        assertIs<VerifyResult.Mismatch>(renderer.check(op, "${sha.hex}  -\n", null))
        // The sync route keeps its rule: an empty file there is a file.
        assertIs<VerifyResult.Mismatch>(renderer.check(Op.RemoveFile(wallpaper), "$EMPTY_FILE_SHA256  $wallpaper\n", null))
    }

    @Test fun theSyncRouteIsUnchanged() {
        val r = renderer.render(Op.PushAsset(blob, config, sha))
        assertTrue(r.lines.isEmpty())
        assertEquals(Transfer.Push(blob, config, 0x1A4), r.transfer)
        assertEquals(Op.Read(ProbeKind.SHA256_OF_FILE, ReadArg.Path(config)), r.verify)
    }

    // A push runs before the op's lines, so the plan hash and Show commands put the sync line first; every earlier
    // op has either lines or a push, never both, so no earlier hash changes (GoldenTest.planHashIsPinned).
    @Test fun thePushIsHashedBeforeTheWrite() {
        val r = renderer.render(Op.PushAsset(blob, config, sha, FileRoute.THEME_PROVIDER))
        val input = "sync:/data/local/tmp/cleanyourtv/theme-config.json ${sha.hex}\n" + r.lines.single().text + "\n"
        assertEquals(ShellRenderer.sha256Of(input.encodeToByteArray()), renderer.planHash(listOf(r)))
    }
}
