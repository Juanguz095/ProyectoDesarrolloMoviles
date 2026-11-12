# AuditLab

**Monitoreo visible y autorizado para dispositivos Android propios o administrados.**
Un **Agente** instalado en el dispositivo supervisado comparte solo lo que su
propietario autoriza; un **Centro de control** consulta la actividad; una **API
Ktor/PostgreSQL** autentica todo y registra cada acceso. Indicador visible,
consentimiento versionado y revocación accesible siempre.

| Pieza | Tecnología | Rol |
| --- | --- | --- |
| Agente Android | Kotlin, Compose, Room | Consentimiento, captura autorizada, cola local. |
| Centro Android | Kotlin, Compose | Consulta, reportes y administración por rol. |
| API | Ktor, PostgreSQL, Flyway | Autenticación, validación, recepción, auditoría. |

## Levantar el servidor con Docker (paso a paso)

Requisitos: **Docker Desktop** corriendo, **JDK 21**, **Python 3** y el repositorio clonado.

**1. Comprueba Docker:**

```bash
docker info
```

**2. Crea la base de datos** (elige tu propia contraseña):

```powershell
# PowerShell
$env:AUDITLAB_DB_PASSWORD = "una-contraseña-tuya"
docker compose up -d db
docker compose ps
```

Debe quedar `healthy` (PostgreSQL 17.6 en `127.0.0.1:5433`). El `compose.yaml` publica
el **5433** porque Windows suele reservar u ocupar el 5432; los comandos de abajo ya
usan ese puerto.

**3. Compila la API y las apps:**

```bash
python3 scripts/gradle.py :backend:ktor:installDist :apps:agent:assembleDebug :apps:control:assembleDebug --no-daemon --max-workers=1
```

**4. Migra el esquema, concede permisos y crea el usuario `admin`:**

```bash
export AUDITLAB_MIGRATION_JDBC_URL=jdbc:postgresql://127.0.0.1:5433/auditlab
export AUDITLAB_MIGRATION_USER=auditlab
export AUDITLAB_MIGRATION_PASSWORD="una-contraseña-tuya"

backend/ktor/build/install/ktor/bin/ktor migrate
AUDITLAB_TARGET_DB_ROLE=auditlab_api  backend/ktor/build/install/ktor/bin/ktor grant-runtime
AUDITLAB_TARGET_DB_ROLE=auditlab_maintenance backend/ktor/build/install/ktor/bin/ktor grant-maintenance
AUDITLAB_BOOTSTRAP_USERNAME=admin AUDITLAB_BOOTSTRAP_PASSWORD="otra-contraseña" AUDITLAB_BOOTSTRAP_ROLE=admin backend/ktor/build/install/ktor/bin/ktor bootstrap-user
```

**5. Arranca la API:**

```bash
export AUDITLAB_DB_JDBC_URL=jdbc:postgresql://127.0.0.1:5433/auditlab
export AUDITLAB_DB_USER=auditlab_api
export AUDITLAB_DB_PASSWORD="una-contraseña-tuya"
export AUDITLAB_AUTH_SIGNING_KEY_BASE64="$(python3 -c 'import base64,secrets;print(base64.b64encode(secrets.token_bytes(32)).decode())')"

backend/ktor/build/install/ktor/bin/ktor
```

Comprueba `http://127.0.0.1:8081/ready` → `{"status":"ready"}`.

**6. Usa las apps:** instala las APK (`apps/*/build/outputs/apk/debug/`), inicia
sesión en el Centro con la URL del servidor y el usuario `admin`, genera un código
de inscripción e inscribe el Agente aceptando su consentimiento.

Todas las variables (TLS, retención, límites) están en
[docs/OPERACION.md](docs/OPERACION.md). Para una PC y dos teléfonos en redes
distintas, publica la API con Tailscale Serve (guía en
[docs/HISTORICO.md](docs/HISTORICO.md)).

## Funciones

| Función | Alcance |
| --- | --- |
| Estado del dispositivo | Batería, conexión y último contacto. |
| SMS y llamadas | Tipo y fecha con permiso Android; número y texto opcionales con autorización adicional. Sin audio. |
| WhatsApp | Vistas previas temporales de notificaciones nuevas; sin historial de chats. |
| Pantalla compartida | Iniciada por su propietario con permiso de Android; sin grabación. |
| Texto manual | Texto elegido y confirmado por el usuario en cada envío. |
| Galería | Fotos seleccionadas y confirmadas por el usuario (15 MB/archivo, 20 por lote). |
| Historial y reportes | Filtros, búsqueda, detalle y exportación CSV. |
| Administración | Inscripción con código de un uso, roles, revocación, auditoría y actualizaciones verificadas. |

## Privacidad

- Consentimiento versionado y revocación local e inmediata; indicador visible obligatorio.
- Cada función sensible exige acción local confirmada; nunca hay activación remota ni oculta.
- Retención declarada (eventos 30 días, auditoría 90 días) y borrado manual de contenidos sensibles.
- Credenciales cifradas (Keystore/Argon2id); auditoría sin contenido sensible.

## Estado y documentación

- Código actual: **0.9.0-dev** (plan de mejoras consentidas: M0–M2 completadas).
- Pendiente de validación nativa: SMS real, visor de pantalla y pruebas en teléfono físico.

| Documento | Contenido |
| --- | --- |
| [docs/INDICE.md](docs/INDICE.md) | Índice completo de la documentación. |
| [docs/MANUAL_PRODUCTO.md](docs/MANUAL_PRODUCTO.md) | Uso paso a paso. |
| [docs/OPERACION.md](docs/OPERACION.md) | Construcción, servidor, variables y pruebas. |
| [docs/PLAN_MEJORAS_CONSENTIDAS.md](docs/PLAN_MEJORAS_CONSENTIDAS.md) | Plan 0.9 en curso. |
| [contracts/openapi.yaml](contracts/openapi.yaml) | Contrato de la API. |

## Descargas

Última versión publicada: Agente 0.7.5-preview · Centro 0.7.4-preview · Servidor 0.7
([archivos y checksums](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/tree/descargas-auditlab)).
Las APK usan firma debug; la firma de distribución está pendiente.
