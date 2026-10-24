plugins { id("com.android.library") }

android {
    namespace = "com.freddyq.diplay.customization"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
