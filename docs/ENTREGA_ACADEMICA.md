# AuditLab — entrega técnica de la fase 1 académica

Ámbito: una app Android local, educativa y sin red, desarrollada mediante I01–I12. Crea eventos ficticios; no captura llamadas, SMS, notificaciones ni datos de otras apps. Los módulos históricos de dos apps/API quedan conservados como referencia. Este documento no declara conformidad con un PDF/rúbrica que todavía no se ha recibido.

## Instalar y demostrar

1. Usar el APK **app-debug.apk**, paquete `com.auditlab.academic`, versión `0.1.0-i12`/12. Es una compilación de desarrollo firmada; el release sin firma no es el instalador de esta entrega.
2. Instalar en un Android propio API26+ con depuración autorizada: `adb install -r app-debug.apk`. La cobertura realmente ejecutada se identifica en el informe I12; minSdk no acredita por sí solo todos los dispositivos.
3. Abrir AuditLab. Sin marcar/confirmar aceptación, simular y exportar permanecen bloqueados.
4. Aceptar explícitamente y pulsar las cuatro opciones. Revisar SIMULADO, ID y fecha/zona; cerrar/abrir conserva los eventos confirmados.
5. Exportar CSV con el selector Android. Comprobar filas/IDs y abrir como UTF-8. Cancelar informa cancelación. La copia externa queda bajo control del usuario/proveedor.
6. Borrar historial: cancelar conserva los datos; confirmar elimina el historial y conserva la aceptación.
7. Revocar: confirmar guarda primero la retirada y luego intenta borrar. Si falla la limpieza, no se reactiva la aceptación; hay un reintento disponible. Volver a aceptar requiere una nueva acción explícita y no recupera eventos eliminados.

Los mensajes y casos de recuperación se explican en [USO_ACADEMICO.md](USO_ACADEMICO.md). No ejecutar pruebas destructivas sobre datos que se quieran conservar: los tests automatizados usan bases temporales con UUID; el recorrido manual de cierre se realiza solo en el emulador propio de pruebas, con respaldo/restauración de su estado inicial.

## Repetir la verificación completa

Herramientas exigidas: JDK17, Gradle9.3.1 mediante wrapper, AGP9.1.1, SDK/target36 y build-tools36.0.0; Kotlin JVM2.2.10 en domain y Kotlin integrado en AGP en app. No se añadieron librerías para las herramientas Python.

Desde la raíz del repositorio, en Linux con SDK y JDK preparados y un dispositivo autorizado conectado:

```bash
python3 academic/tools/verify.py --sdk-root /RUTA/SDK --java-home /RUTA/JDK17 --android-serial emulator-5554
```

En esta nube se utiliza el helper existente de herramientas/proxy/CA:

```bash
python3 academic/tools/verify.py --sdk-root .tooling/android-sdk --java-home .tooling/jdk17 --gradle-helper .tooling/academic-setup/run-gradle.py --android-serial emulator-5554
```

El directorio de salida predeterminado es `academic/build/verification/FECHA_UTC/`; puede seleccionarse otro con `--output`, siempre nuevo. El comando conserva los resultados anteriores. Sin `--android-serial` solo acredita comprobaciones host: lo declara en el resumen y no cuenta Android como aprobado.

El verificador:

- Registra revisión base, hash del árbol de fuentes y si hay cambios locales; rechaza cambios de fuente durante la comprobación.
- Ejecuta 93 JVM, 15 SQL host y 23 pruebas de las herramientas (14 de política y 9 del lector/protocolo de evidencias).
- Compila debug, tests y release; exige lint sin incidencias en debug/release.
- Con dispositivo, instala app/tests mediante `adb install -r -t`, ejecuta Instrumentation directamente y exige 55 casos reales, únicos y sin fallos/errores/omitidos. No desinstala la app ni borra sus datos; una firma/versión incompatible falla. Rechaza informes vacíos, antiguos, duplicados, incompletos o con conteos incoherentes, incluidos XML agrupados de Gradle.
- Revisa manifiesto y reglas de backup efectivamente empaquetados, firma debug y coincidencia de firma app/tests. Comprueba aislamiento de fixtures de test en Dex.
- Conserva logs, XML, tamaños y SHA-256. Genera `summary.json` y `auditlab-verificacion.zip` con el APK debug y sus evidencias. Los APKs de tests/release sin firmar quedan fuera de ese ZIP de instalación.

