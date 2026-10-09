import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.andronuke.cardscan"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        testApplicationId = "com.andronuke.cardscan.library.test"
        targetSdk = 35
        testInstrumentationRunner = "com.andronuke.cardscan.ScannerPipelineInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    testImplementation("junit:junit:4.13.2")

    // Google's bundled, on-device Latin OCR model; no first-use model download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    val cameraXVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.camera:camera-mlkit-vision:$cameraXVersion")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.andronuke"
                artifactId = "cardscan"
                version = "1.1.0"
                pom {
                    name.set("CardScan for Android")
                    description.set("On-device Visa, Mastercard, and Amex card number and expiry scanner.")
                    url.set("https://github.com/numesh/cardscan-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                        }
                    }
                    developers {
                        developer {
                            id.set("numesh")
                            name.set("Numesh Dilusha")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/numesh/cardscan-android.git")
                        developerConnection.set("scm:git:ssh://github.com/numesh/cardscan-android.git")
                        url.set("https://github.com/numesh/cardscan-android")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "nexus"
                url = uri(providers.environmentVariable("NEXUS_URL")
                    .orElse("https://example.invalid/repository/android-snapshots").get())
                credentials {
                    username = providers.environmentVariable("NEXUS_USERNAME").orNull
                    password = providers.environmentVariable("NEXUS_PASSWORD").orNull
                }
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/numesh/cardscan-android")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}
