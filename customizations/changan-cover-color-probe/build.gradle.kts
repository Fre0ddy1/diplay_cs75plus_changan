plugins { id("com.android.application") }
android {
    namespace = "com.freddyq.diplay.colorprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.freddyq.covercolorprobe"
        minSdk = 28
        // Matches the OEM client SDK; this independent diagnostic is for the Android9 head unit.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    lint {
        // Deliberately limited to the Android 9 OEM SDK diagnostic, never shipped to Google Play.
        // Keep the old-target finding visible; it does not apply to the DiPlay application.
        warning += "ExpiredTargetSdkVersion"
    }
}
dependencies { testImplementation(libs.junit) }
