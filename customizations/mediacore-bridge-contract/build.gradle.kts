plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.mediabridge.contract"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
