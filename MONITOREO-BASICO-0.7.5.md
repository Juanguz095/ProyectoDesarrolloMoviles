# Agente 0.7.5 — batería y conexión con recuperación visible

30 de octubre de 2026, America/Lima. P5.3/P5.4/P7.1/P8.2/P8.3.
Compatible con Centro 0.7.4 y servidor 0.7/V9. No requiere cambiar URL, servidor
ni inscripción. Las modificaciones ya están integradas; no pegarlas por duplicado.

## Cómo usarlo

1. Instalar el Agente actualizado sobre el anterior conservando sus datos.
2. Leer/aceptar el consentimiento e inscribir el equipo si todavía no está inscrito.
3. En Android 13 o superior se solicita una vez la notificación después de la
   primera inscripción válida. Si se deniega, no se inicia el monitoreo básico;
   se puede volver a solicitar desde su botón o revisar los ajustes Android.
4. En Monitoreo, buscar la tarjeta **Batería y conexión**. No necesita READ_SMS,
   READ_CALL_LOG, cámara, micrófono ni acceso a notificaciones de otras apps.
5. Si se desea recuperación automática, marcar **Reanudar estado básico al encender,
   mientras siga vigente mi consentimiento**. Está desmarcada inicialmente.
6. Pulsar **Activar batería y conexión**. Esta acción guarda la elección para esa
   inscripción y ese consentimiento. Aparece **AuditLab · Batería y conexión**, con
   acceso visible a la app y **Detener estado básico**.
7. Cerrar la actividad permite continuar el servicio mientras Android lo permita.
   Con recuperación habilitada, al reiniciar se intenta recuperar solo ese servicio
   después de que el almacenamiento protegido esté disponible, normalmente tras
   desbloquear por primera vez. No pide otra vez permisos que siguen concedidos.
8. **Detener estado básico**, **Pausar monitoreo** o **Retirar consentimiento**
   desactivan la recuperación. Activar posteriormente solo el indicador general
   no vuelve a aceptar la recuperación por el usuario.

El teléfono apagado no puede medir ni transmitir. Forzar detención en Android,
desinstalar, borrar datos o ciertas restricciones del fabricante pueden impedir
el inicio hasta abrir la app. No se promete ejecución permanente ni excepción
automática al ahorro de batería. Nunca se oculta la interfaz ni su identidad.

## Qué se recupera y qué necesita activación manual

- El nuevo servicio solo registra `device_status`: `batteryPercent`, `networkState`
  y el código permitido `device_status_changed`. Consulta cada 10 segundos y registra
  cambios o una renovación al minuto. La red y la planificación pueden retrasarlo.
- Se utiliza la cola Room y el transporte autenticado existentes. Eventos pendientes
  previamente autorizados conservan su política de envío/retención, sin capturar
  nuevos contenidos de comunicaciones durante la recuperación.
- No registra observadores de SMS/llamadas ni arranca sus lectores. No inicia
  WhatsApp ni MediaProjection. Esas sesiones se activan manualmente y la pantalla
  requiere autorización del sistema.
- El modo básico y la captura real de SMS/llamadas son excluyentes: activar el
  básico detiene esa captura. Activar captura real detiene el básico y borra su
  recuperación. Esto evita reactivar fuentes sensibles al reiniciar.
- Una revocación remota que todavía no llegó al teléfono se detecta al contactar
  con el servidor. El receptor comprueba el estado local; no puede conocer una
  decisión remota mientras está sin conexión.

## Dónde está cada fragmento en el código

### 1. Permisos y declaración de componentes

Archivo: [AndroidManifest.xml](../apps/agent/src/main/AndroidManifest.xml).

Dentro de `<manifest>` se declara el permiso normal del sistema:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Dentro de `<application>` están `BasicBootReceiver` y `BasicMonitoringService`.
Ambos están sin exportar. El servicio declara `specialUse` y una descripción
explícita de batería/conexión visible; no usa el tipo cámara/micrófono/pantalla.
La publicación en una tienda puede exigir revisión del uso de ese tipo de servicio.

### 2. Petición inicial y controles de la pantalla

Archivo: [MainActivity.kt](../apps/agent/src/main/java/com/auditlab/agent/MainActivity.kt).

