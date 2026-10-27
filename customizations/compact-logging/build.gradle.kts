plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.diagnostics"
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
}
