# Base Ktor

Este módulo contiene el arranque, pool PostgreSQL, migraciones Flyway y comandos de bootstrap/permisos del servicio definitivo. Login HTTP, refresh rotatorio y logout están implementados; inscripción, eventos, consultas, revocación de dispositivos y retención siguen pendientes de fase 3. El [contrato 0.2](../../contracts/openapi.yaml) define esas interfaces; el prototipo Python permanece separado.

Desde la raíz del repositorio:

```bash
python3 scripts/gradle.py :backend:ktor:installDist --no-daemon
AUDITLAB_PORT=8081 backend/ktor/build/install/ktor/bin/ktor
```

El servidor escucha solo en loopback. `GET /health` devuelve `status: ok`, `service: auditlab-api`, `stage: foundation`. `GET /ready` comprueba la conexión/consulta requerida con la cuenta runtime: 200 disponible, 503 ausente o fallida. Sin variables DB arranca solo salud; una configuración parcial falla.

La [guía de base de datos](../../docs/base-datos.md) detalla roles, variables y comandos `migrate`, `bootstrap-user`, `grant-runtime` y `grant-maintenance`. No se ejecutan migraciones automáticamente al arrancar.

Comprobaciones:

```bash
python3 scripts/gradle.py :backend:ktor:test --no-daemon
python3 scripts/check_database.py
```

La segunda crea una BD PostgreSQL temporal y ejecuta integración, CLI y cambio de disponibilidad al detener la BD. No apunta a una base existente del usuario.

La [guía de autenticación](../../docs/autenticacion.md) define clave externa, emisor, audiencia, límites y las rutas `/v1/auth/login`, `/v1/auth/refresh`, `/v1/auth/logout`. Sin configuración auth esas rutas devuelven 503. La prueba aislada también ejecuta autenticación, concurrencia y reinicio del proceso. La puesta en producción requiere HTTPS y los criterios del plan.
