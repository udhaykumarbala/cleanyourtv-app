package tv.cleanyour.core.flows

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HdmiSource
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockRanking
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.0 (RFC-LOOK-SETUP-1): Setup's look step has the Look sheet's choices (apps in a row, the
// look, the picture, a new photo), sends the same bytes the Look sheet would for the same choice,
// keeps what it sent for Manage, and a Setup run again starts from the saved choice.
class SetupLookTest {
    private val looks = BundledThemeSource()
    private val glass = looks.pictures().single { it.id == "glass" }
    private val apps = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
        "com.spotify.tv.android", "com.plexapp.android", "org.videolan.vlc").map(::PackageName)
    private val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))

    // Welcome to Found with the bundled looks, a plan that pushes the look's config.json, and a run
    // that completes and journals that push (so Done keeps what it sent).
    private suspend fun TestScope.found(h: Harness): SetupMachine {
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val on = PackageState(EnabledState.ENABLED, false, 0, null, false, false, null, false)
        h.prober.snapshotFor = { r -> Samples.snapshot(reason = r, packages = Samples.miTvPackages() + apps.associateWith { on }) }
        val previous = h.planner.respond
        h.planner.respond = { request, snap ->
            if (request is PlanRequest.Setup) PlanResult.Ok(h.plan(listOfNotNull(Op.Disable(adPkg), request.config?.let { Op.PushAsset(it, config, it.sha256) }), takeover = true))
            else previous(request, snap)
        }
        val m = SetupMachine(h.setupDeps(themes = looks), backgroundScope)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        return m
    }

    // "Make it clean": a run that completes and journals the config.json push, so Done keeps what it sent.
    private fun TestScope.makeItClean(h: Harness, m: SetupMachine) {
        h.engine.onStart = { p ->
            val i = p.steps.indexOfFirst { it.op is Op.PushAsset }
            if (i >= 0) {
                h.journal.append(JournalRecord.Begin(p.id, i, p.steps[i].op, listOf("fake"), null, 1))
                h.journal.append(JournalRecord.Done(p.id, i, 0, true, 5))
            }
            finish(p, RunStatus.COMPLETED, if (p.sections.isNotEmpty()) HomeOutcome.CONFIRMED else null)
        }
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
    }

    private fun Harness.lastSetup() = planner.requests.filterIsInstance<PlanRequest.Setup>().last()
    private fun Harness.sentDoc(): LauncherConfigDoc = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(blobs.bytes(assertNotNull(lastSetup().config)))).doc

    private fun SetupMachine.choice(): HomeChoice? = extras.value.draft?.look?.let { HomePrefs.decode(it) }

    // look2: the look step's choice is kept in WizardDraft.look (home.txt line form); apps in a row
    // is always kept, and a look of the 0.4.0 list is the draft it migrates to.
    @Test fun appsInARowAndThePictureOnlyChangeTheDraftAndALookTapResetsTheCount() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        assertEquals("default", m.extras.value.look?.themeId)
        assertEquals(StyleId.CLASSIC to LookPicture.NONE_ID, m.extras.value.look?.let { it.style to it.picture }, "Setup's Default look")
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        m.dispatch(SetupIntent.SetTheme("console")); settle()
        assertEquals(7 to "cobalt", m.extras.value.look?.let { it.columns to it.picture }, "Console's own")
        assertNull(m.extras.value.draft?.columns)
        m.dispatch(SetupIntent.Look(LookEdit.SelectColumns(6))); settle()
        assertEquals(6, m.choice()?.columns)
        assertEquals(6, m.extras.value.look?.columns)
        assertEquals(6, m.extras.value.dockColumns, "the dock sheet holds 6 a row")
        assertEquals(6, h.sentDoc().columns)
        assertEquals(1, h.sentDoc().rowsBelowDock, "7 apps at 6 in a row: the dock and one row")
        m.dispatch(SetupIntent.SelectColumns(9)); settle()
        assertEquals(6, m.choice()?.columns, "9 is not offered")
        m.dispatch(SetupIntent.SelectColumns(7)); settle()
        assertEquals(7, m.choice()?.columns, "always kept")
        m.dispatch(SetupIntent.SelectColumns(5)); settle()
        m.dispatch(SetupIntent.Look(LookEdit.SelectPicture("glass"))); settle()
        assertEquals("glass", m.choice()?.picture)
        assertEquals("glass", m.extras.value.look?.picture)
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        assertEquals(true, h.sentDoc().wallpaper)
        assertEquals(5, h.sentDoc().columns)
        assertEquals(false, h.sentDoc().fillDock)
        // A style keeps the picture and apps in a row (they belong to nobody).
        m.dispatch(SetupIntent.Look(LookEdit.SelectStyle(StyleId.GLASS))); settle()
        assertEquals(Triple("glass", "glass", 5), m.choice()?.let { Triple(it.style, it.picture, it.columns) })
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        m.dispatch(SetupIntent.Look(LookEdit.SelectPicture(LookPicture.NONE_ID))); settle()
        assertEquals(false, h.sentDoc().wallpaper)
        assertNull(h.lastSetup().wallpaper)
        // The draft is saved with the TV, so a Setup continued later has the same choice.
        assertEquals(m.extras.value.draft, h.tvs.tvs.value.single().wizard)
    }

    // The bytes Setup sends equal what the Look sheet sends for the same choice, and after Setup the
    // Look sheet opens on that choice.
    @Test fun setupSendsTheLookSheetsBytesAndTheLookSheetOpensOnTheSameChoice() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        m.dispatch(SetupIntent.SetTheme("console")); settle()
        m.dispatch(SetupIntent.SelectColumns(6)); settle()
        m.dispatch(SetupIntent.SelectPicture("glass")); settle()
        val setup = h.lastSetup()
        val setupBytes = h.blobs.bytes(assertNotNull(setup.config))
        // The emulator check pushes these bytes the way FileOverlayTransport does (LookBytesDumpTest).
        java.io.File("build/look-bytes/setup-console-6-glass").apply {
            deleteRecursively(); mkdirs()
            java.io.File(this, "config.json").writeBytes(setupBytes)
            java.io.File(this, "wallpaper.jpg").writeBytes(assertNotNull(BundledThemeSource.bytes(assertNotNull(setup.wallpaper))))
        }
        val inSetup = assertNotNull(m.extras.value.look)
        makeItClean(h, m)
        val tv = h.tvs.tvs.value.single()
        assertEquals(sha256Of(setupBytes), tv.launcher?.ourOverlayDigest)
        val saved = assertNotNull(HomePrefs(h.documents).load(tv.id))
        assertEquals(listOf<Any?>(apps, false, "classic", "glass", 6, "quick", false), listOf(saved.dock, saved.cards, saved.style, saved.picture,
            saved.columns, saved.movement, saved.lift), "the new form, the migrated Console on a 1 GB TV (Quick, Lift and zoom off)")

        val home = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = looks), backgroundScope, tv.id)
        settle()
        assertEquals(6, home.state.value.columns)
        home.dispatch(HomeScreenIntent.OpenLook); settle()
        val draft = assertNotNull(home.state.value.look)
        assertEquals(listOf<Any?>(inSetup.style, inSetup.picture, inSetup.columns, inSetup.movement, inSetup.lift, inSetup.details, inSetup.fullFile),
            listOf(draft.style, draft.picture, draft.columns, draft.movement, draft.lift, draft.details, draft.fullFile), "the same choice as Setup's look step")
        assertEquals(WayBack.EARLIER, draft.wayBack, "Setup's Default look is the earlier look")
        // 0.4.8 (beta1 QA): the TV has this choice, so the sheet offers no "Put it on the TV" and a send does nothing.
        assertFalse(draft.canSend, "nothing changed since Setup sent it")
        val pushes = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size
        home.dispatch(HomeScreenIntent.UseLook); settle()
        assertEquals(pushes, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size, "an unchanged draft is not sent")
        assertNotNull(home.state.value.look, "the sheet stays open")
    }

    // 0.4.7: every push names the TV's Live TV app (Samples' com.android.tv), Setup's and each of
    // Manage's after it, since the look's doc is built from scratch each time (LookKeys.build); and
    // fill_dock follows who made the dock on every push: true for the dock Setup picked, false from
    // the moment the person sets one in the Dock sheet, kept in home.txt for the pushes after it.
    @Test fun everyPushAfterSetupNamesTheLiveTvAppAndFillsOnlyAPickedDock() = runTest(timeout = 30.seconds) {
        val live = "com.android.tv"
        val h = Harness()
        val m = found(h)
        assertEquals(listOf<Any?>(live, true), h.sentDoc().let { listOf(it.liveTvPackage, it.fillDock) }, "Setup, the picked dock")
        makeItClean(h, m)
        val tv = h.tvs.tvs.value.single()
        assertEquals(false, HomePrefs(h.documents).load(tv.id)?.dockChosen)
        assertTrue("\ndockchosen=false\n" in assertNotNull(h.documents.read("tvs/${tv.id.value}/home.txt") { it.decodeToString() }))
        val home = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = looks), backgroundScope, tv.id)
        settle()
        fun pushed(): LauncherConfigDoc = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(
            h.blobs.bytes(h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().config))).doc
        fun check(fill: Boolean, what: String) = assertEquals(listOf<Any?>(live, fill), pushed().let { listOf(it.liveTvPackage, it.fillDock) }, what)
        home.dispatch(HomeScreenIntent.OpenLook); settle()
        home.dispatch(HomeScreenIntent.Look(LookEdit.SelectStyle(StyleId.GLASS))); settle()
        home.dispatch(HomeScreenIntent.UseLook); settle()
        check(true, "a look change after Setup")
        home.dispatch(HomeScreenIntent.RestoreMine); settle()
        check(true, "Restore mine")
        home.dispatch(HomeScreenIntent.SetDock(apps.take(2))); settle()
        check(false, "a dock the person set")
        assertEquals(true, HomePrefs(h.documents).load(tv.id)?.dockChosen)
        home.dispatch(HomeScreenIntent.OpenLook); settle()
        home.dispatch(HomeScreenIntent.Look(LookEdit.SelectStyle(StyleId.CLASSIC))); settle()
        home.dispatch(HomeScreenIntent.UseLook); settle()
        check(false, "a look change after the person's dock")
        home.dispatch(HomeScreenIntent.RestoreMine); settle()
        check(false, "Restore mine after the person's dock")
    }

    // A photo made in the look step is sent with its dim when Setup runs, and kept for the TV (as
    // "Restore mine" with that dim) only once the TV has it.
    // 0.4.8 (issue #1902, a Vestel set with a brand set): the default dock took its Live TV app from the profile only,
    // so a TV with no profile got no Live TV tile though LiveTvKeys found the app. Now it gets the one LiveTvKeys finds;
    // a profiled TV still gets its profile's (the harness profile names none, so none).
    @Test fun aTvWithoutAProfileGetsItsLiveTvAppInTheDefaultDock() = runTest(timeout = 30.seconds) {
        val live = PackageName("com.android.tv")
        suspend fun asked(brandSet: Boolean): Pair<PackageName?, List<PackageName>?> {
            val h = Harness()
            if (brandSet) h.profiles.resolver = { _, _ -> ProfileResolution.BrandSureSet("Xiaomi", emptyList(), emptyList()) }
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r -> Samples.snapshot(reason = r).let { it.copy(hdmi = listOf(HdmiSource(1, null, "hdmi1", false))) }.let { it.copy(contentHash = SnapshotCodec.hash(it)) } }
            val picked = mutableListOf<PackageName?>()
            val m = SetupMachine(h.setupDeps(themes = looks, dock = DockPicker { p, i, hdmi, l, loc -> picked += l; DockRanking.pick(p, i, hdmi, l, loc) }), backgroundScope)
            h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
            settle()
            m.dispatch(SetupIntent.Start)
            settle()
            h.discovery.open5555[HOST] = true
            settle(2_100)
            settle(700)
            assertIs<SetupState.Found>(m.state.value)
            return picked.lastOrNull() to h.planner.requests.filterIsInstance<PlanRequest.Setup>().lastOrNull()?.choices?.dock
        }
        val (brandLive, brandDock) = asked(brandSet = true)
        assertEquals(live, brandLive)
        assertEquals(live, brandDock?.last(), brandDock.toString())
        val (profiledLive, profiledDock) = asked(brandSet = false)
        assertNull(profiledLive, "a profiled TV keeps its profile's (none)")
        assertFalse(live in profiledDock.orEmpty(), profiledDock.toString())
    }

    @Test fun aPhotoMadeInSetupIsSentThenKeptForTheTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = found(h)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(2000) { it.toByte() }
        m.dispatch(SetupIntent.SetOwnPicture(jpeg, 40)); settle()
        assertEquals(LookPicture.NEW_ID, m.extras.value.look?.picture)
        assertEquals(LookPicture.NEW_ID, m.choice()?.picture)
        assertEquals(sha256Of(jpeg), m.extras.value.newPicture?.sha256)
        assertEquals(40, m.extras.value.newDim)
        assertEquals(sha256Of(jpeg), h.lastSetup().wallpaper?.sha256)
        assertEquals(40, h.sentDoc().wallpaperDim)
        val id = h.tvs.tvs.value.single().id
        assertNull(h.documents.read(OwnPicture.path(id)) { it }, "nothing kept before the TV has it")
        makeItClean(h, m)
        assertContentEquals(jpeg, h.documents.read(OwnPicture.path(id)) { it })
        val saved = assertNotNull(HomePrefs(h.documents).load(id))
        assertEquals(LookPicture.MINE_ID, saved.picture)
        assertEquals(40, saved.dim)
    }

    // Running Setup again starts from the saved choice (the look, its apps in a row and picture), so
    // it no longer clears the saved count.
    @Test fun setupAgainStartsFromTheSavedChoice() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.tvs.upsert(Samples.tv())
        HomePrefs(h.documents).save(Samples.miTvId(), HomeChoice(apps, "console", cards = false, picture = "glass", columns = 6))
        val m = found(h)
        val draft = assertNotNull(m.extras.value.draft)
        assertEquals("console", draft.themeId)
        assertEquals(6, draft.columns)
        assertEquals("glass", draft.picture)
        assertEquals(6, h.sentDoc().columns)
        assertEquals(glass.wallpaper, h.lastSetup().wallpaper)
        assertTrue(m.extras.value.look?.let { it.columns == 6 && it.picture == "glass" } == true)
    }

    // look2: a Setup run again on a TV whose saved choice is in the new form starts from it.
    @Test fun setupAgainStartsFromASavedLook2Choice() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.tvs.upsert(Samples.tv())
        HomePrefs(h.documents).save(Samples.miTvId(), HomeChoice(apps, null, cards = false, picture = "neon", columns = 6, style = "glass",
            movement = "smooth", lift = false, names = true, details = LookDetails(ring = false)))
        val m = found(h)
        val look = assertNotNull(m.extras.value.look)
        assertEquals(listOf<Any?>(StyleId.GLASS, "neon", 6, tv.cleanyour.core.spec.Movement.SMOOTH, true, LookDetails(ring = false)),
            listOf(look.style, look.picture, look.columns, look.movement, look.names, look.details))
        assertEquals("glass", m.choice()?.style)
        assertEquals(6, h.sentDoc().columns)
        assertEquals("false", h.sentDoc().unknown["app_glow"]?.toString(), "the ring stays off")
    }

    // The draft's look choice survives a restart with the TV record.
    @Test fun theDraftKeepsItsLookChoiceOnDisk() {
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), apps, "console", false, null, 5, columns = 6, picture = "glass"))
        assertEquals(tv, TvJson.decode(TvJson.encode(tv)))
        val old = TvJson.encode(tv.copy(wizard = tv.wizard?.copy(columns = null, picture = null))).decodeToString()
        assertEquals(tv.wizard?.copy(columns = null, picture = null), TvJson.decode(old.replace(",\"columns\":null,\"picture\":null,\"look\":null", "").encodeToByteArray()).wizard,
            "a draft written before 0.4.0 still loads")
        // look2: the look step's choice in home.txt line form.
        val look2 = tv.copy(wizard = tv.wizard?.copy(look = HomePrefs.encode(HomeChoice(emptyList(), null, picture = "neon", columns = 6, style = "glass"))))
        assertEquals(look2, TvJson.decode(TvJson.encode(look2)))
    }
}
