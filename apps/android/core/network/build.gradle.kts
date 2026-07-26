plugins {
    id("koalacast.android.library")
    id("koalacast.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.koalastuff.koalacast.core.network"
}

dependencies {
    api(project(":core:model"))

    api(libs.okhttp)
    // `api` because apiCall()'s signature mentions retrofit2.Response, which
    // core:data's repositories have to see.
    api(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
