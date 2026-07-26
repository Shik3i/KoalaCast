plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.player"
}

dependencies {
    implementation(project(":core:player"))
}
