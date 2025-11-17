plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    application
}

group = "com.kaj.studioreserve"
version = "1.0.0"
application {
    mainClass.set("com.kaj.studioreserve.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.server.auth.jvm)
    implementation(libs.ktor.server.auth.jwt.jvm)
    implementation(libs.ktor.server.content.negotiation.jvm)
    implementation(libs.ktor.serialization.kotlinx.json.jvm)
    implementation(libs.ktor.server.call.logging.jvm)
    implementation(libs.ktor.server.status.pages.jvm)
    implementation(libs.ktor.client.core.jvm)
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.logback)
    implementation(libs.org.jetbrains.exposed.exposed.core2)
    implementation(libs.org.jetbrains.exposed.exposed.jdbc2)
    implementation(libs.org.jetbrains.exposed.exposed.kotlin.datetime2)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.bcrypt)

    testImplementation(libs.ktor.server.tests.jvm)
    testImplementation(kotlin("test"))
}
