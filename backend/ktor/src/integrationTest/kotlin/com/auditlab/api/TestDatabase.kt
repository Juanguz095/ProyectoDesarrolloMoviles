package com.auditlab.api

/** One initialization per JVM, independent of JUnit class execution order. */
internal object TestDatabase {
    init { check(System.getenv("AUDITLAB_TEST_ISOLATED") == "true") { "Use scripts/check_database.py" } }
    val owner = DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION").also {
        check(java.net.URI(it.url.removePrefix("jdbc:")).path.startsWith("/auditlab_test_")) { "Isolated database required" }
    }
    val runtime = DatabaseConfig.fromEnvironment("AUDITLAB_DB")
    val maintenance = DatabaseConfig.fromEnvironment("AUDITLAB_TEST_MAINTENANCE")
    val initialMigrations = DatabaseTools.migrate(owner)
    init {
        DatabaseTools.grantRole(owner, runtime.username)
        DatabaseTools.grantRole(owner, maintenance.username, maintenance = true)
        Bootstrap.user(owner, "fixture_admin", System.getenv("AUDITLAB_BOOTSTRAP_PASSWORD"), "admin")
    }
}
