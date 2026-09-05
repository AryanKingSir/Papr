buildscript {
    dependencies {
        // AGP's built-in Kotlin (AGP 9.0+) defaults to an older KGP version;
        // pin it to the version this project targets.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
