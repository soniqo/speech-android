plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    id("maven-publish")
}

val speechCoreDir = providers.gradleProperty("SPEECH_CORE_DIR")
    .orElse("${project.rootDir}/speech-core")
    .get()

android {
    namespace = "audio.soniqo.speech"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
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
            version = "4.1.2"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // android.util.Log is a no-op under unit tests rather than throwing,
        // so production code can log on paths the tests exercise.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp3.okhttp)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp3.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
