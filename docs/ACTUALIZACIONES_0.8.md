# AuditLab 0.8 — versiones y actualizaciones confirmadas

31 de octubre de 2026, America/Lima. [Plan de esta ampliación](PLAN_ADMINISTRACION_ANDROID.md).
Agente código15, Centro código14, API con migración V10. Las descargas 0.7
anteriores no contienen estas funciones. Los resultados se registran en [AVANCE](AVANCE.md).

## Uso desde el Centro

1. En **Equipos**, abrir el teléfono correcto. Su nombre/archivo son locales al Centro.
2. En **Actualizar Agente**, pulsar **Consultar versiones y solicitud**.
3. Administrador: revisar la versión disponible y **Solicitar actualización**.
   Confirmar el destino. Auditor solo consulta.
4. En el Agente, abrir **Actualizaciones de AuditLab**. Con batería/conexión activo
   se consulta la solicitud como máximo una vez por minuto y se muestra un aviso;
   la red y Android pueden retrasarlo. Sin servicio, consultar desde la app.
5. El propietario elige **Descargar e instalar** o **Rechazar esta solicitud**.
   Si Android abre el ajuste de fuente desconocida, volver y pulsar el botón otra
   vez. No se inicia una descarga por volver de Ajustes.
6. Android permite confirmar o cancelar. Abrir nuevamente el Agente y consultar
   para verificar la versión instalada y notificar el resultado al servidor.
7. Para actualizar el propio Centro, abrir **Más → Actualizar Centro** y consultar.

Una solicitud no enciende el teléfono, no concede permisos y no instala a distancia.
La actualización no inicia WhatsApp, SMS/llamadas ni pantalla compartida. Si Android
detuvo el servicio durante el reemplazo, revisar su estado y activarlo localmente.
Forzar detención puede impedir avisos hasta abrir la app. La desinstalación se hace
desde Android; revocar credenciales o archivar no desinstala.

## Preparar el catálogo del servidor

El catálogo está vacío por defecto. Generar un archivo **nuevo** desde los APK
construidos y firmados que se vayan a distribuir:

```bash
python3 scripts/build_release_catalog.py --output .tooling/releases-0.8.json apps/agent/build/outputs/apk/debug/agent-debug.apk apps/control/build/outputs/apk/debug/control-debug.apk
```

Requiere Android SDK Build Tools y Java; no sube archivos ni habilita el catálogo.
Verifica la firma y obtiene paquete, versiones, minSdk, tamaño, SHA256 y certificado
del APK real. Revisar el JSON y publicar esos **mismos bytes** en las URLs indicadas
antes de habilitarlo. Si se recompila, generar y revisar otro catálogo.
No copiar hashes a mano ni publicar un catálogo que apunte a versiones inexistentes.
Solo se permiten estas apps y las rutas HTTPS de `descargas-auditlab` de este repo.

La API directa usa `AUDITLAB_RELEASE_CATALOG` con la ruta absoluta del archivo.
El lanzador Windows limpia variables heredadas: pasar su parámetro explícito,
que configura únicamente esa ruta aprobada para el proceso:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1 -ReleaseCatalog 'C:\AuditLab\releases-0.8.json'
```

Esta configuración se aplica al proceso; no contiene tokens. Cambiar catálogo
requiere reiniciar la API; las solicitudes existentes conservan su snapshot.
Sin configuración, `items` es vacío. Un catálogo configurado inválido impide el
arranque: no se sustituye silenciosamente por otro. Los APK nuevos necesitan una
versión superior y el mismo certificado que los instalados. Cambiar de firma exige
un procedimiento de migración independiente, no borrar datos para forzar actualización.

Antes de actualizar el servidor: respaldo verificado, detener la API de forma
ordenada y reemplazar la distribución completa Ktor, incluidos `lib/` y scripts.
Aplicar `migrate`, `grant-runtime` y `grant-maintenance`; el lanzador Windows lo
hace con su configuración existente. Actualizar también `Iniciar-AuditLab.ps1`
y añadir `Revisar-AuditLab.ps1` desde esta revisión; el lanzador antiguo no tiene
el parámetro `-ReleaseCatalog`. No borrar `.auditlab-windows` ni volúmenes.
La migración V10 conserva tablas previas; volver a un binario antiguo después de
migrar no es una restauración soportada. Probar la restauración en otra base.
El ZIP Windows0.7 publicado sigue siendo el anterior: no basta con cambiar su JSON.
Todo ZIP nuevo requiere el análisis y control de distribución del archivo exacto.

## Diagnóstico Windows de solo lectura

Desde CMD, en la carpeta del servidor actualizado:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\Revisar-AuditLab.ps1
```

Para comprobar HTTPS privado, añadir `-ServiceUrl https://TU-PC.TU-RED.ts.net:8443`.
El script no instala, borra, modifica Tailscale ni relaja TLS. Consulta Windows/RAM,
Java21, Docker, presencia de Tailscale y `/ready`. Una respuesta lista desde Windows
no acredita acceso desde cada teléfono. No ejecutado aquí en Windows físico.

