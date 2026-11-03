package tv.cleanyour.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.flows.UpdateChecker
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.FetchException
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.getSmall
import tv.cleanyour.core.trust.DownloadHosts

// The full build (GitHub and F-Droid, STORES-PLAN 1): everything the app did before the store
// flavours, unchanged.
object Store : StorePorts {
    override val flavour = "full"
    override val hosts: Set<String> = DownloadHosts.ALL
    override val rules = StoreRules.FULL
    override val updatesMayGoOff = rules.updatesOff
    override val releasePage: String = RELEASE_PAGE

    // D12: one GET on user action (Settings, "Check for updates"); "couldn't check" when GitHub
    // does not answer or the repository has no published release yet.
    //
    // 0.4.7 (issue #818, "Couldn't check for updates" on some phones, Android 17 among them): the API's
    // unauthenticated limit is 60 requests an hour per IP address, which a phone behind a carrier's shared
    // address (CGNAT) or a VPN shares with everyone else there, and the API then answers 403 or 429 and the check
    // said only "couldn't check". So when the API gives no tag, the releases feed on github.com (no API limit) is
    // read instead: its newest tag without a hyphen is the latest release, as /releases/latest never returns a test
    // build. When neither gives a tag, the diagnostics log (and so a problem report) says why, for each.
    override fun updateChecker(http: HttpFetcher) = updateChecker(http) { CytLog.i(it) }

    internal fun updateChecker(http: HttpFetcher, io: CoroutineDispatcher = Dispatchers.IO, log: (String) -> Unit) = UpdateChecker { current ->
        val (api, apiWhy) = read(http, RELEASES_URL, io)
        val fromApi = api?.let { Regex("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9A-Za-z.]*)\"").find(it)?.groupValues?.get(1) }
        val tag = fromApi ?: run {
            val (feed, feedWhy) = read(http, FEED_URL, io)
            val fromFeed = feed?.let { latestInFeed(it) }
            if (fromFeed == null) log("settings: update check failed: api ${apiWhy ?: "no tag"}, feed ${feedWhy ?: "no tag"}")
            else log("settings: update check used the releases feed (api ${apiWhy ?: "no tag"})")
            fromFeed
        } ?: return@UpdateChecker null
        if (offered(tag, current)) tag else ""
    }

    // A body, or why there is none: the HTTP code, or the fetcher's failure kind and its words (no URL, no
    // address: FetchException messages name only the step, as "TLS failed: SSLHandshakeException").
    // 0.4.8 (issues #818, #2486): the body is read off the main thread with the request (getSmall); read here, on
    // the Settings screen's scope, it failed with NetworkOnMainThreadException on every phone. At most MAX_BODY
    // bytes, as before.
    private suspend fun read(http: HttpFetcher, url: String, io: CoroutineDispatcher): Pair<String?, String?> = try {
        val r = http.getSmall(url, MAX_BODY, io)
        if (r.code != 200) null to "HTTP ${r.code}"
        else r.bytes?.decodeToString()?.let { it to null } ?: (null to "empty body")
    } catch (e: FetchException) {
        null to "${e.failure.name.lowercase()} (${e.message})"
    } catch (e: Exception) {
        null to e.javaClass.simpleName
    }

    // The newest release in the Atom feed (newest first) whose tag has no hyphen: a test build's tag has one
    // ("v0.4.6-beta3"), and /releases/latest never returns those either.
    internal fun latestInFeed(atom: String): String? =
        Regex("/releases/tag/v?([0-9][0-9A-Za-z.-]*)\"").findAll(atom).map { it.groupValues[1] }.firstOrNull { '-' !in it }

    // Whether the latest release `tag` is worth offering to `current`: any other version, as before,
    // except on a test build ("0.3.5-test1", a GitHub pre-release that /releases/latest never
    // returns), which is offered only a higher version or its own release, never the one before it.
    internal fun offered(tag: String, current: String): Boolean {
        if (tag == current) return false
        if ('-' !in current) return true
        fun numbers(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = numbers(tag)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return '-' !in tag
    }

    override fun addOnFetcher(make: () -> ArtifactFetcher): ArtifactFetcher = make()

    const val RELEASES_URL = "https://api.github.com/repos/udhaykumarbala/cleanyourtv-app/releases/latest"
    const val FEED_URL = "https://github.com/udhaykumarbala/cleanyourtv-app/releases.atom"
    private const val MAX_BODY = 2 * 1024 * 1024
    const val RELEASE_PAGE = "https://github.com/udhaykumarbala/cleanyourtv-app/releases"
}
