# AuditLab académico — uso de la entrega I12

Aplicación local para **simular** llamadas/mensajes. No lee comunicaciones ni usa backend, login, claves API o un segundo dispositivo. La fase 2 sigue fuera de alcance.

## Abrir y compilar

Abrir la carpeta `academic/` en Android Studio, seleccionar JDK17 y SDK36 y ejecutar Sync. Herramientas fijadas: AGP9.1.1, Gradle9.3.1, Kotlin JVM2.2.10 en domain; app utiliza Kotlin integrado en AGP. Android mínimo API26. No cambiar estas versiones para evitar un error de configuración sin registrar causa y aprobación.

Desde `academic/`:

```bash
./gradlew :domain:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

APK principal: `academic/app/build/outputs/apk/debug/app-debug.apk`. Paquete `com.auditlab.academic`, versión `0.1.0-i12`/12. Es una compilación debug para desarrollo académico; se instala junto a las apps históricas porque usa otro identificador. Con un dispositivo propio/emulador autorizado conectado:

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Los comandos de instalación anteriores parten de `academic/`. El botón Run de Android Studio también sirve. Esta guía no acredita un Sync realizado: los comandos y dispositivos efectivamente comprobados se registran en las evidencias.

## Recorrido de uso

1. Leer la política y marcar la casilla. **Aceptar** guarda ID, versión y fecha de la decisión, sin crear eventos automáticamente. **Continuar sin aceptar** mantiene la simulación bloqueada.
2. Pulsar Llamada entrante/saliente o Mensaje entrante/saliente. Cada operación admitida crea un evento ficticio con ID propio; durante el trabajo los controles se deshabilitan para evitar duplicados.
3. Consultar el historial desplazando la pantalla. Cada fila indica SIMULADO, fecha/zona e ID. Se muestran hasta 100 recientes y se almacenan hasta 1000; al alcanzar el cupo se rechaza el siguiente, sin reemplazar registros.
4. **Exportar CSV** abre el selector Android. Elegir destino y guardar, o cancelar. Se exportan todos los registros de la instantánea tomada al guardar, no solo los 100 visibles, con UTF-8 y timestamps numéricos. La pantalla informa la cantidad, incluso cero. El permiso de aceptación vuelve a validarse antes de abrir el destino.
5. **Borrar historial** solicita confirmación y conserva la aceptación. Cancelar no cambia nada. Confirmar elimina los registros locales, permitiendo simular otros.
6. **Revocar aceptación** explica y confirma la retirada. Guarda primero la retirada, bloquea simulación/exportación e intenta borrar. Una nueva aceptación requiere volver a marcar la casilla y confirmarla; nunca restaura datos borrados.

## Estados y recuperación

| Estado visible | Qué significa / acción |
| --- | --- |
| Cargando / operación en curso | Esperar. No se presenta un cambio como guardado antes de confirmarlo. |
| Aceptación pendiente | Se necesita una acción explícita de aceptación para simular/exportar. |
| Aceptación vigente | Simulación y exportación disponibles cuando no hay otra operación en curso. |
| Error de lectura o política cambiada | No se habilitan acciones con una decisión desconocida/antigua. Reintentar lectura o aceptar la nueva política según corresponda. |
| Revocada, borrado pendiente | La retirada sí está guardada. No se permite nueva aceptación hasta completar la limpieza. Usar Reintentar borrado pendiente. |
| Retirada guardada, confirmación de limpieza pendiente | Los eventos se borraron, pero falta guardar esa confirmación. Reintentar; el borrado es idempotente. |
| Revocación no confirmada | Falló guardar la retirada. Las acciones protegidas quedan bloqueadas en esta sesión. Usar Reintentar guardar revocación. No se promete retirada tras reiniciar mientras no se confirme la escritura. |
| Selector ausente/restringido | Se informa error, se libera la operación y se permite reintentar. |
| Fallo al exportar | No se informa éxito. Si el destino llegó a abrirse, se intenta borrar la salida parcial; una apertura rechazada no elimina el documento anterior. si el proveedor lo rechaza puede quedar un archivo vacío/parcial. Revisar el destino antes de reintentar. |

Rotar no debe crear registros ni duplicar una operación. Un selector pendiente conserva su ID durante recreación; si se pierde el proceso, no se recupera un permiso de exportación obsoleto. Borrar/revocar invalidan el selector pendiente. Las confirmaciones abiertas se descartan al dejar la Activity; no se confirman por sí solas al volver.

Las copias CSV ya exportadas quedan bajo el control del usuario y su proveedor; AuditLab no las elimina al borrar/revocar datos locales. No hay retención temporal automática ni transmisiones. No se solicitan permisos de llamadas, SMS, contactos, accesibilidad o almacenamiento amplio.

## Pruebas reproducibles

El flujo recomendado conserva los datos: desde la raíz, con JDK17/SDK36 y un Android propio autorizado:

```bash
python3 academic/tools/verify.py --sdk-root /RUTA/SDK --java-home /RUTA/JDK17 --android-serial emulator-5554
```

Compila y actualiza app/tests con `adb install -r -t`; luego ejecuta Instrumentation directamente. No desinstala la app ni borra sus datos. Si la firma/versión es incompatible, falla sin intentar resolverlo desinstalando. El APK de tests usa bases temporales distintas de la app normal.

El comando `./gradlew :app:connectedDebugAndroidTest`, desde `academic/`, también funciona en un dispositivo dedicado a pruebas, pero en este entorno dejó la app desinstalada al terminar. No usar ese instalador con datos que se quieran conservar. El verificador recomendado guarda resultados en un directorio nuevo de `academic/build/verification/`; consulte `summary.json`, XML y logs, no solo la salida de compilación.

El runner propio utiliza el protocolo Instrumentation y bases temporales diferentes de la aplicación normal. El verificador exige 55 casos completos y sin omisiones. Los informes del comando conectado están en `app/build/outputs/androidTest-results/connected` y `app/build/reports/androidTests/connected`. La prueba no está aprobada si solo compila el APK o ejecuta cero casos. Para diagnóstico, tras instalar ambos APKs:

```bash
adb shell am instrument -w -r -e testGroup PrivacyUiTests com.auditlab.academic.test/com.auditlab.academic.tests.AuditLabTestRunner
```

Sin `-e testGroup ...` ejecuta la suite completa. Filtros disponibles: SQLiteEventTests, SQLiteAcceptanceTests, SimulationUiTests, ExportUiTests y PrivacyUiTests; uno inexistente se rechaza. JVM utiliza kotlin.test/JUnit4 y los fakes no prueban Android. El checker adicional `python3 academic/tools/check_sqlite_schema.py`, desde la raíz, comprueba el DDL con SQLite host, sin sustituir instrumentación.

La nube conserva un helper en `.tooling/academic-setup/run-gradle.py` para seleccionar herramientas/proxy/CA correctos. No usar el helper del build raíz anterior para esta app. Los emuladores por software deben ejecutarse de uno en uno; procesos y permisos del entorno no se presumen restaurados automáticamente. API26 y API28 completaron los 55 casos cada uno en la [comprobación de dos emuladores](evidencias/academico/DOS-EMULADORES-2026-10-23.md); API36 sufrió ANR de componentes del emulador y sigue sin validación completa. Para repetir esa comprobación, usar una instancia estable con aceleración KVM o un dispositivo propio compatible. No subir timeouts ni ocultar errores para declarar la suite aprobada.

Resultados observados, límites y siguiente incremento: [avance académico](AVANCE_ACADEMICO.md). Diseño del almacenamiento: [persistencia](PERSISTENCIA_ACADEMICA.md). I11 documenta la [revisión SSDF](SEGURIDAD_ACADEMICA.md); la [guía de entrega I12](ENTREGA_ACADEMICA.md) y su [informe](evidencias/academico/I12-2026-10-23.md) identifican versión, comprobaciones y límites.
