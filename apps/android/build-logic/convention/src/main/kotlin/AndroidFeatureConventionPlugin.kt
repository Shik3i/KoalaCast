import net.koalastuff.koalacast.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * A `feature:*` module is always: an Android library + Compose + Hilt, wired to the
 * shared model/data/ui modules. Screens depend on this plugin and nothing else.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("koalacast.android.library")
        pluginManager.apply("koalacast.android.compose")
        pluginManager.apply("koalacast.android.hilt")

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:ui"))

            add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            add("implementation", libs.findLibrary("coil-compose").get())
        }
    }
}
