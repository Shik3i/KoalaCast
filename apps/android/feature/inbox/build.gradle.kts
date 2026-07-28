plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.inbox"
}

dependencies {
    implementation(project(":core:player"))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
