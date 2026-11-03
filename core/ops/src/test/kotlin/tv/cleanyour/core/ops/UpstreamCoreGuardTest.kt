package tv.cleanyour.core.ops

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.9: upstream's core guard (tools/disable.sh refuses a whole list that names a CORE_RE package that
// is not CORE_OK). The Denylist protects every such package, by name or prefix, whatever a list,
// a candidate list, a brand set, a takeover quirk or a never-disable list says.
class UpstreamCoreGuardTest {
    private val guarded = listOf(
        "com.android.sdksandbox", "android.ext.services", "android.ext.shared",
        "com.android.ext.services", "com.google.android.ext.services", "com.android.ext.shared", "com.google.android.ext.shared",
        "com.android.modulemetadata", "com.google.android.modulemetadata",
        "com.google.android.overlay.modules.permissioncontroller", "com.google.android.overlay.modules.ext.services",
        "com.android.providers.downloads", "com.android.providers.downloads.ui", "com.android.providers.settings",
        "com.android.providers.tv", "com.android.providers.media.module", "com.android.providers.partnerbookmarks",
        "com.android.phone", "com.android.location.fused",
        "com.cltv.mal", "com.mediatek.tis", "com.mediatek.tv.service", "com.mediatek.tv.agent",
    )
    private val coreOk = listOf(
        "com.android.providers.calendar", "com.android.providers.contacts",
        "com.android.providers.userdictionary", "com.android.providers.blockednumber",
    )
    private val lookalikes = listOf(
        "com.android.providersx", "com.android.phone.overlay", "com.mediatek.tv.services", "com.mediatek.tisx",
        "com.cltv.mal.overlay", "com.google.android.overlay.module", "com.android.sdksandboxes", "com.mediatek.wwtv.tvcenter",
    )
    private val takeover = Profile(
        "test", ProfileMatch(emptyList(), emptyList(), null, null, null, null), Platform.ANDROID_TV, listOf(Samples.stockLauncher),
        null, null, setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH, Quirk.EXTRA_HOME_CLAIMERS), emptyList(), emptySet(), emptyList(), null, Sha256("0".repeat(64)),
    )

    @Test fun everyGuardedPackageIsProtected() {
        for (name in guarded) {
            assertNotNull(Denylist.staticReason(name), name)
            assertNotNull(Protected.isProtected(PackageName(name), null, takeover), name)
        }
    }

    @Test fun theFourDataProvidersStayOpenAsUpstreamAllows() {
        for (name in coreOk) assertNull(Denylist.staticReason(name), name)
    }

    @Test fun lookalikesAreNotCaught() {
        for (name in lookalikes) assertNull(Denylist.staticReason(name), name)
    }

    @Test fun reasonsArePlainWords() {
        for (name in guarded) {
            val reason = Denylist.staticReason(name)!!
            assertTrue(reason.startsWith("it "), "$name: $reason")
            assertTrue("—" !in reason && "--" !in reason, "$name: $reason")
        }
    }

    // Drift: the Denylist covers upstream's own guard at the pinned commit. Every sample name that
    // CORE_RE refuses (and CORE_OK does not let through) has a static reason here.
    @Test fun theDenylistCoversThePinnedCoreGuard() {
        val script = File(Fixtures.repoRoot, "third_party/cleanyourtv/tools/disable.sh").readText()
        fun pattern(name: String) = Regex(Regex("^$name='([^']*)'", RegexOption.MULTILINE).find(script)!!.groupValues[1])
        val coreRe = pattern("CORE_RE")
        val coreOkRe = pattern("CORE_OK")
        val samples = guarded + coreOk + lookalikes + listOf(
            "android", "com.android.systemui", "com.android.shell", "com.android.settings", "com.android.tv.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
        var refused = 0
        for (name in samples) {
            if (coreRe.matches(name) && !coreOkRe.matches(name)) {
                refused++
                assertNotNull(Denylist.staticReason(name), "upstream refuses $name, the Denylist must too")
            }
        }
        assertTrue(refused >= guarded.size, "the sample names reach upstream's guard ($refused)")
    }
}
