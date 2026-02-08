import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library) // This is com.android.kotlin.multiplatform.library
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
    android {
        androidResources.enable = true
        compileSdk = 36
        namespace = "top.msfxp.music.shared"
    }
    
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.ui)
                api(libs.components.resources)
                
                // Miuix
                implementation(libs.miuix)
                implementation(libs.miuix.icons)
                implementation(libs.haze)
                
                // Ktor & Serialization
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                
                // Persistence
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.okio)

                // Coroutines
                implementation(libs.kotlinx.coroutines.core)

                // Image Loader
                implementation(libs.image.loader)
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.session)
                implementation(libs.media3.ui)
                implementation(libs.sqldelight.android)
            }
        }
        
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
                implementation(libs.sqldelight.web)
            }
        }
    }
}

sqldelight {
    databases {
        create("MusicDb") {
            packageName.set("database")
        }
    }
}
