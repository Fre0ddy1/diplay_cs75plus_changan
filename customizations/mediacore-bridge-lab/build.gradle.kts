plugins { id("com.android.application") }
android {
    namespace = "com.freddyq.diplay.mediabridge.lab"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.freddyq.mediacorebridgelab"
        minSdk = 25
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-local"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Local/offline validation host, not a Play Store application.
    lint { warning += "ExpiredTargetSdkVersion" }
}
dependencies {
    implementation(project(":mediacore-bridge-publisher"))
    implementation(project(":mediacore-bridge-receiver"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
