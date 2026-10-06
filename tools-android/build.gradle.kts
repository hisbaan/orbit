plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hisbaan.orbit.tools"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":agent"))
    implementation(project(":ytmusic"))
    implementation(project(":weather"))
    implementation(project(":diagnostics"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
