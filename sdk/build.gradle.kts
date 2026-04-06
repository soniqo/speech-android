plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
    id("maven-publish")
}

android {
    namespace = "audio.soniqo.speech"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSPEECH_CORE_DIR=${project.rootDir}/speech-core",
                    "-DORT_DIR=${project.rootDir}/ort",
                )
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

mavenPublishing {
    coordinates("audio.soniqo", "speech", findProperty("VERSION_NAME")?.toString() ?: "0.0.1")

    pom {
        name.set("speech-android")
        description.set("On-device speech SDK for Android — VAD, STT, TTS, noise cancellation")
        url.set("https://github.com/soniqo/speech-android")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/soniqo/speech-android/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("soniqo")
                name.set("Soniqo")
                url.set("https://soniqo.audio")
            }
        }

        scm {
            url.set("https://github.com/soniqo/speech-android")
            connection.set("scm:git:git://github.com/soniqo/speech-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/soniqo/speech-android.git")
        }
    }

    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/soniqo/speech-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