- En `onCreate`, el `LaunchedEffect` espera inscripción válida, sesión preparada y
  ninguna operación pendiente. Guarda `notificationsAsked` para evitar abrir el
  diálogo en cada entrada y solicita POST_NOTIFICATIONS solo si falta en Android 13+.
- `onRequestRealMonitoring` filtra los permisos de SMS/llamadas ya concedidos;
  solo solicita los faltantes cuando el usuario activa esa función opcional.
- En la vista de equipo inscrito se añade `BasicMonitoringControls()`. Su checkbox
  y botón son la aceptación explícita de la recuperación básica. Solicitar un
  permiso por sí solo no crea una inscripción ni acepta el arranque automático.

### 3. Recibo de autorización y condiciones para recuperar

Archivo: [BasicMonitoring.kt](../apps/agent/src/main/java/com/auditlab/agent/BasicMonitoring.kt).

- `BasicMonitoring.start(autoResume)` valida inscripción y visibilidad, guarda el
  identificador de equipo/consentimiento y la elección en preferencias privadas,
  y solicita el servicio visible. No guarda tokens en ese recibo.
- `BasicRecoveryPolicy.allows(...)` rechaza falta de opt-in, otro equipo/consentimiento,
  pausa, notificaciones bloqueadas, retirada o almacenamiento bloqueado.
- `BasicMonitoring.stop()` elimina el recibo y detiene el servicio. Ante un fallo
  de escritura bloquea transmisiones y pausa el indicador; no afirma que se guardó.

### 4. Recepción del reinicio

En ese mismo archivo, `BasicBootReceiver.onReceive` acepta únicamente
`Intent.ACTION_BOOT_COMPLETED`. Usa `goAsync()` y una tarea IO para comprobar el
estado sin bloquear el hilo del receptor. Tras comprobar la política, su único
destino es `BasicMonitoringService`. No arranca las sesiones de comunicaciones.

### 5. Servicio en primer plano

En `BasicMonitoringService.onStartCommand`:

1. Se muestra inmediatamente la notificación de primer plano de AuditLab.
2. Se vuelve a validar el recibo, la inscripción y la disponibilidad del indicador.
3. El bucle registra únicamente batería/conexión bajo el bloqueo compartido.
4. Se mantiene el envío autenticado y la cola/backoff existentes.
5. El botón Detener elimina la autorización de recuperación. Un reinicio de proceso
   solo usa START_STICKY cuando se eligió la recuperación, y vuelve a comprobarla.

### 6. Pausa, retirada y exclusión de modos

Archivos:

- [AgentRuntime.kt](../apps/agent/src/main/java/com/auditlab/agent/AgentRuntime.kt):
  crea el controlador básico y detiene productores ante revocación/bloqueo.
  La ausencia transitoria del indicador al crear el proceso no borra el recibo
  antes de que el servicio de arranque pueda mostrarlo.
- [AgentSession.kt](../apps/agent/src/main/java/com/auditlab/agent/AgentSession.kt):
  pausa y retirada desactivan recuperación; Enviar estado ahora acepta modo básico.
- [RealEventMonitor.kt](../apps/agent/src/main/java/com/auditlab/agent/RealEventMonitor.kt):
  activar captura real manual detiene el modo básico y borra su recuperación.

## Pruebas y límites

`BasicRecoveryPolicyTest`: autorización vinculada a la inscripción, falta de opt-in,
pausa, permisos, retirada y bloqueos. `AgentSchedulingTest` y `RoomAgentJournalTest`
comprueban regresiones de programación/cola. `NativeBasicRecoveryTest` y
`scripts/check_basic_recovery.py` realizan dos invocaciones separadas por un reinicio
real del AVD de QA, comprobando la presencia del servicio antes de abrir una actividad.

Los resultados finales y las versiones Android comprobadas están en
[evidencia de la revisión](evidencias/BASICO-0.7.5-2026-10-30.json).
El resultado en un emulador no acredita fabricantes, teléfonos físicos ni todas
las versiones Android. No se validan capturas de comunicaciones con este ensayo.

Prueba nativa: recuperación observada por el host antes de instrumentar; la instrumentación
reinicia el proceso y vuelve a enganchar el mismo servicio autorizado para comprobar
el botón Detener. Se comprueba además que el contador de arranques del sistema cambió.
