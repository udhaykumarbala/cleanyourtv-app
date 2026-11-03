package tv.cleanyour.core.flows

import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LiveTvKeys
import tv.cleanyour.core.spec.TvSettings
import tv.cleanyour.core.store.DocumentStore

// What a Manage push adds to a look's doc on this TV, after the look itself: the Home button
// helper's keys it keeps (HomeHelperFlows.carry), then the TV's Live TV app and no placed app hidden
// (LiveTvKeys), from the newest snapshot and the TV's profile. Without a snapshot the Live TV app is
// not known, so live_tv_package is not written; the hidden list still never hides a placed app.
// Setup does the same from its own snapshot (SetupMachine.replan).
//
// 0.5.0 F0: the look goes into the TV's settings document (TvSettings), seeded from the config.json
// the TV has now (SettingsSeed), and the push sends the whole document: the look push writes its own
// sections (TvSettings.LOOK_PUSH) and keeps every key of the others, less the keys the TV's launcher
// does not read. With no file on the TV that is the 0.4.8 bytes.
internal object LookCarry {
    suspend fun of(d: ManageDeps, tvId: TvId): (LauncherConfigDoc) -> LauncherConfigDoc {
        val helper = HomeHelperFlows.carry(d, tvId)
        val snap = attempt { d.snapshots.latest(tvId) }.getOrNull()
        val profile = d.tv(tvId)?.let { attempt { d.profileOf(it) }.getOrNull() }
        val liveTv = snap?.let { LiveTvKeys.pick(profile, it.packages) }
        val overlay = attempt { SettingsSeed.overlay(d, tvId, snap) }.getOrElse { snap?.launcherOverlay }
        val settings = TvSettings.seed(overlay, snap?.packages?.get(Launcher.pkg)?.versionCode)
        return { doc -> merge(settings, doc, liveTv, helper) }
    }

    // The look push on a TV's document: the look's sections from `built`, then the rules of every push,
    // the Home button helper's keys (`helper`) and the Live TV app with no placed app hidden
    // (LiveTvKeys). The hidden list is the Apps section's, so the TV's own stays; a 0.4.0 theme's list
    // (Default writes the launcher's own) only stands in for none. A list that holds part of the
    // launcher's own hidden apps and nothing else is the Live TV rule's (an earlier push took a placed
    // app out of it), so it is worked out again: the Live TV app that is no longer placed goes off the
    // grid, as in 0.4.8. An empty list, or any other, is the person's (or a theme's) and only loses the
    // placed apps.
    fun merge(settings: TvSettings, built: LauncherConfigDoc, liveTv: PackageName?, helper: (LauncherConfigDoc) -> LauncherConfigDoc): LauncherConfigDoc {
        val doc = settings.withSections(built.copy(hiddenApps = null), TvSettings.LOOK_PUSH).doc
        val launcherList = LauncherConfigCodec.builtInDefaults.hiddenApps
        val ruleMade = doc.hiddenApps?.let { l -> l.isNotEmpty() && l.size < launcherList.size && l.all { it.value in launcherList } } == true
        val hidden = if (doc.hiddenApps == null || ruleMade) built.hiddenApps else doc.hiddenApps
        val ruled = LiveTvKeys.apply(helper(doc.copy(hiddenApps = hidden)), liveTv)
        return TvSettings.of(ruled, settings.versionCode).pushDoc()
    }
}

// The config.json the settings document starts from. The newest snapshot's read of the TV, unless
// this app changed the file since (a ChangeRecord of config.json newer than the read: the engine's
// own read is taken before a run's steps): then what this phone pushed last (PushedSettings) when
// that is the file the record names, none when the record removed it, else a fresh read while the
// TV is connected, and the snapshot's read when nothing better is at hand.
internal object SettingsSeed {
    suspend fun overlay(d: ManageDeps, tvId: TvId, snap: Snapshot?): ByteArray? {
        val last = d.ledger.changes(tvId).filter { isConfig(it.op) }.maxByOrNull { it.atMs }
        if (last == null || (snap != null && snap.takenAtMs >= last.atMs)) return snap?.launcherOverlay
        when (val op = last.op) {
            is Op.RemoveFile -> return null
            is Op.PushAsset -> PushedSettings.load(d.documents, tvId)?.takeIf { sha256Of(it) == op.sha256 }?.let { return it }
            else -> {}
        }
        val session = d.access.live(tvId) ?: return snap?.launcherOverlay
        return d.runner.freshSnapshot(session).launcherOverlay
    }

    private fun isConfig(op: Op): Boolean {
        val path = when (op) { is Op.PushAsset -> op.dest; is Op.RemoveFile -> op.path; else -> return false }
        return path.dir == RemoteDir.OVERLAY && path.segments == listOf("config.json")
    }
}

// The whole config.json this phone last pushed to a TV, kept so the next push can start from it
// before the TV is read again (SettingsSeed). One file per TV; Forget and Delete everything remove
// the TV's folder with it.
internal object PushedSettings {
    fun path(id: TvId) = "tvs/${id.value}/pushed-config.json"

    suspend fun remember(docs: DocumentStore, id: TvId, bytes: ByteArray) = docs.write(path(id), bytes)

    suspend fun load(docs: DocumentStore, id: TvId): ByteArray? = docs.read(path(id)) { it }
}
