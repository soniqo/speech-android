plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
    id("maven-publish")
}

val speechCoreDir = providers.gradleProperty("SPEECH_CORE_DIR")
    .orElse("${project.rootDir}/speech-core")
    .get()

android {
    namespace = "audio.soniqo.speech"
    compileSdk = 35
    // NDK r28+ defaults to 16 KB ELF alignment; pin r29 for reproducible AARs.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSPEECH_CORE_DIR=$speechCoreDir",
                    "-DORT_DIR=${project.rootDir}/ort",
                    // LiteRT runtime (fetched by setup.sh into /litert/<abi>/);
                    // enables the Nemotron multilingual LiteRT backend.
                    "-DLITERT_DIR=${project.rootDir}/litert",
                )
                // arm64-v8a for devices; x86_64 for emulators.
                abiFilters += listOf("arm64-v8a", "x86_64")
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
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

mavenPublishing {
    coordinates("audio.soniqo", "speech", findProperty("VERSION_NAME")?.toString() ?: "0.0.1")

    pom {
        name.set("speech-android")
        description.set("On-device speech SDK for Android — VAD, STT, TTS, noise cancellation")
        url.set("https://soniqo.audio/getting-started/android")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
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

    publishToMavenCentral()
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
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("androidx.work:work-testing:2.11.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
