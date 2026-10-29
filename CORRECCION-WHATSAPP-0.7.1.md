# WhatsApp — corrección Android 0.7.1

Fecha: 29 de octubre de 2026 (America/Lima).

## Cambios

- La señal de actividad de WhatsApp se envía cada 8 segundos en una tarea
  independiente. Antes dependía del mismo recorrido que guarda eventos: esa cola
  puede retener un bloqueo mientras envía por red y dejar vencer la sesión de
  vistas previas del servidor (30 segundos). No se cambia ese plazo del servidor.
- Se consultan notificaciones que llegaron **después de aceptar la sesión** y
  durante su conexión inicial. Se evita perderlas mientras se autoriza con la API;
  no se importa historial anterior. Se limita el recorrido a 20 notificaciones.
- Compatibilidad con notificaciones de mensaje sin MessagingStyle completo:
  se acepta categoría mensaje o, cuando falta categoría, una acción de respuesta
  libre. Mensajes estructurados mantienen su filtro de fecha y remitente.
- Agente muestra notificaciones observadas, eventos reconocidos, textos enviados
  al servidor y última señal confirmada. No se registran cuerpos en diagnósticos.
- Centro permite elegir el teléfono directamente en Eventos → WhatsApp, actualizar
  sus vistas previas y ver la última consulta. Errores HTTP distinguen teléfono
  ausente, falta de permisos, sesión vencida y fallo del servidor.

## Descargar

- [APK Agente 0.7.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.7.1-preview.apk).
- [APK Centro 0.7.1](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.7.1-preview.apk).

Compatibles con servidor 0.7 y su configuración existente. No hay cambios de API
ni base de datos ni un ZIP Windows nuevo asociado a esta corrección. El ZIP 0.7
contiene los APK anteriores; utiliza los dos enlaces anteriores para esta revisión.

## Activar

1. Instala ambos APK como actualización, conservando sus datos.
2. En el Agente, detén la sesión WhatsApp anterior si seguía activa.
3. Abre Configurar vistas previas de WhatsApp, comprueba acceso Android y servicio
   conectado, acepta la autorización e inicia la sesión visible.
4. Deja WhatsApp del Agente en segundo plano y recibe un mensaje nuevo desde un
   teléfono propio. El chat abierto puede impedir que WhatsApp publique notificación.
5. En el Centro abre Eventos → WhatsApp, selecciona ese teléfono y pulsa
   Actualizar WhatsApp. La consulta continúa cada 3 segundos en primer plano.

Los contadores permiten distinguir lo que Android entrega, lo que se reconoce y
lo que se envía. Si no llegan notificaciones, no se inventan mensajes. Si el servidor
rechaza la credencial o no ofrece la ruta, el Agente muestra la causa; /ready
accesible no confirma inscripción ni autorización de estas rutas.

## Límites conservados

Solo notificaciones nuevas de WhatsApp/Business, consentimiento por sesión,
indicador visible, revocación y detención locales. Sin historial de chats, archivos,
accesibilidad ni captura oculta. No se observan todos los mensajes enviados dentro
del chat. Texto entrante temporal: 20 vistas previas/5 minutos en memoria, fuera de
Room, SQL y CSV. Las respuestas observadas solo comparten tipo/fecha.

La tarea de actividad también se cancela al detener la sesión; no se reconecta
ni inicia captura automáticamente tras revocación, muerte de proceso o reinicio.

## Validación

Compilación y lint de ambas apps, auditoría estática de APK y revisión de diff.
No se ejecutaron suites automáticas ni un recorrido funcional nuevo en teléfonos;
no se presenta como causa confirmada del teléfono del usuario ni recepción real
validada. El defecto de bloqueo se identifica en el código y se corrige con una
ruta de actividad que no adquiere ese bloqueo. No cambia el resultado de la
revisión anterior del ZIP Windows: el objeto de esa revisión era otro artefacto.
