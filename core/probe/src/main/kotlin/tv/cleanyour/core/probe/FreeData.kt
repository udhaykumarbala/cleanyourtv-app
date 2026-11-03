package tv.cleanyour.core.probe

// The answer of ProbeScripts.FREE_DATA (`df -k /data`), read as untrusted: only the Available number
// of the /data row leaves the parser, in MB. Issues #3221, #3101 and #2851 (InstallStorage in
// :core:engine) read it after a streamed install the TV ended.
//
// The forms it takes (toybox df on Android 9 to 14, and busybox where a vendor ships it):
//   Filesystem      1K-blocks    Used Available Use% Mounted on
//   /dev/block/dm-5   4883720 3612180   1271540  74% /data
// and a long filesystem name on a line of its own with the numbers on the next (busybox):
//   /dev/block/platform/soc/by-name/userdata
//                     4883720 3612180   1271540  74% /data
// The numbers are 1K blocks only when the header says so ("1K-blocks", or "1024-blocks"). A df that
// ignored -k and printed sizes ("Size ... Avail", "3.0G"), toolbox's "Free Blksize" table (Android 5
// and older), 512-byte blocks or any row that does not end in a percentage and /data is a
// ProbeException, never a guess.
object FreeDataParser {
    private val KILO_HEADER = Regex("(?i)^Filesystem\\s+(1K|1024)-blocks\\s")
    private val NUMBER = Regex("^[0-9]{1,15}$")
    private val PERCENT = Regex("^([0-9]{1,3}%|-)$")

    fun parse(lines: Sequence<String>): Int {
        val all = skipLong(lines).map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = all.firstOrNull { it.startsWith("Filesystem", ignoreCase = true) }
            ?: throw ProbeException(ProbeStage.FACTS, "df printed no header")
        if (!KILO_HEADER.containsMatchIn(header)) throw ProbeException(ProbeStage.FACTS, "df did not print 1K blocks")
        // The row's last fields: Size Used Available Use% Mounted-on, with or without the
        // filesystem's name in front (on its own line when it is long).
        val row = all.map { it.split(Regex("\\s+")) }.lastOrNull { it.size >= 5 && it.last() == "/data" }
            ?: throw ProbeException(ProbeStage.FACTS, "df printed no /data row")
        val n = row.size
        val (size, used, available) = Triple(row[n - 5], row[n - 4], row[n - 3])
        if (!PERCENT.matches(row[n - 2]) || listOf(size, used, available).any { !NUMBER.matches(it) }) {
            throw ProbeException(ProbeStage.FACTS, "df /data row is not in 1K blocks")
        }
        return (available.toLong() / 1024).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
