package tv.cleanyour.core.engine

// Issues #3221 (Xiaomi MiTV-AXSO0, Android 9), #3101 (Xiaomi MiTV-MSSP1, Android 9) and #2851 (Sony
// BRAVIA UR3, Android 10, whose author found the TV low on storage): the launcher's streamed install
// ended "streamed install failed, the TV is still connected: Protocol(detail=the TV closed the
// stream)" with nothing to say why. After such a failure the engine reads the free space on /data
// once (the Prober's `df -k /data`, numbers only) and adds it to the failure in fixed words, so the
// step line of a report says it; the flows read the same words back for the stop card.
object InstallStorage {
    private val TAG_RE = Regex("\\[TV storage: ([0-9]{1,9}) MB free]")

    // " [TV storage: 312 MB free]", appended to the failure text.
    fun tag(freeMb: Int): String = " [TV storage: $freeMb MB free]"

    // The free MB a failure text names, or null when it names none (an older journal, a probe that failed).
    fun freeMbIn(detail: String): Int? = TAG_RE.find(detail)?.groupValues?.get(1)?.toIntOrNull()

    // Below this the TV is clearly low on storage, whatever the APK.
    const val FLOOR_MB = 200L

    // "Clearly low" only, so a stream the TV closed for another reason is not blamed on storage. An
    // install needs the APK's bytes on /data and then about as much again for its compiled code, so
    // under twice the APK there is no room at all. Android also keeps a reserve of its own (5 % of
    // /data, at most 500 MB) and starts to refuse or end installs inside it; 200 MB is under that
    // reserve on every TV with 4 GB of /data or more, so a TV under the floor is low by Android's
    // own measure too, while a TV above it may still have room and gets the plain card.
    fun low(freeMb: Int, apkBytes: Long): Boolean {
        val apkMb = (apkBytes + MB - 1) / MB
        return freeMb.toLong() < maxOf(FLOOR_MB, 2 * apkMb)
    }

    private const val MB = 1024L * 1024
}
