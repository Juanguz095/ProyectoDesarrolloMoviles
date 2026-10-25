# Configuración y despliegue HTTPS

Relación: P3.6. Describe la configuración del servicio definitivo Ktor/PostgreSQL y el despliegue previsto detrás de TLS. El prototipo Python/SQLite no se despliega con esta guía y permanece restringido a loopback.

## Modelo de proceso

```mermaid
flowchart LR
    A[Agente Android] -->|HTTPS 443| P[Proxy TLS]
    C[Centro de control] -->|HTTPS 443| P
    P -->|HTTP 127.0.0.1:8081| K[Ktor]
    K -->|JDBC| D[(PostgreSQL)]
    R[Trabajo de retención] -->|JDBC mantenimiento| D
```

- Ktor escucha solo en `127.0.0.1` (`embeddedServer(host = "127.0.0.1")`). El proxy termina TLS y es el único proceso expuesto.
- El estado vive en PostgreSQL; el proceso no guarda sesiones, eventos ni cursores en memoria. Reiniciarlo no pierde datos confirmados.
- Los cursores son opacos y firmados con la clave de autenticación; no requieren almacenamiento.
- La retención corre en el mismo proceso con la cuenta de mantenimiento, o por separado con `ktor retention` programado por el operador.

## Variables de entorno

Nunca se guardan valores reales en Git, URLs JDBC, documentación ni evidencias. Los nombres siguientes son los únicos que lee el servicio.

| Variable | Uso | Secreto | Obligatoria |
| --- | --- | --- | --- |
| `AUDITLAB_DB_JDBC_URL` | JDBC del rol runtime (`jdbc:postgresql://host:5432/auditlab`). Sin usuario/contraseña en la URL. | No | Sí para rutas de negocio |
| `AUDITLAB_DB_USER` / `AUDITLAB_DB_PASSWORD` | Credenciales del rol runtime (sin privilegios administrativos). | Sí | Sí con la anterior |
| `AUDITLAB_MAINTENANCE_JDBC_URL` / `_USER` / `_PASSWORD` | Cuenta de mantenimiento para retención. | Sí | Sí para retención programada |
| `AUDITLAB_AUTH_SIGNING_KEY_BASE64` | Clave HMAC de JWT y cursores, Base64 de al menos 32 bytes. | Sí | Sí con base de datos |
| `AUDITLAB_AUTH_ISSUER` / `AUDITLAB_AUTH_AUDIENCE` | Emisor y audiencia del JWT. | No | No (valores por defecto `auditlab-api`, `auditlab-control`) |
| `AUDITLAB_AUTH_LOGIN_*_LIMIT`, `AUDITLAB_AUTH_REFRESH_*_LIMIT` | Límites de intentos por cuenta, cliente y globales. | No | No |
| `AUDITLAB_RETENTION_DAYS` | Días de eventos desde `receivedAt` (1–3650, por defecto 30). | No | No |
| `AUDITLAB_AUDIT_RETENTION_DAYS` | Días de auditoría (1–3650, por defecto 90). | No | No |
| `AUDITLAB_RETENTION_INTERVAL_SECONDS` | Intervalo del bucle (60–86400, por defecto 3600). | No | No |
| `AUDITLAB_PORT` | Puerto local de Ktor en HTTP (por defecto 8081). | No | No |
| `AUDITLAB_TLS_CERTIFICATE` / `AUDITLAB_TLS_PRIVATE_KEY` | Rutas a certificado X.509 y clave privada **PKCS#8 sin cifrar** en PEM. Si se definen, Ktor sirve solo HTTPS y no abre el puerto HTTP. | No (son rutas; el contenido de la clave sí) | No |
| `AUDITLAB_TLS_PORT` | Puerto HTTPS cuando TLS está configurado (por defecto 8443). | No | No |

Variables exclusivas de comandos administrativos: `AUDITLAB_MIGRATION_*`, `AUDITLAB_BOOTSTRAP_USERNAME`, `AUDITLAB_BOOTSTRAP_PASSWORD`, `AUDITLAB_BOOTSTRAP_ROLE` y `AUDITLAB_TARGET_DB_ROLE`. No se entregan al proceso servidor.

Una configuración parcial de base de datos o autenticación falla al arrancar con código 2 y un mensaje sin valores. Sin base de datos, el proceso solo ofrece `/health`; no sirve rutas de negocio.

## Preparación y arranque

```bash
# 1. Esquema y permisos (cuenta de migración).
ktor migrate
AUDITLAB_TARGET_DB_ROLE=auditlab_api ktor grant-runtime
AUDITLAB_TARGET_DB_ROLE=auditlab_maintenance ktor grant-maintenance

# 2. Cuenta inicial de AuditLab (no es un rol PostgreSQL).
ktor bootstrap-user

# 3. Servicio con las variables del servidor.
ktor
```

`migrate` valida checksums y no borra datos. Repetirlo no modifica un esquema ya aplicado. Tras una migración nueva se repiten los grants.

## TLS y proxy

