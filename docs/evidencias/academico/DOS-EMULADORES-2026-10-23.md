# I12 — comprobación adicional en dos emuladores

Fecha: 23 de octubre de 2026, UTC. Solicitud: «Pruébalo con dos emuladores». Ámbito: la misma app académica local de I01–I12, versión **0.1.0-i12/12**, instalada en dos versiones de Android. Esta comprobación no acredita comunicación entre las dos apps del sistema ampliado histórico.

## Resultado real

| Emulador | Sistema | Casos iniciados/completados | Aprobados | Fallos/errores/omitidos | Tiempo de Instrumentation |
| --- | --- | --- | --- | --- | --- |
| `emulator-5558` | Android 8.0.0, API26, x86_64 | 55/55 | **55** | **0/0/0** | 199.112 s |
| `emulator-5554` | Android 9, API28, x86_64 | 55/55 | **55** | **0/0/0** | 200.235 s |

Son **55 casos distintos repetidos en dos sistemas: 110 ejecuciones aprobadas**. Se ejecutaron de uno en uno: la máquina no dispone de `/dev/kvm` y emplea emulación por software. La cobertura de API26 ahora incluye el mínimo admitido por la app; no acredita todos los modelos ni Android16.

| Grupo | API26 | API28 |
| --- | --- | --- |
| SQLiteEventTests: persistencia, cupo, duplicados, datos inválidos y concurrencia | 13/13 | 13/13 |
| SQLiteAcceptanceTests: consentimiento, retirada, migración y fallos independientes | 16/16 | 16/16 |
| SimulationUiTests: bloqueo inicial, cuatro eventos, pulsaciones, recreación e intent externo | 7/7 | 7/7 |
| ExportUiTests: CSV, cancelación, apertura/escritura fallida y selector ausente/restringido | 10/10 | 10/10 |
| PrivacyUiTests: borrar/revocar/cancelar, fallos de retirada/limpieza y reintentos | 9/9 | 9/9 |

El recolector estricto de `academic/tools/verify.py` volvió a validar identidades únicas, inicio/finalización, conteo esperado de 55, marcador final y XML sin fallos, errores u omisiones. Los dos procesos devolvieron código 0. No se modificaron fuentes, aserciones ni plazos de las pruebas.

Además se abrió la **Application normal** en API26, fuera de Instrumentation: `am start -W` devolvió éxito (2483 ms). La captura y el árbol UI muestran la marca SIMULADO y «Aceptación pendiente», checkbox sin marcar y acciones protegidas visibles deshabilitadas. Se confirmó que había cero eventos y cero consentimientos GRANTED leyendo una copia de la base y de cualquier WAL/SHM después de detener el proceso propio de QA. No se borraron datos ni se otorgó aceptación para obtener esta comprobación.

## Identidad y preservación

- Revisión de las fuentes comprobadas: `8ff61ab6d9fef761dccd34c3f05e5f0a55a7baca`.
- Árbol académico SHA-256: `b5ac2a88c6656eba727a8f7e7bfdd0e4836a118b614ffd8172267c178e97a4ff`.
- APK debug: 917373 bytes; SHA-256 `10232d8fbea4894f0bc6edce355cd2204648b9fa69b0cc64327d9ebed25361f4`.
- APK de tests: 62568 bytes; SHA-256 `35839397efc0f3d06046412e573a8818f39a800a79a0689d965f7528a75dac13`.

Se reutilizaron exactamente los artefactos de la entrega I12; no se cambió ninguna dependencia de la app. La imagen adicional API26 procede del SDK oficial. Instalación mediante `adb install -r -t`; no se desinstaló ni limpió la app. Los tests usan bases temporales. En API28 se comparó el volcado lógico de la base normal antes/después: SHA-256 idéntico `899045c518edfbf5f5ddbcbb66118d2aa7826a8d3db90dc1a678932b1657b770`. API26 usa un AVD nuevo sin datos previos.

