plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
// Only binary compile-time signatures. Never use implementation/runtimeOnly on this project.
