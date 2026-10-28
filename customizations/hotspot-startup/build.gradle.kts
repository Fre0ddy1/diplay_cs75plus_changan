plugins { id("com.android.library") }
android {
    namespace = "com.freddyq.diplay.hotspot"
    compileSdk = 37
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":vehicle-customization-api"))
    implementation(project(":shared"))
    compileOnly(project(":android9-hotspot-stubs"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testCompileOnly(project(":android9-hotspot-stubs"))
}
