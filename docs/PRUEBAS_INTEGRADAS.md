# Preparar y conservar el fixture de integración

Este procedimiento corresponde al proyecto de dos apps y API de [PLAN_MAESTRO.md](PLAN_MAESTRO.md). Los resultados de cada ejecución se registran por separado: JVM, TLS y contrato no acreditan un recorrido de UI Android.

## Herramientas y entorno Python

Conservar las versiones de [desarrollo](desarrollo.md). Desde la raíz del checkout:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.lock --cache-dir .tooling/pip-cache
.venv/bin/python -m pip check
```

Si `.venv` ya funciona y coincide con el lock, puede reutilizarse. Tanto el venv como la caché quedan en el checkout, fuera de `/tmp`.

## Fixture persistente de PostgreSQL y API

```bash
python3 scripts/local_fixture.py prepare
python3 scripts/gradle.py :backend:ktor:installDist :apps:agent:assembleDebug :apps:control:assembleDebug --no-daemon
python3 scripts/local_fixture.py migrate
python3 scripts/local_fixture.py start
python3 scripts/local_fixture.py status
```

`prepare` crea una instancia de QA propia con un nombre y un volumen Docker únicos, PostgreSQL 17.6 fijado por digest, roles distintos de migración/runtime/mantenimiento, una clave de firma y una cuenta ficticia `qa_admin`. Los valores se generan localmente y no se imprimen. La API escucha en loopback 8081 y el puerto PostgreSQL se publica únicamente en loopback.

La configuración privada, los certificados y los registros se conservan en `.tooling/integration-fixture/`. El directorio tiene modo 700; los archivos de credenciales y la llave privada tienen modo 600. `.tooling/` está excluido de Git. La base reside en un volumen Docker y no depende de un archivo bajo `/tmp`.

Los comandos se pueden repetir: reutilizan el contenedor y la base identificados como propiedad del fixture. No eliminan filas, contenedores, volúmenes o llaves para lograr que una prueba pase. Migraciones y bootstrap son explícitos.

```bash
python3 scripts/local_fixture.py stop
python3 scripts/local_fixture.py start
```

`stop` detiene solo el proceso API cuyo PID, instante de creación y clase/ruta coinciden con el registro propio. Conserva PostgreSQL y sus datos. Para comprobar otras herramientas usando los valores privados sin copiarlos a la consola:

```bash
python3 scripts/local_fixture.py run -- .venv/bin/python scripts/check_live_contract.py
python3 scripts/local_fixture.py run -- bash scripts/check_tls.sh
python3 scripts/local_fixture.py run -- python3 scripts/gradle.py :apps:common:smokeTest --no-daemon
```

El chequeo TLS usa `.tooling/integration-fixture/tls-check/`. Los certificados son de QA. El cliente del contrato pasa la CA elegida en cada solicitud, conservando la verificación y el proxy; una CA ambiental no reemplaza esa elección explícita. La prueba exige que un certificado no confiable sea rechazado.

## Pruebas aisladas de base de datos

```bash
python3 scripts/check_database.py
```

Ese comando crea **otro** contenedor y una base nueva para la suite, sin usar la base persistente anterior. Elimina del entorno hijo las variables heredadas `AUDITLAB_*` antes de definir sus propias cuentas, firma y endpoints: esto incluye mantenimiento y TLS. Sus archivos temporales se crean en `.tooling/database-check-tmp/`; al finalizar limpia únicamente los recursos que él mismo creó.

## Android en esta nube

Usar las APKs de `apps/agent` y `apps/control`. Las APKs de `academic/` corresponden a otra entrega y no comprueban el flujo de dos apps. El emulador accede al servicio del anfitrión con `http://10.0.2.2:8081`; esa excepción de loopback está declarada en la política de red. El piloto requiere HTTPS real.

Para P4 se debe comprobar consentimiento → código de un uso → inscripción → evento simulado → consulta desde el centro → revocación local/remota. Se comparan los IDs con el servidor y se comprueba el registro de acceso.

No asumir que `sys.boot_completed=1` acredita una interfaz estable: comprobar también la app y las ventanas activas. Un diálogo ANR de System UI invalida ese intento de UI, aunque la actividad de AuditLab aparezca detrás. Registrar capturas y resultados fallidos antes de intentar recuperar el entorno.

El AVD necesita espacio para su disco además de los 2 GiB de RAM. En el emulador utilizado, el disco inicial se ajusta a 6 GiB y exige unos 7,2 GiB libres para crearlo. Los instaladores descargables pueden retirarse conservando herramientas extraídas, AVDs, datos y evidencias. Un bloqueo residual de `hardware-qemu.ini.lock` solo puede retirarse tras comprobar que su PID pertenece a un lanzamiento propio terminado, incluido el estado zombie; nunca se usa `-wipe-data` como reparación.

La ausencia de KVM limita el rendimiento de la emulación por software. El cierre de P4 depende de una ejecución real y estable. La firma de distribución, dispositivos físicos y piloto publicado tienen sus propios criterios en las fases 7–8.
