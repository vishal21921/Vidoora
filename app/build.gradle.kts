import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    alias(libs.plugins.hilt)
}

// Public app identifier for UMP configuration; ad-unit IDs remain Google demos only.
val viewerAdmobAppId=providers.gradleProperty("vidooraAdmobAppId").getOrElse("ca-app-pub-3940256099942544~3347511713")
require(Regex("ca-app-pub-[0-9]{16}~[0-9]{10}").matches(viewerAdmobAppId)) { "vidooraAdmobAppId must be an AdMob app ID (contains ~), not an ad-unit ID." }

// The internal track uses a dedicated private upload key, never the Android debug certificate.
val uploadStore=providers.environmentVariable("VIDOORA_UPLOAD_STORE_FILE")
val uploadPassword=providers.environmentVariable("VIDOORA_UPLOAD_STORE_PASSWORD")
val uploadAlias=providers.environmentVariable("VIDOORA_UPLOAD_KEY_ALIAS")
val uploadKeyPassword=providers.environmentVariable("VIDOORA_UPLOAD_KEY_PASSWORD")
val uploadConfigured=listOf(uploadStore,uploadPassword,uploadAlias,uploadKeyPassword).all { !it.orNull.isNullOrBlank() }
val internalApi=providers.gradleProperty("vidooraInternalApiBaseUrl").getOrElse("http://127.0.0.1:8080/")
val internalUri=URI(internalApi)
require(internalUri.isAbsolute && internalUri.host!=null && internalUri.rawUserInfo==null && internalUri.rawQuery==null && internalUri.rawFragment==null && internalApi.endsWith("/") &&
    (internalUri.scheme=="https" || (internalUri.scheme=="http" && internalUri.host=="127.0.0.1"))) {
    "The internal API must be HTTPS, or the explicit USB-only http://127.0.0.1 endpoint; no URL credentials or query parameters."
}
val internalVersion=providers.gradleProperty("vidooraPlayVersionCode").map { it.toInt().also { code -> require(code>0) } }.orElse(1)
// Empty means existing API playback. This public origin is not a credential.
val mediaEdge=providers.gradleProperty("vidooraMediaEdgeBaseUrl").getOrElse("")
if(mediaEdge.isNotEmpty()) {
    val edgeUri=URI(mediaEdge)
    require(edgeUri.scheme=="https" && edgeUri.host!=null && edgeUri.rawUserInfo==null &&
        edgeUri.rawPath=="/" && edgeUri.rawQuery==null && edgeUri.rawFragment==null) { "Media edge must be a root HTTPS origin ending in /" }
}

val shareHost=providers.gradleProperty("vidooraShareHost").getOrElse("links.invalid")
require(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*").matches(shareHost)) { "Invalid share host" }

android {
    namespace = "com.example.vidoora"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.vidoora"
        buildConfigField("boolean", "PLAY_BILLING_ENABLED", "false")
        buildConfigField("boolean", "PLAY_CHECKOUT_ENABLED", "false")
        buildConfigField("String", "SHARE_HOST", "\"$shareHost\"")
        manifestPlaceholders["vidooraShareHost"]=shareHost
        buildConfigField("String", "MEDIA_EDGE_BASE_URL", "\"$mediaEdge\"")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["vidooraAdmobAppId"]=viewerAdmobAppId
        manifestPlaceholders["vidooraAppLabel"]="@string/app_name"
    }

    signingConfigs {
        if(uploadConfigured) create("playUpload") {
            storeFile=file(uploadStore.get());storePassword=uploadPassword.get()
            keyAlias=uploadAlias.get();keyPassword=uploadKeyPassword.get()
        }
    }
    buildTypes {
        debug { buildConfigField("boolean", "VIEWER_TEST_ADS", "true") }
        create("billingTest") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "PLAY_BILLING_ENABLED", "true")
            // Separate installation: never replace the development application's local data.
            manifestPlaceholders["vidooraAppLabel"] = "Vidoora Billing Test"
        }
        create("playInternal") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable=false
            isMinifyEnabled=false
            versionNameSuffix="-internal"
            manifestPlaceholders["vidooraAppLabel"]="Vidoora Internal Test"
            buildConfigField("boolean","VIEWER_TEST_ADS","true")
            buildConfigField("boolean","PLAY_BILLING_ENABLED","true")
            buildConfigField("boolean","PLAY_CHECKOUT_ENABLED","true")
            buildConfigField("String","API_BASE_URL","\"${internalUri.toASCIIString()}\"")
            signingConfig=if(uploadConfigured) signingConfigs.getByName("playUpload") else null
        }
        release {
            buildConfigField("boolean", "VIEWER_TEST_ADS", "false")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("maven.repo.local", "$rootDir/.gradle/robolectric")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("vidooraApiBaseUrl").getOrElse("http://10.0.2.2:8080/")}\"")
    }
}

androidComponents {
    onVariants(selector().withBuildType("playInternal")) {
        it.applicationId.set("com.vidoora.app")
        it.outputs.forEach { output -> output.versionCode.set(internalVersion) }
    }
    onVariants(selector().withBuildType("billingTest")) {
        it.applicationId.set("com.vidoora.app")
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.android.billingclient:billing:9.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycleRuntimeKtx.get()}")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Security & Encrypted Preferences
    implementation(libs.androidx.security.crypto)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager & Media3
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation("androidx.media3:media3-exoplayer-hls:${libs.versions.media3.get()}")
    implementation("androidx.media3:media3-ui:${libs.versions.media3.get()}")
    implementation("androidx.media3:media3-exoplayer-ima:${libs.versions.media3.get()}")
    // Override the adapter transitive SDK: IMA 3.33.0 is deprecated.
    implementation("com.google.ads.interactivemedia.v3:interactivemedia:3.40.0")

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Ktor Client for Android
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)

    // Testing
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}






// Fail before producing any upload-labelled bundle if private release signing has not been configured.
val validatePlayUploadSigning=tasks.register("validatePlayUploadSigning") {
    doLast {
        check(uploadConfigured) { "Private Vidoora upload signing is required. Use the local signing launcher; never paste passwords into source or chat." }
        val store=KeyStore.getInstance(file(uploadStore.get()),uploadPassword.get().toCharArray())
        val certificate=store.getCertificate(uploadAlias.get()) as? X509Certificate ?: error("The upload certificate is missing")
        check(store.isKeyEntry(uploadAlias.get())) { "The configured alias has no private upload key" }
        certificate.checkValidity()
        check(!certificate.subjectX500Principal.name.contains("CN=Android Debug",ignoreCase=true)) { "Android debug keys cannot be used for this Play upload" }
    }
}
tasks.matching { it.name in setOf("bundlePlayInternal","packagePlayInternalBundle","signPlayInternalBundle") }.configureEach {
    dependsOn(validatePlayUploadSigning)
}
