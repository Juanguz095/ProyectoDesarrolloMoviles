# Operación: entorno, servidor y pruebas

Consolidación de las guías de desarrollo y despliegue (11 de noviembre de 2026).
El contrato real es [openapi.yaml](../contracts/openapi.yaml); la arquitectura está
en [ARQUITECTURA](ARQUITECTURA.md).

## Herramientas

- JDK 21, Android SDK 35, Python 3, Docker Desktop (para PostgreSQL).
- Wrapper de Gradle fijado en el repositorio; caches locales en `.tooling/`.
- En Windows usar `gradlew.bat` (el script `scripts/gradle.py` espera un entorno
  con `gradlew` de Unix/WSL).

## Construcción

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :backend:ktor:installDist --no-daemon --max-workers=1
```

Artefactos: `apps/*/build/outputs/apk/debug/*.apk` y `backend/ktor/build/install/ktor/`.

## Base de datos y servidor

1. **PostgreSQL** con [compose.yaml](../compose.yaml): `AUDITLAB_DB_PASSWORD` + `docker compose up -d db`.
2. **Migrar y permisos** (cuenta de migración, no va al proceso servidor):

```bash
ktor migrate
AUDITLAB_TARGET_DB_ROLE=auditlab_api ktor grant-runtime
AUDITLAB_TARGET_DB_ROLE=auditlab_maintenance ktor grant-maintenance
AUDITLAB_BOOTSTRAP_USERNAME=admin AUDITLAB_BOOTSTRAP_PASSWORD=... AUDITLAB_BOOTSTRAP_ROLE=admin ktor bootstrap-user
```

3. **Servidor**: `ktor` con las variables de runtime (tabla inferior). Comandos
   adicionales: `ktor retention` (pasada manual) y `ktor bootstrap-user`.
   Salud: `GET /health` y `GET /ready` (por defecto en `http://127.0.0.1:8081`).

## Variables de entorno

| Variable | Descripción | Obligatoria |
| --- | --- | --- |
| `AUDITLAB_DB_JDBC_URL` / `AUDITLAB_DB_USER` / `AUDITLAB_DB_PASSWORD` | Rol runtime sin privilegios (`jdbc:postgresql://host:5432/auditlab`, sin credenciales en la URL). | Sí para rutas de negocio |
| `AUDITLAB_MAINTENANCE_*` (JDBC_URL/USER/PASSWORD) | Cuenta de mantenimiento para retención programada. | Sí para retención |
| `AUDITLAB_AUTH_SIGNING_KEY_BASE64` | Clave HMAC de JWT y cursores (Base64 de ≥32 bytes). | Sí con BD |
| `AUDITLAB_AUTH_ISSUER` / `AUDITLAB_AUTH_AUDIENCE` | Emisor/audiencia JWT (defecto `auditlab-api` / `auditlab-control`). | No |
| `AUDITLAB_AUTH_LOGIN_*_LIMIT` / `AUDITLAB_AUTH_REFRESH_*_LIMIT` | Límites de intentos por cuenta, cliente y globales. | No |
| `AUDITLAB_RETENTION_DAYS` / `AUDITLAB_AUDIT_RETENTION_DAYS` | Retención de eventos (30) y auditoría (90). | No |
| `AUDITLAB_RETENTION_INTERVAL_SECONDS` | Intervalo del bucle (defecto 3600). | No |
| `AUDITLAB_PORT` | Puerto HTTP local (defecto 8081). | No |
| `AUDITLAB_TLS_CERTIFICATE` / `AUDITLAB_TLS_PRIVATE_KEY` / `AUDITLAB_TLS_PORT` | TLS nativo PEM (PKCS#8 sin cifrar). Si se definen, solo se abre HTTPS (8443). | No |
| `AUDITLAB_MEDIA_DIR` | Directorio de archivos de galería (D11). | No (defecto `auditlab-media`) |

Solo comandos administrativos: `AUDITLAB_MIGRATION_*`, `AUDITLAB_BOOTSTRAP_*`,
`AUDITLAB_TARGET_DB_ROLE`. Nunca se pasan al proceso servidor.

## HTTPS

Ktor termina TLS solo con `AUDITLAB_TLS_CERTIFICATE`/`AUDITLAB_TLS_PRIVATE_KEY`
(sin HTTP en claro al lado). Para piloto privado sin dominio, Tailscale Serve
publica la API en la VPN (pasos en [HISTORICO](HISTORICO.md)). El prototipo web
queda en loopback; HTTPS obligatorio fuera de él.

## Respaldos

`pg_dump` con la cuenta de mantenimiento; restauración ensayada en BD aislada.
Rollback: conservar el paquete anterior y `api/` respaldado; nunca borrar la base
ni la configuración para resolver errores de inicio.

## Pruebas

| Comando | Qué cubre |
| --- | --- |
| `./gradlew.bat :apps:common:test :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest` | Apps (JVM/Robolectric) |
| `./gradlew.bat :backend:ktor:test` | Backend unit (parser, almacén, auth, configuración) |
| `python3 scripts/check_database.py` | Integración PostgreSQL aislada (Docker con socket Unix; migraciones, roles, concurrencia, retención) |
| `python3 scripts/gradle.py :apps:agent:lintDebug :apps:control:lintDebug` | Lint |

Los resultados se registran distinguiendo **aprobado, fallido, omitido y no
ejecutado**. Nada se declara validado en dispositivo sin ejecución nativa.

## Registros y secretos

Nunca registrar contraseñas, tokens, códigos de inscripción ni claves en Git,
evidencias o respuestas. Verificar presencia de variables sin imprimir valores.
