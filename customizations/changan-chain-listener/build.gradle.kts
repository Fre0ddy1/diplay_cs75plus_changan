plugins { id("com.android.application") }
android {
    namespace = "com.freddyq.diplay.chainlistener"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.freddyq.medialightlistener"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    // Android9-only diagnostic, not a Play Store application; retain the policy warning.
    lint { warning += "ExpiredTargetSdkVersion" }
}
dependencies { testImplementation(libs.junit) }
