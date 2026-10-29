# AuditLab 0.6 — mensajes y llamadas con información legible

## Qué cambia

- **Eventos** abre en mensajes y llamadas reales. Batería/conexión siguen en el
  resumen de Inicio y del dispositivo; no llenan el historial ni su CSV por defecto.
  El filtrado se hace antes de paginar en el servidor, no ocultando filas después.
- Las tarjetas muestran SMS recibido/enviado o tipo de llamada, número si fue
  compartido y hora local como “Hoy · 11:01”. Sin número se indica que no está
  compartido o disponible. No se inventan nombres, números ni contenido.
- Los dispositivos se muestran con alias locales persistentes como “Teléfono 1”.
  Los UUID, códigos, políticas y diagnóstico están bajo **Información técnica**;
  también se simplifica la auditoría y las confirmaciones de revocación.
- En **Eventos → WhatsApp** puedes consultar el texto temporal de notificaciones
  durante una sesión autorizada. Si hay varios teléfonos, selecciona uno en
  **Filtrar eventos**. El texto desaparece al expirar la sesión o perder conexión.
- Se conserva la actualización automática y el botón **Actualizar ahora**.

## Actualiza las tres piezas

- [Agente 0.6](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.6.0-preview.apk).
- [Centro 0.6](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.6.0-preview.apk).
- [Servidor Windows 0.6](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.6.0-preview.zip).

1. Detén la API con Ctrl+C en su ventana de Windows.
2. Conserva **`.auditlab-windows`**, sus claves y los datos/volúmenes Docker. Guarda
   la carpeta `api` anterior como respaldo y extrae todo el ZIP nuevo en la misma
   ubicación de la instalación. No borres la base ni la configuración.
3. Desde esa carpeta, en CMD o PowerShell, ejecuta:

   ```text
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\Iniciar-AuditLab.ps1"
   ```

4. El lanzador aplica V8 y conserva cuenta/configuración. Mantén la ventana abierta
   y comprueba `/ready` desde ambos teléfonos usando la misma URL HTTPS de Tailscale.
5. Instala los dos APK como actualización, conservando sus datos. No necesitas
   cambiar URL, contraseña ni volver a inscribir por esta actualización.

La API nueva es necesaria: un servidor anterior no entiende el filtro conjunto ni
el campo opcional `phoneNumber`. V8 conserva los registros y migraciones anteriores.

## Mostrar el número de SMS y llamadas

1. En el Agente, detén la captura real si ya estaba activa.
2. Lee y marca la autorización de metadatos y la casilla adicional **Autorizo
   compartir el número del remitente/destinatario de SMS y llamadas**.
3. Pulsa **Autorizar e iniciar captura real** y concede los permisos Android de
   SMS/registro de llamadas. El indicador debe permanecer visible.
4. Genera un SMS o llamada nuevos en un teléfono propio; las llamadas aparecen
   cuando Android escribe el registro, normalmente al finalizar. En el Centro
   abre **Eventos → SMS/Llamadas** y pulsa **Actualizar ahora**.

La casilla es opcional y no se activa por instalar una actualización. Sin ella no
se consulta la columna del número. Los eventos antiguos quedan como estaban; los
números ocultos/privados o que Android no entrega se muestran como no disponibles.
No se consulta agenda, cuerpo de SMS ni audio. Esta ampliación no agrega permisos:
utiliza READ_SMS/READ_CALL_LOG ya existentes.

El número autorizado forma parte del evento persistente y del CSV para roles
autorizados. Usa la retención existente: cola local hasta 7 días/10 000 registros;
servidor 30 días por defecto. Detener la captura impide nuevas lecturas, sin borrar
registros ya transmitidos. Retirar consentimiento mantiene el borrado local y
la revocación existentes.

## Mostrar mensajes de WhatsApp

1. En el Agente abre **Configurar vistas previas de WhatsApp**.
2. Concede acceso Android a notificaciones, vuelve y acepta la autorización de
   vistas previas/registro de actividad. Inicia la sesión visible.
3. Recibe una notificación nueva con texto en WhatsApp o Business. En el Centro
   abre **Eventos → WhatsApp**; con varios dispositivos, selecciona el teléfono.

El texto comparte el límite existente de 20 vistas previas/5 minutos en memoria;
no entra en Room, PostgreSQL, el historial persistente ni CSV. Los eventos de
tipo/fecha sí mantienen la retención ordinaria. No importa historial, contenido
oculto ni archivos. Los envíos dentro de un chat abierto normalmente no son
observables; las respuestas en notificaciones no confirman entrega o lectura.
El propietario puede detener la sesión en el Agente o su notificación.

## Validación y límites de entrega

Entrega de desarrollo con compilación de ambas apps/API, lint, contrato OpenAPI y
auditoría estática de APK. El registro de avance distingue los resultados de esta
versión de los de versiones anteriores. No se ejecutan suites automáticas en esta
revisión. Recepción desde WhatsApp instalado, teléfonos físicos y Windows siguen
pendientes de validación; no se declara que todos los recorridos estén verificados.
Las APK mantienen firma debug.

Para generar el paquete desde la revisión comprometida y compilada:

```bash
python3 scripts/package_development.py --build-record docs/evidencias/COMUNICACIONES-0.6.0-2026-10-28.json --output .tooling/delivery/0.6.0-preview/AuditLab-Servidor-Windows-0.6.0-preview.zip
```
