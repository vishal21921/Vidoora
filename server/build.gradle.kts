plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

group = "com.vidoora.backend"
version = "1.0.0"

application {
    mainClass.set("com.vidoora.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("runProcessor") {
    group = "application"
    description = "Process queued videos using PostgreSQL, FFmpeg and private R2 storage"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.vidoora.backend.modules.processing.ProcessingMainKt")
    if (providers.gradleProperty("processingOnce").orNull == "true") args("--once")
    if (providers.gradleProperty("thumbnailBackfill").orNull == "true") args("--thumbnails")
}

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)

    // Database & Migration
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Server-only Google Play Developer API credentials; never bundled in Android.
    implementation("com.google.auth:google-auth-library-oauth2-http:1.54.0")

    implementation("org.eclipse.angus:jakarta.mail:2.0.4")

    // Security & Logging
    implementation(libs.jbcrypt)
    implementation(libs.logback)

    // S3 / R2 Object Storage SDK
    implementation(libs.aws.s3)

    // Testing
    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.junit)
    testImplementation(libs.h2)
}

