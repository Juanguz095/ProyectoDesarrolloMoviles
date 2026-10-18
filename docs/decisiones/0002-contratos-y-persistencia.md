# ADR 0002 — Contrato definitivo, migraciones y cuentas separadas

Estado: adoptado para P2.2/P2.3. Evidencias y límites en el registro de avance.

## Decisiones

- `contracts/openapi.yaml` pasa a 0.2 para el servicio definitivo; `prototype-v0.1.yaml` conserva la referencia del prototipo. No se confunde schema válido con rutas implementadas.
- Detalle/idempotencia por `(deviceId,eventId)`, marca de simulación explícita y recepción del servidor; contrato estricto con errores estables y paginación real prevista.
- PostgreSQL/JDBC con Flyway 11.20.3, módulo Flyway PostgreSQL de la misma versión, driver PostgreSQL 42.7.14 y HikariCP 6.3.3. Versiones disponibles verificadas contra metadatos del repositorio Maven oficial.
- Migraciones solo por CLI con cuenta de migración. El servidor usa una cuenta runtime limitada; retención tiene una identidad distinta. Roles deben existir y no ser administrativos ni dueños de objetos protegidos.
- Bootstrap de cuentas AuditLab por CLI, idempotente sin rotación implícita de rol/contraseña; Argon2id mediante Password4j 1.8.4. No se utiliza el JWT artesanal del prototipo.
- FK de consentimiento/dispositivo y código/dispositivo, y padre/sesión de refresh; relaciones circulares diferidas hasta commit para permitir inscripción atómica sin registros incompletos.
- Pruebas de BD usan PostgreSQL 17.6 real en contenedor temporal, sin SQLite/H2 como sustituto de sus transacciones y permisos. JUnit 5.13.4 para JVM, integración separada y nunca satisfecha solo por caché de Gradle.

## Consecuencias

El despliegue debe preparar roles/variables y ejecutar migraciones antes del servicio. Los hashes/códigos se comparan dentro de operaciones de fase 3 aún pendientes. La retención exige implementar su job y selección de registros, no solo disponer del permiso DELETE. Salud y disponibilidad se distinguen sin declarar listas las rutas protegidas.

Antes del piloto se revisan actualizaciones de dependencias, TLS, gestión de llaves y acceso operativo conforme al plan; estas versiones fijan la base comprobada de esta fase.
