plugins {
    id("koalacast.android.library")
    id("koalacast.android.hilt")
}

android {
    namespace = "net.koalastuff.koalacast.core.player"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))

    api(libs.media3.exoplayer)
    api(libs.media3.session)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
