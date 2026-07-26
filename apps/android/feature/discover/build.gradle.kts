plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.discover"
}

dependencies {
    implementation(project(":core:player"))
}