## Estados y límites

| Estado | Significado |
| --- | --- |
| Esperando confirmación | Solicitud registrada; el propietario aún decide. |
| Instalación pendiente de verificar | Preparación confirmada para abrir instalador; no prueba que instaló. |
| Versión y firma verificadas por el Agente | El Agente comprobó su instalación y reportó autenticado. |
| Rechazada / Fallida / Vencida | No se afirma éxito; una nueva solicitud es independiente. |

Sin respuesta del servidor se conservan los datos anteriores indicando el error.
Último contacto refleja recepción de eventos, no presencia de la VPN ni lectura de
una solicitud. Una lectura antigua no demuestra que el teléfono esté apagado.
El servicio puede continuar con pantalla apagada; un teléfono apagado no ejecuta
código. Recuperación básica: [guía0.7.5](MONITOREO_BASICO_0.7.5.md).

`REQUEST_INSTALL_PACKAGES` permite pedir el instalador, no instalar en silencio.
FileProvider no está exportado y solo comparte el subdirectorio privado de APK.
Publicar en Google Play exige revisar sus políticas de permisos y actualización;
esta entrega se plantea como distribución directa autorizada. Firma actual debug,
clave de distribución del responsable y pruebas físicas pendientes.

## Reproducir las comprobaciones de actualizaciones

En emuladores dedicados de QA, con SDK/JDK configurados y el fixture aislado:

1. Construir candidatos **solo de QA** mediante las propiedades Gradle
   `auditlabAgentVersionCode`, `auditlabAgentVersionName`,
   `auditlabControlVersionCode` y `auditlabControlVersionName`; usar códigos
   superiores a los valores normales y un nombre como `0.8.1-qa`.
2. Guardarlos fuera de los outputs de Gradle, bajo `.tooling`.
3. Volver a construir sin esas propiedades tanto las apps normales como
   `assembleDebugAndroidTest`. Los valores normales son Agente15/Centro14.
4. Ejecutar `scripts/check_app_updates.py --serial emulator-N --app agent
   --candidate RUTA_APK_QA` y repetir con `--app control` en su emulador.
   Verifica el APK instalado por SHA256, abre/cancela el instalador del sistema
   y comprueba rechazo de alteración, otra firma y downgrade. No confirma instalar.
5. Generar un catálogo de QA desde esos candidatos y ejecutar
   `scripts/check_administration.py --agent-serial emulator-N --catalog RUTA_JSON_QA`.
   Configura temporalmente la API loopback con ese catálogo; prueba solicitud,
   aviso Android, rechazo local y revocación. Restaura el catálogo vacío al salir.
   Puede reemplazar únicamente la inscripción previa del emulador ligada al fixture
   `http://10.0.2.2:8081`; no acepta teléfonos físicos ni borra datos generales de app.
6. `scripts/check_basic_recovery.py --agent-serial emulator-N` prueba recuperación
   básica con reinicio real y detención. No reactivar comunicaciones desde el boot.

Los candidatos de QA no se publican ni se incorporan al catálogo operativo. Estas
pruebas no acreditan descarga pública de una versión aún no publicada, reemplazo
en teléfonos físicos ni funcionamiento en fabricantes no ensayados.

### Reemplazo confirmado y conservación de datos

`scripts/check_confirmed_upgrade.py` prueba un Agente en un emulador dedicado:

1. Preparar un candidato firmado más nuevo y volver a construir los outputs normales
   y de instrumentación, como arriba. En Ajustes Android del emulador, autorizar
   explícitamente AuditLab como fuente de instalación si es necesario.
2. Ejecutar `--agent-serial emulator-N --candidate RUTA_APK_QA --phase prepare`.
   Crea una inscripción del fixture y un evento pendiente; guarda huellas privadas
   de credencial/cola. Espera a que Android termine de preparar el APK y muestre
   **Instalar**. El script no pulsa ese botón.
3. Revisar y confirmar **Instalar** en Android. Esperar a que termine.
4. Ejecutar el mismo comando con `--phase verify`. La segunda instrumentación
   comprueba versión/firma, misma inscripción, credencial Keystore descifrable y
   mismo evento pendiente. Deja detenida la inscripción de QA al terminar.
5. `--phase cleanup` retira únicamente archivos temporales de esta prueba. El ajuste
   de fuente y la versión instalada se revisan/restauran por el operador; el script
   no modifica esos ajustes ni rebaja versiones.

Las dos invocaciones nativas están en `NativeUpgradePreservationTest`. No ejecutar
sus métodos seguidos sin la confirmación intermedia. Terminar instrumentación antes
de que Android copie el APK revoca las concesiones URI del ensayo: por eso se
espera la página final de confirmación. No se amplía FileProvider ni se hace público.
