plugins {
    kotlin("jvm")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // Android provides org.json at runtime; unit tests use the real implementation.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

// Prueba de humo opcional contra una API Ktor en ejecución; no forma parte de `check`.
val smokeTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[smokeTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[smokeTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("smokeTest") {
    description = "Ejecuta el cliente JVM real contra una API en marcha (AUDITLAB_SMOKE_*)."
    group = "verification"
    testClassesDirs = smokeTest.output.classesDirs
    classpath = smokeTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // Permite probar el cliente contra un servidor HTTPS con CA privada (solo en la comprobación manual).
    System.getenv("AUDITLAB_SMOKE_TRUSTSTORE")?.takeIf { it.isNotBlank() }?.let { store ->
        systemProperty("javax.net.ssl.trustStore", store)
        System.getenv("AUDITLAB_SMOKE_TRUSTSTORE_PASSWORD")?.let { systemProperty("javax.net.ssl.trustStorePassword", it) }
    }
}
