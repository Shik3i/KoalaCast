plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.inbox"
}

dependencies {
    implementation(project(":core:player"))
}
