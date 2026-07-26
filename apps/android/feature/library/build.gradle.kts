plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.library"
}

dependencies {
    implementation(project(":core:player"))
}
