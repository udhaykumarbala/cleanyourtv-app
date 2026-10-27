
plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:spec"))
    api(project(":core:ops"))
    testImplementation(project(":testing:fixtures"))
}

// Test build only: -Pcyt.wizardStaysAnyBrand=true (or cyt.wizardStaysAnyBrand=true in gradle.properties)
// widens R0 (HomeSwitchRisk.wizardStaysOnSafely, 0.4.6) from Xiaomi to every brand, so volunteers can
// check reboot survival on the other brands' sets with the same Home claimants before a release
// takes them. The switch is carried as a bundled marker file. Off by default: no file is generated,
// so the module's resources are byte for byte the release ones.
val wizardStaysAnyBrand = providers.gradleProperty("cyt.wizardStaysAnyBrand").map { it.toBoolean() }.getOrElse(false)
val generatedTestBuild = layout.buildDirectory.dir("generated/testBuild")
val testBuildSwitches by tasks.registering {
    description = "Writes the test build's planner switches (cyt.wizardStaysAnyBrand) as bundled data"
    val on = wizardStaysAnyBrand
    val out = generatedTestBuild
    inputs.property("wizardStaysAnyBrand", on)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        if (on) File(dir, "tv/cleanyour/core/planner").apply { mkdirs() }.resolve("wizard-stays-any-brand").writeText("true\n")
    }
}
sourceSets.main { resources.srcDir(testBuildSwitches) }
// HomeSwitchRiskTest checks that the bundled switch is the one this build asked for.
tasks.withType<Test>().configureEach { systemProperty("cyt.wizardStaysAnyBrand", wizardStaysAnyBrand.toString()) }

// A hung coroutine or a deadlocked run must fail the build, not stall it.
