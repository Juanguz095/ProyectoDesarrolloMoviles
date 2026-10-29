# Alertas WhatsApp sin Firebase — Centro 0.7.4

29 de octubre de 2026, America/Lima. P6.2/P6.6/P6.4/P8.2/P8.3.

## Activar

1. Instalar Centro 0.7.4 sobre el anterior, conservando sus datos. Compatible con
   Agente 0.7.1 y API 0.7; sin cambios de servidor, URL, base de datos ni inscripción.
2. En el Agente activar el indicador visible, conceder acceso a notificaciones y
   autorizar/iniciar la sesión de vistas previas de WhatsApp.
3. En el Centro abrir Equipos → Ver dispositivo → Activar alertas para este teléfono.
4. Permitir las notificaciones Android del Centro (Android 13 o superior).
5. Aparece una notificación persistente «AuditLab · Alertas activas». Se consulta
   cada 5 segundos mientras Android permita ejecutar el servicio y haya red.
6. Recibir una notificación nueva con WhatsApp en segundo plano en el Agente.
   El Centro muestra título del equipo/notificación y el texto disponible. Expandir
   el aviso permite leerlo; tocarlo abre Eventos → WhatsApp filtrado por ese equipo.
7. Detener desde el botón de la notificación persistente o desde el detalle del
   equipo en el Centro. Una sola fuente por vez; detener antes de activar otra.

## Funcionamiento y límites

- Se implementa consulta mediante **servicio visible dataSync**, no notificaciones
  push de Firebase. No necesita cuenta Firebase ni envía los textos a Google.
- Puede seguir al cerrar la pantalla de la actividad; no se reinicia tras forzar
  detención, reiniciar el teléfono o morir el proceso. El fabricante, ahorro de
  batería, Doze o Android pueden retrasar/interrumpir las consultas. Android 15
  aplica un presupuesto de 6 horas de dataSync en segundo plano por 24 horas;
  al alcanzar el límite se detiene. No se promete vigilancia permanente.
- Si no hay sesión autorizada del Agente, conexión, credenciales válidas o permiso,
  el servicio se detiene. Reabrir el Centro y activar de nuevo; no reconecta ocultamente.
- Solo notificaciones recibidas con texto y timestamp posterior a activar alertas.
  No captura mensajes enviados desde el chat ni recupera historial anterior.
- Deduplicación en RAM; no crea eventos adicionales ni guarda textos en Room/SQL/CSV.
  No se transmite contenido a un proveedor push. No hay cambios del contrato API.
- Notificaciones de mensajes ocultas en pantalla bloqueada, con caducidad máxima
  de 5 minutos. Al detener el servicio se cancelan sus avisos. Una sesión detenida,
  revocación o eliminación del texto en el Agente se detecta en la siguiente consulta
  exitosa y retira los avisos. No se promete retirada instantánea sin conexión.
- La API conserva sus auditorías por consulta. Más consultas aumentan consumo de
  batería/red y volumen de auditoría. El cierre de sesión invalida el núcleo compartido
  y la limpieza local detiene el servicio; no se mantienen credenciales paralelas.
- El Centro añade POST_NOTIFICATIONS, FOREGROUND_SERVICE y
  FOREGROUND_SERVICE_DATA_SYNC; servicio no exportado, sin arranque automático.

## Validación

Compilación, lint y auditoría del APK registrados en
[evidencia](evidencias/ALERTAS-0.7.4-2026-10-29.json). Pruebas de selección temporal,
deduplicación, sesión detenida, timestamps inválidos/caducados/futuros, limpieza
local del núcleo compartido y regresión de autenticación. Sin prueba de notificaciones
reales ni servicio mantenido en teléfonos. Pendiente P7.6: ambos dispositivos con
WhatsApp instalado, Centro en segundo plano, detener/revocar y ahorro de batería.

### Comprobación nativa posterior

Un recorrido Android API26 aprobado con APK publicado: aviso con texto en segundo
plano (Home), ocultación en bloqueo, deduplicación, abrir Eventos al tocar, Detener,
reactivar y retirar avisos al terminar la sesión fuente. Entrada ficticia vía API
real; no WhatsApp instalado ni listener del Agente. [Evidencia nativa](evidencias/ALERTAS-NATIVAS-0.7.4-2026-10-29.json).
No sustituye la prueba en teléfonos ni valida Android13/15 o ejecución prolongada.

Para repetir en un AVD de QA sin sesión previa, con el fixture local ya activo:

```bash
python3 scripts/gradle.py :apps:control:assembleDebug :apps:control:assembleDebugAndroidTest --offline --max-workers=2
python3 scripts/check_whatsapp_alerts.py --control-serial emulator-5594
```

No se borra la app para preparar la prueba; aborta si existe una sesión previa.
Se crean una inscripción y sesiones QA en el fixture. Configuración privada
inyectada por stdin y retirada al terminar; no pegar credenciales en comandos.
