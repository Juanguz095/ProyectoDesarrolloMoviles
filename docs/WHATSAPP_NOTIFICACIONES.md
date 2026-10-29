# Vistas previas de WhatsApp — ampliación opcional

## Qué comparte

Con la autorización del propietario del Agente, comparte el remitente/título y el
texto que Android entregue en **notificaciones nuevas** de WhatsApp y WhatsApp
Business. Funciona por Internet cuando ambos teléfonos alcanzan la misma API
HTTPS. No requiere compartir pantalla ni mantener abierta la actividad del Agente.

No recupera conversaciones completas, historial, mensajes sin notificación,
archivos adjuntos, mensajes enviados que no se notifiquen ni texto ocultado por
WhatsApp/Android. Los resúmenes de grupo se descartan. En notificaciones agrupadas
se filtran mensajes anteriores al consentimiento y respuestas propias reconocibles.
Si Android omite la identidad del usuario, se excluye también el nombre local por
precaución: un contacto con el mismo nombre puede quedar omitido.

## Activar y detener

1. Actualizar **Agente, Centro y servidor** a la revisión que incluye Flyway V5/V6.
   No basta actualizar solamente los APK.
2. Inscribir el Agente y activar su indicador visible de monitoreo.
3. En Monitoreo, abrir **Configurar vistas previas de WhatsApp**.
4. Leer la explicación y abrir **Configurar acceso de Android**. En los ajustes
   del teléfono, conceder acceso a notificaciones a AuditLab. Android ofrece un
   permiso general; el código de AuditLab filtra los dos paquetes de WhatsApp.
5. Volver a AuditLab. Cuando el servicio indique que está conectado, marcar la
   autorización adicional y pulsar **Autorizar e iniciar sesión**. Conceder el
   permiso de Android por sí solo no inicia la lectura ni el envío.
6. En el Centro, abrir el detalle del dispositivo y **Ver notificaciones de
   WhatsApp**. Pueden consultar administradores y auditores. El Centro no puede
   iniciar la captura remotamente.
7. Usar **DETENER** en la notificación persistente, detener desde el Agente,
   pausar el monitoreo, retirar el consentimiento o revocar el acceso de Android.
   Cada nueva sesión necesita otra autorización local. No se reinicia al arrancar
   el teléfono o tras morir/forzar la detención del proceso.

Cerrar la actividad del Agente mantiene el servicio visible mientras Android lo
permita. El ahorro de batería o el fabricante pueden detenerlo: no se promete
funcionamiento ilimitado en segundo plano ni después de forzar su detención.

## Datos y acceso

- Texto limitado a 2000 caracteres y título a 200; el resto de la notificación no
  se transmite.
- Cola de salida solo en RAM: 20 vistas previas; deduplicación acotada por sesión.
  No se usa Room/WorkManager para texto, ni se reenvía contenido tras otro inicio.
- Servidor: últimas 20 vistas previas por dispositivo, hasta 5 minutos desde su
  recepción. Una sesión sin actividad/heartbeat durante 30 segundos se cierra y
  pierde su contenido. Reiniciar el servidor también descarta el contenido.
- Detener limpia la sesión; si no hay red, la eliminación en servidor puede tardar
  hasta 30 segundos. Los envíos pendientes en memoria se descartan.
- Token de dispositivo para enviar; JWT admin/auditor para consultar; aislamiento
  por dispositivo, sesión UUID y contexto de consentimiento base comprobado.
- Auditoría SQL: inicio/fin, identificador de sesión, aceptación y accesos. **Sin
  título ni cuerpo de los mensajes**. No se incluyen en eventos, CSV ni respaldos SQL.
- El Centro consulta cada 3 segundos, elimina la vista al perder conexión, salir o
  detenerse la sesión. La pantalla usa FLAG_SECURE para impedir capturas y vistas
  recientes del sistema; no puede impedir que una persona copie lo que leyó.
- HTTPS en entrega; HTTP solo en el fixture local de depuración. No añadir logs de
  cuerpos de solicitud ni proxies de grabación de contenido sensible.

Contrato: `contracts/whatsapp-preview.openapi.yaml`. Tareas P3.8/P5.6/P6.6/P7.6.

## Comprobaciones reproducibles

```bash
python3 scripts/gradle.py :apps:common:test :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest
python3 scripts/check_database.py
python3 scripts/gradle.py :apps:agent:assembleDebug :apps:control:assembleDebug :apps:agent:assembleDebugAndroidTest :apps:control:assembleDebugAndroidTest
python3 scripts/check_whatsapp_pair.py --agent-serial emulator-5592 --control-serial emulator-5594
```

El último comando exige dos AVD propios vacíos y el fixture local preparado. Usa
el permiso real de Android, el servicio real, API y pantalla del Centro. Inyecta
una notificación sintética en el callback: **no acredita recepción desde una
cuenta real de WhatsApp**. El ensayo final pendiente consiste en recibir un mensaje
desde un tercer contacto en WhatsApp instalado en el teléfono del Agente y repetir
la detención/revocación. No usar conversaciones de terceros sin autorización.
