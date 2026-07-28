plugins {
    id("koalacast.android.library")
    id("koalacast.android.compose")
}

android {
    namespace = "net.koalastuff.koalacast.core.ui"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))

    api(libs.androidx.compose.material3)
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)

    testImplementation(libs.okhttp.mockwebserver)
}
