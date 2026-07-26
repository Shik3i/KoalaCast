plugins {
    id("koalacast.android.library")
    id("koalacast.android.hilt")
}

android {
    namespace = "net.koalastuff.koalacast.core.data"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit.kotlinx.serialization)
}
