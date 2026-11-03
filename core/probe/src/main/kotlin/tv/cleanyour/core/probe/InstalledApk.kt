package tv.cleanyour.core.probe

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.session.TvSession

// Not part of the frozen Prober (section 5.5), like LaunchableApps (RFC-INSTALL-1): why an
// installed APK's hash could not be read. Twenty reports from Google TVs on Android 11 and 12 ended
// "installed apk sha256 <x>, got no readable apk": the package dump showed the new versionCode, but
// `pm path` named no APK, the launcher did not start and its provider did not answer. That is a
// package on the TV that is not installed for user 0. One read-only call
// (ProbeScripts.installedApkState); only counts, exit codes, user ids and state flags leave the
// TV, never a path or a hash. Fails with AdbException or ProbeException like the Prober.
fun interface InstalledApkReader {
    suspend fun installedApk(session: TvSession, pkg: PackageName): InstalledApk
}

// `basePaths`: base.apk lines `pm path` printed (it asks for user 0). `hashExit`: the exit code of
// sha256sum on the first of them. `currentUser`: the user in front. `users`: the package's state for
// each user its dump lists, in order. Any of them may be unknown (null) on a firmware that prints
// something else.
data class InstalledApk(val basePaths: Int?, val hashExit: Int?, val currentUser: Int?, val users: List<UserState>) {
    data class UserState(val id: Int, val installed: Boolean?, val hidden: Boolean?, val suspended: Boolean?, val stopped: Boolean?, val enabled: Int?)

    enum class Cause { NOT_ON_TV, NOT_INSTALLED_FOR_USER_0, HIDDEN_FOR_USER_0, NO_PATH, APK_UNREADABLE, UNKNOWN }

    val user0: UserState? get() = users.firstOrNull { it.id == 0 }
    val othersInstalled: Int get() = users.count { it.id != 0 && it.installed == true }

    val cause: Cause get() = when {
        users.isEmpty() && basePaths == 0 -> Cause.NOT_ON_TV
        user0?.installed == false -> Cause.NOT_INSTALLED_FOR_USER_0
        user0?.hidden == true -> Cause.HIDDEN_FOR_USER_0
        basePaths == 0 -> Cause.NO_PATH
        basePaths != null && basePaths > 0 && hashExit != null && hashExit != 0 -> Cause.APK_UNREADABLE
        else -> Cause.UNKNOWN
    }

    // Fixed words for the install check's failure ("got no readable apk (...)"), read in reports.
    fun why(): String = when (cause) {
        Cause.NOT_ON_TV -> "the app is not on the TV"
        Cause.NOT_INSTALLED_FOR_USER_0 -> "on the TV but not installed for user 0" +
            if (othersInstalled > 0) ", installed for $othersInstalled other user${if (othersInstalled == 1) "" else "s"}" else ""
        Cause.HIDDEN_FOR_USER_0 -> "hidden for user 0"
        Cause.NO_PATH -> "pm path names no base.apk"
        Cause.APK_UNREADABLE -> "its base.apk could not be hashed"
        Cause.UNKNOWN -> "cause unknown"
    }

    // The diagnostics line's body: fixed words, numbers and yes/no only.
    fun summary(): String {
        fun n(v: Int?) = v?.toString() ?: "?"
        fun b(v: Boolean?) = when (v) { true -> "yes"; false -> "no"; null -> "?" }
        val u0 = user0?.let { "user 0 installed ${b(it.installed)}, hidden ${b(it.hidden)}, suspended ${b(it.suspended)}, stopped ${b(it.stopped)}, enabled ${n(it.enabled)}" }
            ?: "no user 0 line"
        return "pm path base.apk lines ${n(basePaths)}, hash exit ${n(hashExit)}, current user ${n(currentUser)}, $u0, " +
            "${users.size} user${if (users.size == 1) "" else "s"} listed, installed for ${othersInstalled} other"
    }
}

// The installedApkState script's sections: "" the base.apk count, "hash" the exit code, "current"
// the user in front, "users" the dump's "User N:" lines (the first line of each id counts).
object InstalledApkParser {
    private val NUMBER = Regex("^-?[0-9]{1,9}$")
    private val USER = Regex("^ +User ([0-9]{1,6}): (.*)$")
    private fun flag(name: String, body: String): Boolean? = Regex("\\b$name=(true|false)\\b").find(body)?.groupValues?.get(1)?.toBooleanStrict()

    fun parse(sections: Map<String, List<String>>): InstalledApk {
        fun number(name: String): Int? = sections[name].orEmpty().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { NUMBER.matches(it) }?.toInt()
        val users = LinkedHashMap<Int, InstalledApk.UserState>()
        for (l in skipLong(sections["users"].orEmpty().asSequence())) {
            val m = USER.matchEntire(l) ?: continue
            val id = m.groupValues[1].toInt()
            if (id in users) continue
            val body = m.groupValues[2]
            users[id] = InstalledApk.UserState(id, flag("installed", body), flag("hidden", body), flag("suspended", body), flag("stopped", body),
                Regex("\\benabled=([0-9])\\b").find(body)?.groupValues?.get(1)?.toInt())
        }
        return InstalledApk(number(""), number("hash"), number("current"), users.values.toList())
    }
}
