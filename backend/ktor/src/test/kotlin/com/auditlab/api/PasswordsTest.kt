package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PasswordsTest {
    @Test fun `argon2id has random salt and verifies only the original password`() {
        val password = "fixture-only-password"
        val first = Passwords.hash(password)
        val second = Passwords.hash(password)
        assertTrue(first.startsWith("\$argon2id\$"))
        assertNotEquals(first, second)
        assertTrue(Passwords.verify(password, first))
        assertFalse(Passwords.verify("wrong-password", first))
        assertFalse(Passwords.verify(password, "invalid-encoded-hash"))
    }

    @Test fun `bootstrap password bounds are enforced`() {
        for (password in listOf("short", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { Passwords.hash(password) }
        }
    }
}