Configuración mínima de referencia (nginx). Los certificados y sus claves se gestionan fuera del repositorio.

```nginx
server {
    listen 443 ssl;
    server_name auditlab.example.invalid;
    ssl_certificate     /etc/auditlab/tls/fullchain.pem;
    ssl_certificate_key /etc/auditlab/tls/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 1m;          # El lote de eventos está limitado a 256 KiB.
    proxy_read_timeout 30s;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

- Redirigir `80` a `443`; no publicar `8081`.
- No registrar cuerpos, cabeceras `Authorization`, `X-Enrollment-Code`, tokens ni códigos de inscripción en el proxy.
- Mantener `Cache-Control: no-store` que ya emite la API; no reescribir cabeceras de respuestas.
- La aplicación Android exige TLS y validación de certificados fuera de loopback. Para desarrollo local se admite HTTP solo contra `127.0.0.1`/`10.0.2.2`.

### TLS nativo de Ktor (comprobado en local)

Ktor puede terminar TLS por sí mismo, sin proxy, cuando se definen `AUDITLAB_TLS_CERTIFICATE` y `AUDITLAB_TLS_PRIVATE_KEY`. En ese modo **solo** se abre `AUDITLAB_TLS_PORT`; no queda un puerto HTTP en claro al lado, ni siquiera en loopback.

```bash
# Certificado de prueba con CA propia (no usar en un piloto real).
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

export AUDITLAB_TLS_CERTIFICATE=/ruta/cert.pem
export AUDITLAB_TLS_PRIVATE_KEY=/ruta/key.pem
export AUDITLAB_TLS_PORT=8443
ktor
```

- La clave debe ser PKCS#8 sin contraseña (`-----BEGIN PRIVATE KEY-----`); una clave cifrada o en formato PKCS#1 (`BEGIN RSA PRIVATE KEY`) se rechaza al arrancar.
- Ktor carga el par en un `KeyStore` PKCS#12 en memoria con una contraseña aleatoria por proceso: no se escribe ningún almacén en disco ni se registra la contraseña.
- Si se usa proxy delante, este debe apuntar a `https://127.0.0.1:8443` y validar el certificado del servicio, o bien dejar Ktor en HTTP solo en loopback y terminar TLS en el proxy. No mezclar ambos modos sin revisar la configuración.
- El certificado de prueba no sirve para un piloto: un dispositivo real exige una CA en la que confíe el sistema Android.

## Salud y disponibilidad

| Ruta | Significado |
| --- | --- |
| `GET /health` | El proceso responde. No comprueba la base de datos. |
| `GET /ready` | `200 {"status":"ready"}` si la cuenta runtime conecta y consulta el esquema; `503 {"status":"unavailable"}` si falta configuración o la base de datos no responde. |

El balanceador debe usar `/ready` para retirar la instancia y `/health` solo para reinicio del proceso. Una base inaccesible mantiene `/health` en `200` y `/ready` en `503`.

## Retención

- Ejecutar con la cuenta de mantenimiento: `ktor retention` (una pasada) o el bucle del servidor.
- El bucle ejecuta una pasada al arrancar y después cada `AUDITLAB_RETENTION_INTERVAL_SECONDS`; no depende de tráfico de consultas.
- Borra eventos con `receivedAt` anterior al plazo y auditoría anterior a su plazo, en una transacción, y registra `retention_executed` con actor `system`.
- El rol runtime no puede borrar eventos ni auditoría. El rol de mantenimiento puede consultar/borrar y **añadir** su propia fila de auditoría, pero no modificarla.
- Comprobar el resultado en `/v1/audit-log?action=retention_executed`.

## Registros y secretos

- La API no registra cuerpos, tokens, códigos de inscripción, contraseñas ni valores de conexión. Los errores devuelven `application/problem+json` con `code` y `correlationId`, sin trazas internas.
- `logback.xml` escribe en consola; el operador decide el destino. No habilitar depuración de JDBC/Hikari con parámetros.
- Rotar la clave de autenticación invalida todos los JWT y cursores: planificarlo con las sesiones. No se guarda en Git ni en la imagen.
- Los respaldos de PostgreSQL contienen datos personales y hashes de credenciales: cifrarlos y aplicar la misma retención.

## Comprobación local realizada (P3.6)

Con la distribución instalada y PostgreSQL 18.6 local:

1. `ktor bootstrap-user` creó una cuenta admin de prueba.
2. `GET /health` y `GET /ready` respondieron `200`; login, código de inscripción, inscripción, ingesta y consulta funcionaron.
3. Tras detener y reiniciar el proceso, el evento concreto seguía en `GET /v1/events` (misma base de datos).
4. `ktor retention` con la cuenta de mantenimiento terminó con código 0 y auditó la pasada.
5. Con una URL JDBC a un puerto cerrado, `/health` siguió en `200` y `/ready` respondió `503`.
6. El registro del servidor no contenía las contraseñas ni la clave de firma usadas.

Estas comprobaciones son manuales y locales. No sustituyen un despliegue con TLS ni la verificación alojada de CI, y no se declara un entorno de piloto publicado.
