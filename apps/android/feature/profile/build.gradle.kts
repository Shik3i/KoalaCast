plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.profile"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
