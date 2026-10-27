import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import tv.cleanyour.buildlogic.AndroidApiCheckTask
import tv.cleanyour.buildlogic.AndroidApiChecker
import tv.cleanyour.buildlogic.ModuleRules
import tv.cleanyour.buildlogic.Toolchain
import tv.cleanyour.buildlogic.libs
import tv.cleanyour.buildlogic.repoRootProperty
import java.io.File
import java.util.Properties

// Pure Kotlin JVM module: no android.jar on the classpath (ARCHITECTURE D5, rule 2).
class JvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JDK)
            // Explicit API mode is left off: section 5 is copied verbatim without visibility
            // modifiers (DECISIONS-W1.md, D-W1-6).
        }
        dependencies {
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("kotlin-test-junit").get())
        }
        tasks.withType<Test>().configureEach {
            useJUnit()
            systemProperty(repoRootProperty, rootDir.absolutePath)
            // A hung test fails after 5 minutes instead of holding the shared forge build lock.
            timeout.set(java.time.Duration.ofMinutes(5))
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
        if (path !in ModuleRules.testOnlyModules) registerAndroidApiCheck()
    }

    // These classes ship in the APK but Android lint never sees them, so check them against the
    // platform API at the app's minSdk (AndroidApiCheck.kt).
    private fun Project.registerAndroidApiCheck() {
        val main = extensions.getByType<SourceSetContainer>().getByName("main")
        val localProps = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
        val sdkDir = localProps.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("sdk.dir").orEmpty()
        }.filter { it.isNotBlank() }
            .orElse(providers.environmentVariable("ANDROID_HOME"))
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
        val apiCheck = tasks.register<AndroidApiCheckTask>("checkAndroidApi") {
            group = "verification"
            description = "Fails when main classes use a Java API that Android lacks at minSdk ${Toolchain.MIN_SDK}."
            classes.from(main.output.classesDirs)
            hierarchy.from(configurations.getByName("runtimeClasspath"))
            minSdk.set(Toolchain.MIN_SDK)
            apiVersions.fileProvider(sdkDir.map { AndroidApiChecker.apiVersionsIn(File(it), Toolchain.COMPILE_SDK) })
            report.set(layout.buildDirectory.file("reports/checkAndroidApi.txt"))
        }
        tasks.named("check") { dependsOn(apiCheck) }
    }

    companion object {
        const val JDK = 21
    }
}
