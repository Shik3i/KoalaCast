package net.koalastuff.koalacast.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Everything that is identical for the app module and every library module:
 * SDK levels, Java 17 and the compiler flags we want project-wide.
 */
internal fun Project.configureKotlinAndroid(extension: ApplicationExtension) {
    extension.apply {
        compileSdk = libs.int("compileSdk")

        defaultConfig {
            minSdk = libs.int("minSdk")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = false
        }

        lint {
            abortOnError = true
            checkDependencies = true
            disable.addAll(ignoredDependencyChecks)
        }
    }

    configureKotlinCompiler()
}

internal fun Project.configureKotlinAndroid(extension: LibraryExtension) {
    extension.apply {
        compileSdk = libs.int("compileSdk")

        defaultConfig {
            minSdk = libs.int("minSdk")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = false
        }

        lint {
            abortOnError = true
            checkDependencies = true
            disable.addAll(ignoredDependencyChecks)
        }
    }

    configureKotlinCompiler()
}

private fun Project.configureKotlinCompiler() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}

// Dependency freshness is a deliberate, reviewed decision — see
// gradle/libs.versions.toml — not something a build should fail on.
private val ignoredDependencyChecks =
    setOf(
        "NewerVersionAvailable",
        "GradleDependency",
        "AndroidGradlePluginVersion",
    )
