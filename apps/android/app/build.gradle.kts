plugins {
    id("koalacast.android.application")
    id("koalacast.android.compose")
    id("koalacast.android.hilt")
}

android {
    namespace = "net.koalastuff.koalacast"

    defaultConfig {
        applicationId = "net.koalastuff.koalacast"
        versionCode = 1
        versionName = "0.1.0-alpha01"
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
            // Debug signing so a local `assembleRelease` is verifiable; real
            // releases are signed in CI with the upload key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
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
