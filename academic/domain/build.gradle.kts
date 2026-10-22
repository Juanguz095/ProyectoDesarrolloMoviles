plugins {
    kotlin("jvm")
}
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test-junit", "2.2.10"))
}

tasks.test {
    useJUnit()
}
