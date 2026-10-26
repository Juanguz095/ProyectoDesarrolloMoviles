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


## Aceptación nativa reproducible de ambas apps

Precondiciones: fixture privado listo en `127.0.0.1:8081`, dos emuladores dedicados distintos API26 o posterior, APKs debug/AndroidTest actuales y herramientas ADB/SQLite del emulador. El script requiere los seriales explícitos y rechaza teléfonos físicos para evitar modificar su red/ajustes. El recorrido automático de DocumentsUI usa los nombres del sistema en inglés: mantener los AVD de QA en en-US; la interfaz de AuditLab sigue en español. Para pruebas en teléfono, seguir el recorrido manual con la autorización de su dueño.

```bash
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :apps:agent:assembleDebugAndroidTest :apps:control:assembleDebugAndroidTest --no-daemon --max-workers=1
.venv/bin/python scripts/check_android.py --agent-serial emulator-5580 --control-serial emulator-5582
```

No desinstala, no ejecuta `pm clear` y no borra registros para pasar el preflight. Si ya existe una inscripción o sesión, retirarla/cerrarla desde la app o usar otro AVD dedicado. En el agente, confirmar la retirada y pulsar **Volver a aceptar consentimiento**, dejando la nueva casilla sin marcar; así se vuelve al estado inicial sin un borrado externo de datos. Cada ejecución crea usuarios/códigos/eventos ficticios propios; conserva su trazabilidad y no borra los datos previos.

El orquestador alterna WiFi/datos del **agente** para comprobar desconexión real. Usa un servidor HTTP 204 propio en loopback para validar la conectividad de la red virtual; no elimina las restricciones de WorkManager. Conserva/restaura los ajustes modificados, reduce animaciones y deja de renderizar el agente tras completar sus casos para limitar la carga sin KVM. Los permisos/notificaciones se cambian mediante la pantalla del sistema dentro de la prueba y se restauran en `finally`.

Hay 17 pasos ordenados: inscripción, lectura cruzada, creación de código, sesiones tras muerte de proceso, pérdida/recuperación de red sin duplicados, retirada de notificaciones, revocación remota, nueva inscripción, retirada local offline y reinicio, confirmación posterior, exportación SAF con cancelación/rotación, auditor/texto al 150 % y TLS Android. Cada paso debe iniciar y finalizar una prueba, sin fallos/omisiones. El resultado completo exige también acabar toda la secuencia; un conjunto parcial en verde no aprueba la entrega.

Informes privados en `.tooling/android-native/<id>/`: `summary.json`, `verdict.json`, `environment.json` con versiones/hashes, `TEST-native.xml` y logs por paso. Nunca publicar los logs en bruto: pueden contener datos ficticios privados. Los archivos de entrada con credenciales se crean modo600 en almacenamiento privado de las apps y se retiran al terminar. El script devuelve error ante un paso fallido o protocolo incompleto.

No ejecutar compilaciones pesadas ni otros recorridos sobre los mismos emuladores mientras corre la suite. Un ANR de System UI sigue siendo un intento fallido. API26/API28 cubren las versiones estables disponibles; la aceptación en Android reciente y los dos teléfonos de entrega sigue teniendo evidencia separada.

## Dependencias, carga y restauración

```bash
python3 scripts/check_dependencies.py --prepare
python3 scripts/local_fixture.py run -- .venv/bin/python scripts/check_capacity.py
python3 scripts/check_restore.py
```

La revisión descarga Trivy 0.76.1 desde su publicación oficial y verifica el SHA256 fijado; obtiene la base de avisos desde GHCR, genera inventarios de las configuraciones runtime efectivamente resueltas y exige que se identifiquen todos sus paquetes. Falla ante avisos HIGH/CRITICAL; no contiene exclusiones de CVE. `--skip-db-update` sirve solo para comparar con la base registrada, no como sustituto de una revisión actual. No examina el SO/JDK, herramientas de prueba ni configuración operacional.

La carga añade 10 000 eventos ficticios por ejecución, compara las 100 páginas y el CSV por ID y mide cinco lectores concurrentes contra el fixture propio. No ejecutar sobre producción. Registrar recursos y ausencia/presencia de emuladores para interpretar la latencia.

Restauración pausa solo la API propia, crea una base nueva sin sobrescribir la original, restaura como migrador, compara las diez tablas y prueba disponibilidad/login/consulta desde otra API. Conserva el dump privado y ambas bases; su retención queda a cargo del operador. La prueba QA no sustituye cifrar y restaurar respaldos reales.

La comparación de la página restaurada usa los IDs y dispositivos esperados, con el mismo orden que la API; funciona también con cero o menos de 100 eventos. Se recuperan y comprueban los permisos runtime/mantenimiento. La API aislada de la copia recibe solo credenciales runtime; la pasada de retención se ejecuta explícitamente sobre la copia después de comparar datos y se exige una entrada nueva de auditoría. Cada restauración conserva su informe propio junto al respaldo.

## Paquete instalable de QA

Después de aprobar los 17 pasos sobre las APKs actuales:

```bash
python3 scripts/package_qa.py --native-report .tooling/android-native/IDENTIFICADOR --output .tooling/delivery/auditlab-qa.zip
```

Reemplazar `IDENTIFICADOR` por la ejecución completa recién aprobada. El paquete incluye las dos APKs debug, la distribución Ktor con sus bibliotecas, los manuales públicos y una copia de los archivos versionados en `source/`, incluidos wrappers y pruebas. `LEEME.md`, `manifest.json` y `SHA256SUMS` permiten instalar e identificar los archivos. Al extraer con `unzip`, comprobar `sha256sum -c SHA256SUMS` desde la carpeta extraída; se conservan los permisos de ejecución del servidor y wrappers.

El empaquetador rechaza una ejecución incompleta, un hash diferente en cualquiera de las cuatro APKs (apps e instrumentación), archivos ausentes/enlaces simbólicos y un destino ya existente. Comprueba además los bytes realmente incluidos para detectar una reconstrucción concurrente. No compila, firma, configura cuentas, incluye datos del fixture ni publica servicios. La firma debug permite desarrollo; la entrega del piloto requiere los pasos P8.1/P8.2/P8.4 descritos en el plan.
