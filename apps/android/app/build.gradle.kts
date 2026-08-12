plugins {
    id("koalacast.android.application")
    id("koalacast.android.compose")
    id("koalacast.android.hilt")
}

val releaseKeystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val explicitReleasePackagingRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':') in setOf("assembleRelease", "bundleRelease", "packageRelease")
}
check(!explicitReleasePackagingRequested || hasReleaseSigning) {
    "Release packaging requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD"
}

android {
    namespace = "net.koalastuff.koalacast"

    defaultConfig {
        applicationId = "net.koalastuff.koalacast"
        versionCode = 41
        versionName = "0.11.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("production") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("production")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val missingSigningVariables = listOf(
            "ANDROID_KEYSTORE_FILE",
            "ANDROID_KEYSTORE_PASSWORD",
            "ANDROID_KEY_ALIAS",
            "ANDROID_KEY_PASSWORD",
        ).filter { System.getenv(it).isNullOrBlank() }
        check(missingSigningVariables.isEmpty()) {
            "Release packaging requires ${missingSigningVariables.joinToString(", ")}"
        }
    }
}

// Fail before compilation/minification, while retaining direct-task protection.
tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease")) {
        dependsOn(verifyReleaseSigning)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(project(":core:ui"))

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:discover"))
    implementation(project(":feature:search"))
    implementation(project(":feature:podcast"))
    implementation(project(":feature:episode"))
    implementation(project(":feature:library"))
    implementation(project(":feature:inbox"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:account"))
    implementation(project(":feature:globalstats"))
    implementation(project(":feature:downloads"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
