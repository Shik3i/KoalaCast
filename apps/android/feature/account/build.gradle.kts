plugins {
    id("koalacast.android.feature")
}

android {
    namespace = "net.koalastuff.koalacast.feature.account"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.androidx.activity.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
