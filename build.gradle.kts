plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // LiteRT-LM 0.14.0 publishes Kotlin 2.3 metadata, which requires the
    // Kotlin 2.2 compiler used by the full-pipeline Compose demo.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}
