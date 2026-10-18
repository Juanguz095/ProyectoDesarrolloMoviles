plugins {
    kotlin("jvm")
    application
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("com.auditlab.api.ApplicationKt") }
dependencies {
    implementation("io.ktor:ktor-server-netty-jvm:3.1.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.flywaydb:flyway-core:11.20.3")
    implementation("org.flywaydb:flyway-database-postgresql:11.20.3")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("com.password4j:password4j:1.8.4")
    implementation("com.auth0:java-jwt:4.6.2")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.2")
}

tasks.test { useJUnitPlatform() }
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("integrationTest") {
    description = "Runs required database checks against an isolated PostgreSQL fixture."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
