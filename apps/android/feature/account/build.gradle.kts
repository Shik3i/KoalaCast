plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.account"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
