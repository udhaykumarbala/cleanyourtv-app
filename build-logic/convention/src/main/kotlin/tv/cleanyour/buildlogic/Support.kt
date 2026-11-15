package tv.cleanyour.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

// System property every JVM and Robolectric test gets, so :testing:fixtures can find the repo's
// fixtures/ folder and the upstream submodule without guessing the working directory.
const val repoRootProperty = "cleanyourtv.repoRoot"

object Toolchain {
    // D10 says 36; Compose BOM 2026.09.00 (D34) requires compileSdk 37 in its AAR metadata, so
    // compileSdk is 37 while targetSdk stays 36 (DECISIONS-W1.md, D-W1-3).
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 26
    // RFC-CURTAIN-1: the TV curtain runs on every TV the phone app supports (Android 8 and later).
    const val TV_MIN_SDK = 26
    val JAVA = JavaVersion.VERSION_21
}

// :feature:setup -> tv.cleanyour.feature.setup; hyphens are not valid in package names.
fun Project.cytNamespace(): String = "tv.cleanyour." + path.removePrefix(":").replace(':', '.').replace('-', '_')
