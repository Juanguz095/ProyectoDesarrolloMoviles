package com.auditlab.api

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2
import java.util.UUID

object Passwords {
    private val algorithm = Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID)

    fun hash(password: String): String {
        require(password.length in 12..128) { "Password must contain 12 to 128 characters" }
        return Password.hash(password).addRandomSalt(16).with(algorithm).result
    }

    fun verify(password: String, hash: String): Boolean = runCatching {
        Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash))
    }.getOrDefault(false)
}

object Bootstrap {
    fun user(config: DatabaseConfig, username: String, password: String, role: String): Boolean {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}").matches(username)) { "Invalid bootstrap username" }
        require(role in setOf("admin", "auditor")) { "Bootstrap role must be admin or auditor" }
        val hash = Passwords.hash(password)
        return DatabaseTools.connection(config) { connection ->
            connection.autoCommit = false
            try {
                val id = UUID.randomUUID()
                val created = connection.prepareStatement("INSERT INTO auditlab.users(id,username,password_hash,role) VALUES (?,?,?,?) ON CONFLICT (lower(username)) DO NOTHING").use { insert ->
                    insert.setObject(1, id); insert.setString(2, username); insert.setString(3, hash); insert.setString(4, role)
                    insert.executeUpdate() == 1
                }
                if (created) {
                    connection.prepareStatement("INSERT INTO auditlab.audit_log(id,actor_type,action,resource,result,correlation_id) VALUES (?,'system','bootstrap_user_created',?::jsonb,'success',?)").use { audit ->
                        audit.setObject(1, UUID.randomUUID())
                        audit.setString(2, """{"type":"user","userId":"$id"}""")
                        audit.setObject(3, UUID.randomUUID())
                        audit.executeUpdate()
                    }
                } else {
                    connection.prepareStatement("SELECT role FROM auditlab.users WHERE lower(username)=lower(?)").use { select ->
                        select.setString(1, username)
                        select.executeQuery().use { result ->
                            check(result.next() && result.getString(1) == role) { "Existing account has a different role; bootstrap never changes it" }
                        }
                    }
                }
                connection.commit()
                created
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }
}
