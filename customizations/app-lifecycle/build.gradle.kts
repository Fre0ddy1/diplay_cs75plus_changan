plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.lifecycle"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":vehicle-customization-api"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
