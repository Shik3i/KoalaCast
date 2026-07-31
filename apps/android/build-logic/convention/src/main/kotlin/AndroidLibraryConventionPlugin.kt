import net.koalastuff.koalacast.buildlogic.configureKotlinAndroid
import net.koalastuff.koalacast.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testOptions.unitTests.isReturnDefaultValues = true
        }

        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            // Every library module is given an instrumentation runner above, so
            // `connectedDebugAndroidTest` builds a test APK for all of them — and
            // the ones with no androidTest sources died with ClassNotFoundException
            // because the runner itself was never on their classpath. That made the
            // aggregate task unusable repo-wide, not just for the empty modules.
            add("androidTestImplementation", libs.findLibrary("androidx-test-runner").get())
        }
    }
}
