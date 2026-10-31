package tv.cleanyour.core.probe

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Issues #3221, #3101 and #2851: the TV's free storage, read after a streamed install the TV ended.
// `df -k /data` as Android 9 to 14 print it, read as untrusted: only the Available number counts,
// and only when the header says the numbers are 1K blocks.
class FreeDataTest {
    private fun parse(text: String) = FreeDataParser.parse(text.lineSequence())

    @Test fun toyboxOnAndroid9To14() {
        // Android 9 (toybox 0.7.x, a Xiaomi MiTV-AXSO0's form).
        assertEquals(2629, parse("Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-1   4962512 2253872   2692256  46% /data\n"))
        // Android 14 (toybox 0.8.x): wider columns, the same fields.
        assertEquals(6050, parse("Filesystem      1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-48 10913648 4718232   6195416  44% /data\n"))
        // A nearly full TV, the case of #2851: 312 MB.
        assertEquals(312, parse("Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-2   4883720 4564232    319488  94% /data\n"))
        // CRLF from a shell that sends it, and a TV that prints nothing free.
        assertEquals(0, parse("Filesystem     1K-blocks    Used Available Use% Mounted on\r\n/dev/block/dm-2   4883720 4883720         0 100% /data\r\n"))
    }

    // Busybox puts a long filesystem name on its own line and the numbers on the next.
    @Test fun aWrappedRow() {
        assertEquals(1241, parse("Filesystem           1K-blocks      Used Available Use% Mounted on\n/dev/block/platform/soc/by-name/userdata\n" +
            "                       4883720   3612180   1271540  74% /data\n"))
        assertEquals(1024, parse("Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/block/dm-0 4883720 3835144 1048576 79% /data\n"))
    }

    // Anything else is a ProbeException, so the failure text stays as it was.
    @Test fun otherFormsAreNotGuessed() {
        val forms = listOf(
            "Filesystem      Size  Used Avail Use% Mounted on\n/dev/block/dm-1  4.7G  2.1G  2.5G  46% /data\n",   // -k ignored, sizes
            "Filesystem               Size     Used     Free   Blksize\n/data                    4.7G     2.1G     2.5G   4096\n",   // toolbox, Android 5
            "Filesystem     512-blocks    Used Available Use% Mounted on\n/dev/block/dm-1   9925024 4507744   5384512  46% /data\n",
            "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-1   4962512 2253872   2692256  46% /\n",   // no /data row
            "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-1   4962512 2253872   2.6G  46% /data\n",
            "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-1   4962512 2253872   2692256  x /data\n",
            "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-1 2253872 2692256 46% /data\n",   // a field missing
            "/dev/block/dm-1   4962512 2253872   2692256  46% /data\n",   // no header
            "df: /data: Permission denied\n",
            "",
        )
        for (f in forms) assertFailsWith<ProbeException>(f) { parse(f) }
    }

    // Through the prober: the one fixed line, read once.
    @Test fun theProberReadsItWithTheFixedLine() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        assertEquals(1241, AdbProber(ProbeFixtures.clock()).freeDataMb(s))
        assertEquals(listOf("df -k /data"), s.execLines.filter { it == ProbeScripts.FREE_DATA.text })
    }
}
