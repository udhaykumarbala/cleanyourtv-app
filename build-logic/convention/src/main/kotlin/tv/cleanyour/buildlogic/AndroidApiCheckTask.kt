package tv.cleanyour.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

// checkAndroidApi: fails when a JVM module's main classes use a platform class, field or method
// that Android does not have at minSdk (see AndroidApiCheck.kt). Part of every shipped JVM
// module's `check`.
@CacheableTask
abstract class AndroidApiCheckTask : DefaultTask() {
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    // Only read to follow a class up to the platform class it extends.
    @get:Classpath
    abstract val hierarchy: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apiVersions: RegularFileProperty

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val xml = apiVersions.orNull?.asFile?.takeIf { it.isFile }
            ?: throw GradleException(
                "checkAndroidApi needs the Android SDK's platforms/android-N/data/api-versions.xml: " +
                    "set sdk.dir in local.properties or ANDROID_HOME",
            )
        val db = xml.inputStream().buffered().use { ApiDatabase.parse(it) }
        val own = AndroidApiChecker.classIndex(classes.files)
        val deps = AndroidApiChecker.classIndex(hierarchy.files)
        val cache = HashMap<String, ClassInfo?>()
        val lookup: (String) -> ClassInfo? = { n ->
            cache.getOrPut(n) { (own[n] ?: deps[n])?.let { ClassFileReader.read(it()) } }
        }
        val checker = AndroidApiChecker(db, minSdk.get(), lookup)
        val problems = own.keys.sorted().flatMap { n -> checker.check(lookup(n)!!) }.distinct()
        val f = report.get().asFile
        f.parentFile.mkdirs()
        f.writeText(problems.joinToString("\n", postfix = if (problems.isEmpty()) "" else "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkAndroidApi: ${problems.size} use(s) of Java APIs Android lacks at API ${minSdk.get()} " +
                    "(the app's minSdk):\n" + problems.joinToString("\n") { "  $it" },
            )
        }
        logger.lifecycle("checkAndroidApi: ${own.size} classes, nothing above API ${minSdk.get()} (${xml.parentFile.parentFile.name})")
    }
}
