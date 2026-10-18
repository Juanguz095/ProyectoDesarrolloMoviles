# Entorno y comprobaciones de desarrollo

## Herramientas

| Herramienta | Versión fijada para esta base |
| --- | --- |
| Python de scripts | 3.12; prototipo admite 3.10+, pero el instalador usa APIs 3.12. |
| Node para prueba de UI aislada | 24 |
| JDK completo | Oracle JDK 21.0.12.1, bytecode objetivo 17; se verifica también `javac`. |
| Gradle Wrapper | 8.11.1, distribución SHA-256 fijada. |
| Kotlin / plugin Compose | 2.1.20 |
| Android Gradle Plugin | 8.9.2 |
| SDK / build-tools / CLI | Plataforma 35 / 35.0.0 / 19.0 |
| Android mínimo / objetivo | 26 / 35 inicial; revisar requisito vigente antes de publicar. |
| Compose BOM / Activity | 2025.04.01 / 1.10.1 |
| Ktor | 3.1.2 |
| Flyway / PostgreSQL JDBC / Hikari | 11.20.3 / 42.7.14 / 6.3.3 |
| Auth0 java-jwt / Jackson BOM | 4.6.2 / 2.22.3 |
| Password4j / JUnit | 1.8.4 / 5.13.4 |
| PostgreSQL de desarrollo | 17.6-bookworm, imagen fijada por digest en `compose.yaml`. |

El instalador automatizado se comprobó en Linux amd64. En Android Studio puede usarse el SDK instalado localmente, conservando las versiones de construcción. Las versiones fijadas son una base reproducible; la revisión de dependencias y requisitos de distribución ocurre antes de entregar.

## Preparación reproducible en nube

Desde la raíz del checkout existente, sin crear otro worktree:

```bash
python3 scripts/setup_toolchain.py
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.lock
```

Las distribuciones se descargan desde sus proveedores oficiales, se verifican con SHA-256 y se extraen en `.tooling`. Las herramientas Android conservan la verificación de su gestor oficial. El script usa el proxy existente, la CA pública suministrada por el entorno y un truststore Java local; nunca deshabilita TLS. No almacena secretos de aplicación.

En esta nube el directorio personal no es escribible. `scripts/gradle.py` configura cachés y preferencias Android dentro de `.tooling`, selecciona el JDK completo instalado, y aplica la ruta de proxy/truststore a Gradle. Usar el script en lugar de exportar variables globales incompatibles. No definir simultáneamente rutas Android contradictorias.

## Comprobaciones del prototipo y documentación

```bash
python3 -m unittest discover -s backend/api/tests -v
node --test backend/api/tests/revocation.test.cjs
.venv/bin/python scripts/check_contract.py
.venv/bin/python -m unittest discover -s contracts/tests -v
python3 scripts/check_docs.py
```

Los tests HTTP generan credenciales y datos temporales y no usan la BD local existente. Las pruebas Node simulan DOM/red y no reemplazan una prueba en navegador/Android. Se validan por separado contrato definitivo 0.2 y prototipo 0.1; los fixtures del definitivo comprueban esquemas, no rutas ya implementadas. Las dependencias directas están en `requirements-dev.txt`; la instalación reproducible usa `requirements-dev.lock`.

## Construcción nativa

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :backend:ktor:installDist --no-daemon
python3 scripts/gradle.py :apps:agent:lintDebug :apps:control:lintDebug --no-daemon
```

Artefactos previstos: `apps/agent/build/outputs/apk/debug/agent-debug.apk`, `apps/control/build/outputs/apk/debug/control-debug.apk` y `backend/ktor/build/install/ktor/`. Las pantallas base son informativas y no capturan datos; login, consentimiento y sincronización se implementan en fases posteriores. Un APK compilado no se considera instalado ni validado en dispositivo.

Para arrancar el servicio base, consultar [backend Ktor](../backend/ktor/README.md). Para el recorrido simulado Python, consultar [el prototipo](../backend/api/README.md).

## Persistencia definitiva y pruebas JVM

```bash
python3 scripts/gradle.py :backend:ktor:test --no-daemon
python3 scripts/check_database.py
```

La primera ejecuta tests de configuración, contraseña, JWT y salud sin BD externa. La segunda crea un PostgreSQL y roles nuevos, aplica/repite Flyway, ejecuta `:backend:ktor:integrationTest`, prueba los comandos administrativos y comprueba login/refresh/logout, concurrencia, límites compartidos, reinicio del servicio y `/ready`/auth con BD no disponible. Clave externa y reglas en [autenticación](autenticacion.md). La tarea de integración fuerza ejecución y no da por válidos resultados de otra BD por caché. Variables y modelo en [base de datos](base-datos.md).

## PostgreSQL

```bash
python3 scripts/check_postgres.py
```

Requiere el daemon Docker local disponible. Crea un contenedor temporal con nombre único, credenciales aleatorias, conexión TCP autenticada y una transacción de lectura/escritura; luego lo elimina. No toca contenedores ni volúmenes existentes y no imprime credenciales.

`compose.yaml` prepara una BD persistente de desarrollo. Suministrar `AUDITLAB_DB_PASSWORD` de forma segura y utilizar el daemon local. En nube:

```bash
env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock compose up -d db
```

La contraseña no tiene valor predeterminado. En un volumen nuevo se exige SCRAM para conexiones TCP, incluso localhost; la prueba comprueba que una contraseña incorrecta falla. El argumento de inicialización no modifica reglas de autenticación de volúmenes ya existentes: revisarlas explícitamente sin borrar trabajo. La base está enlazada a loopback; el usuario bootstrap es para desarrollo. El backend definitivo deberá tener una cuenta de privilegios mínimos distinta de la cuenta de migración. No ejecutar `compose down -v` sobre una BD con trabajo que deba conservarse.

## CI y alcance

[El workflow](../.github/workflows/ci.yml) define tres trabajos: prototipo/contrato/documentación, construcción/lint y tests JVM de la base Android/Ktor, y PostgreSQL aislado con migraciones/CLI/disponibilidad. La ejecución alojada requiere subir la revisión con autorización vigente. Un YAML válido o comandos locales satisfactorios no son prueba de una ejecución en GitHub Actions.
