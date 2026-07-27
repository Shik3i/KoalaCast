plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.downloads"
}

dependencies {
    implementation(project(":core:player"))
}
