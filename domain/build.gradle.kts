// Modulo di dominio: Kotlin puro, nessuna dipendenza Android (vedi ECC android-clean-architecture).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
