plugins {
    id("cleanyourtv.android.app")
    id("cleanyourtv.serialization")
}

android {
    // The app's version, in one place for every store flavour and for F-Droid's checkupdates
    // (STORES-PLAN 0 and 3): GitHub, F-Droid and Google Play ship the same versionCode and versionName.
    defaultConfig {
        // Test build for volunteers (a GitHub pre-release, tag v0.4.5-beta1, which F-Droid's
        // ^v[0-9.]+$ never matches and the update check never offers): code 16 installs over 0.4.0 (15)
        // and every earlier beta (0.3.8-beta2 (14), 0.3.8-beta1 (13), 0.3.6-beta1 (10)) with the same
        // key; the next release, 0.4.5, takes code 17.
        versionCode = 16
        versionName = "0.4.5-beta1"
    }
    // STORES-PLAN 1: one codebase, two store builds, the same applicationId and signing certificate.
    // `full` (GitHub and F-Droid) checks GitHub for a newer release and offers the optional add-ons;
    // `play` (Google Play) has no update check, no add-on downloads, keeps the TV's software updaters
    // on and names no GitHub host. The differences live in src/full and src/play (Store.kt and the
    // network security config) and nowhere else.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            isDefault = true
        }
        create("play") {
            dimension = "store"
        }
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    // A text report per linted variant (build/reports/lint-results-<variant>.txt). No shared
    // textOutput: with two linted variants (fullDebug under `lint`, playDebug under `check`) their
    // report copies raced for one file and failed `check` at random (RF-STORES-030 M3).
    lint {
        textReport = true
    }
}

dependencies {
    implementation(project(":feature:connect"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:manage"))
    implementation(project(":feature:launcher"))
    implementation(project(":feature:modules"))
    implementation(project(":feature:backup"))
    implementation(project(":ui:design"))
    implementation(project(":core:flows"))
    implementation(project(":android:work"))
    implementation(project(":android:platform"))
    implementation(project(":adb:session"))
    // T7: the real implementations behind Seams (layer 9 may use every lower layer).
    implementation(project(":adb:wire"))
    implementation(project(":core:ops"))
    implementation(project(":core:spec"))
    implementation(project(":core:probe"))
    implementation(project(":core:planner"))
    implementation(project(":core:engine"))
    implementation(project(":core:backup"))
    implementation(project(":core:trust"))
    implementation(project(":core:registry"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// :benchmark measures the full build (it asks for store=full); the play benchmark variant is not
// built. Unit tests run on both flavours (testFullDebugUnitTest and testPlayDebugUnitTest under
// `check`); the benchmark build type is release code with the debug key and gets no unit tests.
androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        if (variant.productFlavors.contains("store" to "play")) variant.enable = false
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

// Lint runs on the default flavour (full) with `lint`; the play sources and resources too.
tasks.named("check") { dependsOn("lintPlayDebug") }

// The size gate for every store build: the full APK and the play APK (its AAB holds the same code).
tasks.register("checkApkSizeRelease") {
    group = "verification"
    description = "Fails when the fullRelease or the playRelease APK is larger than the size gate."
    dependsOn("checkApkSizeFullRelease", "checkApkSizePlayRelease")
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "1280m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
