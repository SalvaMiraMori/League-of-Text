// Fitxer de compilació arrel: els plugins es declaren aquí i s'apliquen a :app.
buildscript {
    dependencies {
        // AGP 9 compila Kotlin ell mateix i porta KGP 2.2.10. Afegir el KGP al classpath
        // és la manera documentada de fer servir una versió més nova (la del catàleg).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}