## Matriz de aceptación

La evidencia exacta de la versión final, dispositivo/API, hashes y límites está en [I12](evidencias/academico/I12-2026-10-23.md). La [comprobación adicional en dos emuladores](evidencias/academico/DOS-EMULADORES-2026-10-23.md) aprobó 55/55 casos en API26 y 55/55 en API28 con la misma APK. La siguiente tabla indica dónde repetir cada conducta; no sustituye los resultados del informe.

| Criterio | Evidencia reproducible |
| --- | --- |
| AC-01 — instalar/abrir y reproducir build | verify.py, instalación real e identificación de la APK/firma; Sync IDE separado. |
| AC-02 — rechazo/pendiente/error bloquean | AcceptanceServiceTest, SimulationUiTests, SQLiteAcceptanceTests y recorrido inicial. |
| AC-03 — cuatro eventos válidos por pulsación | ModelTest, AuditServiceTest, botones y pulsaciones rápidas; sin comunicaciones reales. |
| AC-04 — conservación confirmada al reabrir | SQLiteEventTests/SQLiteAcceptanceTests y detención/relanzamiento real con comparación de IDs/contexto. |
| AC-05 — cupos 1000/100 y orden | RepositoryContractTest, AuditServiceTest y SQLiteEventTests con dos conexiones y límites persistidos. |
| AC-06 — CSV y correspondencia de instantánea | CsvReportTest, ExportServiceTest y exportación de 150 filas/CSV real de cuatro IDs. |
| AC-07 — selector, cancelar y fallar | ExportUiTests: URI inválida, selector ausente/restringido, apertura/escritura fallida; documento previo conservado. |
| AC-08 — borrar y conservar aceptación | PrivacyUiTests, transacciones SQLite y recorrido con cancelación/confirmación/reinicio. |
| AC-09 — retirar antes de borrar y reintentar | Pruebas independientes de guardar retirada, DELETE y confirmación; recorrido con fallo de DELETE y muerte de proceso. |
| AC-10 — rotación/repetición/resultados obsoletos | SerialOperationQueueTest, AuditConcurrencyTest, SimulationUiTests, PrivacyUiTests y selector real durante rotación. |
| AC-11 — configuración/datos limitados | Política de fuente/APK, backup/transferencia, decodificación dañada, intento externo sin aceptación e [I11/SSDF](SEGURIDAD_ACADEMICA.md). |
| AC-12 — entrega identificada y demostrable | Este documento, manual, plan, resumen con hash/firma, matriz y resultados/pendientes I12. |

## Explicación breve para defender el proyecto

`domain` define modelos y reglas sin Android. `app` aporta pantalla, SQLite, selector y ciclo de vida. AuditApplication conserva una AuditSession por proceso: la rotación no crea otra cola ni vuelve a generar eventos. El coordinador y las transacciones evitan carreras; el cupo se consulta en SQLite, no en un contador de pantalla.

La revocación se guarda antes del borrado en otra transacción: un fallo de DELETE no recupera la aceptación. Si falla guardar la retirada inicial, la pantalla no promete persistencia; bloquea la sesión y permite reintentar. La exportación vuelve a comprobar la aceptación justo antes de abrir el documento. Si el proveedor rechaza esa apertura, no se elimina su archivo anterior.

La revisión encontró defectos reales y añadió regresiones que fallaban antes de corregirlos. Aprobar pruebas JVM no acredita Android, aprobar un emulador no acredita todos los teléfonos y guardar una configuración no publica la app. El informe final mantiene esas evidencias separadas.

## Elementos que requieren evidencia externa

La conformidad con PDF/rúbrica, Sync de Android Studio y teléfono físico se registran cuando se realicen. La distribución release requiere llave/procedimiento del curso. La fase 2 necesita un alcance nuevo. La situación de Android 16 se documenta con sus intentos y resultados, sin atribuirle las pruebas de Android 9.