Configuración: Android Emulator 37.2.12, dos vCPU por AVD, sin aceleración KVM, SwiftShader, Vulkan deshabilitado y 2048 MiB. API28 conserva su pantalla 1080×1920/densidad420; API26 utiliza 720×1280/densidad320. Los comandos exactos y PID/AVD están en los registros de proceso. Las pruebas JVM/host/lint no se repitieron en esta sesión: sus resultados de construcción y firma permanecen en el [informe I12](I12-2026-10-23.md).

## Intentos de preparación y Android16

Los intentos fallidos no se borraron ni se sumaron a las ejecuciones aprobadas:

1. El primer lanzamiento de API28 omitía `-accel off` y el emulador lo rechazó por falta de KVM. Se corrigió el comando de arranque; posteriormente se completaron los 55 casos.
2. En el primer arranque de API26, el comando preparatorio `input keyevent 82` excedió su límite host de 20 s. Todavía no se habían iniciado tests. A continuación se ejecutó directamente el mismo recolector estricto y los 55 casos finalizaron satisfactoriamente.
3. Se preparó además un AVD nuevo de **Android16/API36**, con Lavapipe, `LP_NUM_THREADS=2`, 4096 MiB y pantalla480×800/densidad160. Durante el arranque, antes de instalar AuditLab, el log registró `WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on main thread (main) for 62s`; también quedaron un reinicio de `system_server` y una ventana de ANR de System UI en el volcado previo a la instalación. El sistema llegó a completar el arranque a los 573 s de espera y se dejaron otros 120 s de estabilización.
4. La captura posterior de API36 **muestra el menú del launcher, no un diálogo ANR visible**. El agente interrumpió ese intento durante la instalación del APK de tests por la inestabilidad observada. Por tanto, el error de instalación posterior corresponde a esa interrupción deliberada; **Instrumentation no llegó a ejecutarse y no hay un resultado funcional de AuditLab en ese intento**. No se presenta como un fallo de la app ni como una prueba aprobada. Los tres intentos históricos de I10/I12 conservan sus resultados originales.

API36 sigue pendiente de una ejecución completa en un entorno estable. Se conservaron los AVDs y sus registros. No se aumentaron tiempos de las aserciones ni se ocultaron diálogos para obtener un aprobado.

La comprobación adicional del estado normal API26 tuvo dos ajustes del conductor host: se corrigió la cita de un comando SQL y después se comprobó que esa imagen no incluye `/system/bin/sqlite3`. Se conservaron ambos errores y se leyó una copia quiescente DB/WAL mediante `sqlite3` de Python. La aplicación no se modificó. El hash antes/después de la base normal se acreditó solo en API28; en API26 no había datos previos y se verificó por separado el estado normal final.

## Evidencias y repetición

Directorio local de esta ejecución:

```text
.tooling/academic-setup/dos-emuladores-20261023T030952Z/
  api26/summary.json
  api26/instrumentation.log
  api26/reports/TEST-instrumentation.xml
  api28/summary.json
  api28/instrumentation.log
  api28/reports/TEST-instrumentation.xml
  api26-normal-launch/summary.json
  api26-normal-launch/app.png
  api36/summary.json
  api36-environment-blocker.json
  api36-continuous-logcat.log
  pre-test-window.log
  pre-test-screen.png
```

Para repetir en un emulador autorizado estable, usar el flujo reproducible de [entrega](../../ENTREGA_ACADEMICA.md) con su número de serie. En esta sesión se reutilizó la APK identificada arriba y se lanzó directamente:

```bash
adb -s SERIAL install -r -t app-debug.apk
adb -s SERIAL install -r -t app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r com.auditlab.academic.test/com.auditlab.academic.tests.AuditLabTestRunner
```

Los informes y artefactos son locales. Teléfono físico, Sync IDE, rúbrica/PDF y distribución release conservan sus pendientes. Las funciones de red/dos apps/API pertenecen al alcance histórico, no a esta app académica local.

El paquete local `AuditLab-pruebas-dos-emuladores.zip` reúne el APK debug identificado, este informe, los dos XML/logs/resúmenes aprobados, la captura de apertura API26 y la explicación del intento API36 interrumpido. Excluye bases de datos, llaves, APK de tests y release sin firma. El ZIP original de entrega I12 se conserva sin sobrescribirlo.
