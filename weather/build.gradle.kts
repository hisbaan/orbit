plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":diagnostics"))

    testImplementation(libs.junit)
}
