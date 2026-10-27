package tv.cleanyour.testing.fixtures

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.backup.RestoreNote
import tv.cleanyour.core.backup.TvRestoreFiles
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.Planner
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.HomeAwareProfileSource
import tv.cleanyour.core.spec.PackageFacts
import tv.cleanyour.core.spec.Picture
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.spec.ThemeSource
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// Prober with settable answers; snapshot() reports every stage in order like the real one.
class FakeProber(
    @Volatile var facts: DeviceFacts = Samples.miTvFacts(),
    @Volatile var snapshotFor: (SnapshotReason) -> Snapshot = { Samples.miTvSnapshot(reason = it) },
    @Volatile var home: HomeResolution = Samples.miTvSnapshot().home,
    @Volatile var focus: Component? = null,
    @Volatile var screenshotPng: ByteArray? = null,
    @Volatile var freeMb: Int = 900,
    @Volatile var refusesInstall: Boolean = false,
    @Volatile var bootIdValue: String = "fake-boot-1",
) : Prober {
    val fileHashes = ConcurrentHashMap<String, Sha256>()
    val installedHashes = ConcurrentHashMap<PackageName, Sha256>()
    val liveWatchdogs: MutableSet<RunToken> = ConcurrentHashMap.newKeySet()
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun facts(session: TvSession): DeviceFacts { calls += "facts"; return facts }
    override suspend fun snapshot(session: TvSession, reason: SnapshotReason, onStage: (ProbeStage) -> Unit): Snapshot {
        calls += "snapshot $reason"
        ProbeStage.entries.forEach(onStage)
        return snapshotFor(reason)
    }
    override suspend fun homeNow(session: TvSession): HomeResolution { calls += "homeNow"; return home }
    override suspend fun homeFocus(session: TvSession): Component? { calls += "homeFocus"; return focus }
    override suspend fun screenshot(session: TvSession): ByteArray? { calls += "screenshot"; return screenshotPng }
    override suspend fun fileSha256(session: TvSession, path: RemotePath): Sha256? { calls += "fileSha256 ${path.absolute}"; return fileHashes[path.absolute] }
    override suspend fun installedSha256(session: TvSession, pkg: PackageName): Sha256? { calls += "installedSha256 ${pkg.value}"; return installedHashes[pkg] }
    override suspend fun freeDataMb(session: TvSession): Int { calls += "freeDataMb"; return freeMb }
    override suspend fun installRefusal(session: TvSession, noopApk: BlobRef): Boolean { calls += "installRefusal"; return refusesInstall }
    override suspend fun watchdogAlive(session: TvSession, token: RunToken): Boolean { calls += "watchdogAlive"; return token in liveWatchdogs }
    override suspend fun bootId(session: TvSession): String { calls += "bootId"; return bootIdValue }
}

// Profiles from a list; resolve() is an exact model match, else None with no stock launchers.
// `homeResolver`, when set, answers the resolution that weighs the snapshot's Home (issue #114);
// otherwise that one is resolve() too.
class FakeProfileSource(
    private val profiles: List<Profile> = emptyList(),
    var resolver: (DeviceFacts, List<Profile>) -> ProfileResolution = { facts, all ->
        all.firstOrNull { facts.model in it.match.models }?.let { ProfileResolution.Exact(it) } ?: ProfileResolution.None(emptyList())
    },
    var homeResolver: ((DeviceFacts, HomeResolution?) -> ProfileResolution)? = null,
) : HomeAwareProfileSource {
    override suspend fun all(): List<Profile> = profiles
    override suspend fun resolve(facts: DeviceFacts): ProfileResolution = resolver(facts, profiles)
    override suspend fun resolve(facts: DeviceFacts, home: HomeResolution?, packages: Map<PackageName, tv.cleanyour.core.model.PackageState>?): ProfileResolution =
        homeResolver?.invoke(facts, home) ?: resolver(facts, profiles)
}

class MapFactsTable(private val facts: Map<PackageName, PackageFacts> = emptyMap()) : FactsTable {
    override fun lookup(pkg: PackageName): PackageFacts? = facts[pkg]
}

class FakeThemeSource(private val themes: List<Theme> = emptyList(), private val gallery: List<Picture> = emptyList()) : ThemeSource, PictureSource {
    override fun bundled(): List<Theme> = themes
    override fun pictures(): List<Picture> = gallery
}

// Planner that answers from a lambda and logs requests.
class FakePlanner(
    var respond: (PlanRequest, Snapshot) -> PlanResult = { _, _ -> PlanResult.Refused(listOf("fake planner: no response set")) },
) : Planner {
    val requests = CopyOnWriteArrayList<PlanRequest>()
    override fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>): PlanResult {
        requests += request
        return respond(request, snapshot)
    }
}

// Backup fakes: deterministic, readable bytes; the real formats are W6's.
class FakeRestoreBundleWriter : RestoreBundleWriter {
    val written = CopyOnWriteArrayList<Pair<Tv, Int>>()
    override suspend fun write(tv: Tv, baseline: Snapshot, changes: List<ChangeRecord>, overlay: ByteArray?, wallpaper: ByteArray?, sink: OutputStream) {
        written += tv to changes.size
        sink.write("fake restore bundle for ${tv.id.value}: ${changes.size} changes\n".encodeToByteArray())
    }
}

class FakeTvRestoreFiles : TvRestoreFiles {
    override fun restorePointJson(tv: Tv, changes: List<ChangeRecord>): ByteArray =
        """{"schema":1,"tv":"${tv.id.value}","changes":${changes.size}}""".encodeToByteArray()
    override fun restoreTxt(tv: Tv, changes: List<ChangeRecord>, profile: Profile?): ByteArray =
        ("# fake restore.txt for ${tv.nickname}\n" + changes.joinToString("") { "${it.op}\n" }).encodeToByteArray()
}

class FakeRestoreNote : RestoreNote {
    override fun text(tv: Tv, changes: List<ChangeRecord>, facts: FactsTable, stockHomeName: String, dateText: String): String =
        "${tv.nickname}, $dateText: ${changes.size} changes; home screen was $stockHomeName"
}
