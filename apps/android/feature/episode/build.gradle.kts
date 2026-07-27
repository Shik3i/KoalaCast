plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.episode"
}

dependencies {
    implementation(project(":core:player"))
    implementation(libs.kotlinx.serialization.json)
}
