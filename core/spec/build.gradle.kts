plugins {
    id("cleanyourtv.jvm")
    id("cleanyourtv.serialization")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":testing:fixtures"))
}

// The upstream launcher repository, pinned by W1 (DECISIONS-W1.md, D-W1-8).
val upstream = rootProject.layout.projectDirectory.dir("third_party/cleanyourtv")
val upstreamCommit = "0cb195a"

// D28: profiles are converted at build time from the pinned upstream examples and shipped as
// data (profiles.json, with the D26 sure sets). The converter is this module's own code, run
// from its compiled classes; profile-meta.json is passed as a file so the task does not depend
// on the resources it feeds.
val generatedProfiles = layout.buildDirectory.dir("generated/profiles")
// Test build only: -Pcyt.candidates=true (or cyt.candidates=true in gradle.properties) adds the
// candidate lists in candidates/ (not tested yet, offered only after the person agrees). Off by
// default: profiles.json is then byte for byte the release one.
val candidatesOn = providers.gradleProperty("cyt.candidates").map { it.toBoolean() }.getOrElse(false)
val candidateDir = layout.projectDirectory.dir("candidates")
val convertProfiles by tasks.registering(JavaExec::class) {
    description = "Converts upstream examples/*/debloat.txt into profiles.json"
    val meta = layout.projectDirectory.file("src/main/resources/tv/cleanyour/core/spec/profile-meta.json")
    // D2: upstream folders without a curated entry, skipped on purpose (ProfilesTest pins the list).
    val unconverted = layout.projectDirectory.file("upstream-feedback/unconverted.txt")
    val examples = upstream.dir("examples")
    inputs.dir(examples).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(meta).withPathSensitivity(PathSensitivity.RELATIVE)
    // The careful brand sets (D26 amendment) read the categories in facts.tsv, beside profile-meta.json.
    inputs.file(layout.projectDirectory.file("src/main/resources/tv/cleanyour/core/spec/facts.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(unconverted).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("candidates", candidatesOn)
    if (candidatesOn) inputs.dir(candidateDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedProfiles)
    classpath(sourceSets.main.map { it.output.classesDirs }, configurations.named("runtimeClasspath"))
    mainClass.set("tv.cleanyour.core.spec.ProfileConverter")
    maxHeapSize = "256m"
    args(examples.asFile.absolutePath, meta.asFile.absolutePath, upstreamCommit, generatedProfiles.get().asFile.absolutePath, unconverted.asFile.absolutePath)
    if (candidatesOn) args(candidateDir.asFile.absolutePath, candidateDir.file("candidate-meta.json").asFile.absolutePath)
}

// The three bundled themes, from the pinned upstream themes/ folder.
val bundleThemes by tasks.registering(Sync::class) {
    from(upstream.dir("themes")) {
        include("default/config.json", "aurora/config.json", "aurora/wallpaper.jpg", "six-columns/config.json")
        into("tv/cleanyour/core/spec/themes")
    }
    into(layout.buildDirectory.dir("generated/themes"))
}

sourceSets.main {
    resources.srcDir(convertProfiles)
    resources.srcDir(bundleThemes)
}

// Differential test (F8): the real readers run on the JVM from the test sources, vendored with only
// the package line changed. Launcher 3.0 (b06ab21, the bundled launcher and the pinned reader,
// RFC-UP300-1) in src/test/java/com/gathin/tvhome/v30, checked against the submodule, and launcher
// 2.7 (v2.7.0, 3e34f40) in .../v27, frozen by digest in LookDifferentialTest, with stubs for the
// Android types they call; launcher 2.5 (v2.5.0, 9071ec8) in .../pinned (RFC-UP037-1)
// and 2.0 (1bbf0c9) in .../previous, kept for the keys they read. Nothing is generated here.
