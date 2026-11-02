plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.mediabridge.receiver"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":mediacore-bridge-contract"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
