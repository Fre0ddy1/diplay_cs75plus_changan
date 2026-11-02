plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.mediabridge.publisher"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":vehicle-customization-api"))
    api(project(":mediacore-bridge-contract"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
