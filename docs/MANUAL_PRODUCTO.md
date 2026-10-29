# AuditLab — manual del producto

Actualizado: 29 de octubre de 2026, America/Lima.
Agente **0.7.1-preview**, Centro **0.7.2-preview**; servidor **0.7**, PostgreSQL con migraciones **V1–V9**.
Es una entrega de desarrollo. [Estado comprobado](AVANCE.md) y
[contexto general](CONTEXTO_PRODUCTO.md).

## 1. Preparación

- Dos teléfonos Android 8/API26 o posteriores, propios o administrados con autorización.
- API Ktor disponible y PostgreSQL migrado, cuenta administrador y auditores si corresponde.
- Ambos teléfonos deben alcanzar la misma URL HTTPS. Para Windows/Tailscale:
  [instalación sin USB](INICIO_WINDOWS_0.4.md). No usar 127.0.0.1 en los teléfonos.
- APK actuales: utilizar los enlaces de [README](../README.md). El ZIP Windows 0.7
  incluye los APK anteriores; descargar los APK actuales separados.
- Las apps publicadas tienen firma debug. Para una distribución propia usar una
  clave del responsable fuera de Git; cambiar de firma puede impedir actualizar
  una instalación anterior. No borrar sus datos automáticamente para resolverlo.

Si Defender bloquea el ZIP, conservar el bloqueo sin restaurar ni añadir exclusiones.
El análisis registrado no reprodujo la alerta; [alcance de la revisión](REVISION_DEFENDER_0.7.md).

## 2. Vincular el teléfono Agente

1. En el Centro, iniciar sesión con la URL HTTPS sin /ready ni /v1, usuario y contraseña.
2. Como administrador, crear un código de inscripción. Es de un uso y vence en 10 minutos.
3. En el Agente, leer y aceptar el consentimiento, configurar esa URL e introducir el código.
4. Conceder notificaciones cuando Android lo solicite y habilitar el indicador visible.
5. Activar de forma independiente las funciones que el propietario quiera autorizar.

No publicar códigos, tokens o contraseñas. Una inscripción activa no confirma que
el servicio esté capturando ni que el teléfono esté conectado en ese momento.

## 3. Agente: batería, SMS y llamadas

En la sección de captura real, aceptar la autorización y pulsar Autorizar e iniciar
captura real. Android puede solicitar READ_SMS y READ_CALL_LOG; la batería no
necesita esos permisos. Las fuentes sin permiso no se leen.

Hay dos casillas adicionales, opcionales:

- **Número:** compartir remitente/destinatario de SMS y llamadas cuando esté disponible.
- **Texto SMS:** compartir hasta 2 000 caracteres de SMS nuevos enviados/recibidos,
  guardados con los eventos y exportables en CSV. No incluye RCS ni mensajes anteriores.

Para cambiar esas autorizaciones, detener captura real y volver a iniciarla con
las casillas correspondientes. No se completan eventos antiguos. No se lee agenda
ni audio. Las llamadas aparecen al escribirse su registro, normalmente al finalizar.

Batería/conexión se leen con observadores y reconciliación cada 10 s. Se envían
cambios y se renueva el estado al menos al minuto mientras la captura funciona.
Enviar estado ahora solicita otra lectura; la red y la planificación pueden retrasarla.
El Centro debe volver a consultar para mostrarla.

## 4. Agente: WhatsApp

1. Habilitar el indicador visible y abrir Configurar vistas previas de WhatsApp.
2. Abrir ajustes Android y conceder acceso a notificaciones. Volver y comprobar
   que indique servicio conectado.
3. Leer y aceptar la autorización de vistas previas/actividad e iniciar la sesión.
4. Recibir una notificación nueva con WhatsApp/Business en segundo plano.

En 0.7.1 se muestran notificaciones observadas, eventos reconocidos, textos enviados
al servidor y última señal confirmada. Son diagnósticos sin cuerpo de mensajes.
La señal de actividad es independiente de la cola persistente.

El texto entrante permanece temporal; no es el historial del chat. Las respuestas
propias solo generan tipo/fecha si Android las expone en una notificación; no
se obtiene todo lo escrito dentro del chat ni se confirma entrega o lectura.
Detener desde la app/notificación termina la sesión; reiniciar el proceso no la reactiva.
[Corrección y límites](CORRECCION_WHATSAPP_0.7.1.md).

## 5. Agente: compartir pantalla

Pulsar Compartir pantalla en vivo, leer la explicación y aceptar el permiso Android
MediaProjection de esa sesión. El propietario elige lo que comparte según las
opciones del sistema. Puede detener desde la notificación o el Agente.

Se comparten imágenes de pantalla aproximadamente una vez por segundo. No es acceso
a cámara, audio ni grabación; ventanas protegidas pueden aparecer en blanco/negro.
[Detalles](COMPARTIR_PANTALLA.md).

## 6. Centro: consultar

- **Inicio:** teléfono seleccionado, estado, última lectura, batería y conexión.
- **Equipos:** estado de inscripción, último contacto y acciones por dispositivo.
- **Eventos:** comunicaciones reales por defecto; WhatsApp, SMS y llamadas.
  Batería y simulaciones no llenan el historial principal.
- **Filtros:** dispositivo, categoría, fechas y búsqueda exacta por ID/código.
  No busca palabras dentro del texto SMS. Fechas precisas del filtro usan UTC;
  las tarjetas presentan hora local.
- **Detalle:** información legible; IDs/códigos/contexto bajo Información técnica.
- **WhatsApp:** Eventos → Todo o WhatsApp, seleccionar teléfono y Actualizar WhatsApp.
  Las tarjetas de texto temporal se muestran dentro de Resultados, separadas del
  historial de actividad, sin filtros de fecha/ID. Ver mensaje completo expande
  el texto. [Alcance del Centro 0.7.2](WHATSAPP_EVENTOS_0.7.2.md).
  También puede abrirse desde el dispositivo. Consulta cada 3 s en primer plano.
- **Pantalla:** Equipos → Ver dispositivo → Ver pantalla compartida.

La consulta general automática es cada 10 s con la app abierta; se puede actualizar
manualmente. En páginas históricas se pausa la renovación automática para conservar
el contexto. Un dato mostrado tiene una fecha; no es garantía de conexión continua.

## 7. Reportes y auditoría

CSV usa los filtros aplicados y todos sus resultados, hasta 10 000 eventos, no solo
la página visible. Incluye texto SMS autorizado y metadatos de actividad WhatsApp;
no incluye texto de vistas previas WhatsApp ni imágenes de pantalla.

Android permite elegir el destino del CSV. Cancelar no guarda un reporte; un fallo
de escritura puede dejar un documento incompleto que no debe usarse como evidencia.
La auditoría registra actor, acción, recurso/filtros, resultado y fecha. Sus datos
técnicos pueden desplegarse; la consulta de auditoría también queda registrada.

## 8. Pausa, revocación y cierre

- **Pausa local:** bloquea captura/envío y conserva pendientes válidos hasta reanudar
  o retirarlos. Las sesiones de contenido se detienen.
- **Retirar consentimiento:** detiene captura y envíos y limpia cola local; sin red,
  el aviso al servidor queda pendiente. No promete borrar al instante eventos ya
  recibidos por PostgreSQL: siguen su retención.
- **Revocar desde Centro:** solo administrador, con confirmación. La API bloquea
  nuevas operaciones; el Agente lo conoce en el siguiente contacto, no sin conexión.
- **Cerrar sesión del Centro:** confirma y limpia su sesión local; la app distingue
  cierre remoto confirmado de cierre solo local cuando hay fallo de red.

## 9. Conservación

| Información | Regla actual |
| --- | --- |
| Cola del Agente | Hasta 7 días/10 000 eventos; reintentos con IDs estables. |
| Eventos del servidor, incluido SMS autorizado | 30 días por defecto, configurable. |
| Auditoría | 90 días por defecto, configurable. |
| Texto WhatsApp | Últimas 20 vistas previas/5 min en memoria; sesión vence tras 30 s sin comunicación. |
| Pantalla | Último cuadro en memoria; vence tras 5 s sin renovación. |

Los eventos se envían por lotes de hasta 100, además limitados por bytes. WorkManager
requiere condiciones de red y su programación depende de Android. Las sesiones de
contenido no usan cola en disco ni se inician automáticamente después de reiniciar.

## 10. Diagnóstico y alcance

- /ready accesible: demuestra disponibilidad de API/BD, no autorización de la captura.
- Rechazo de credencial: revisar inscripción y que se conservó la configuración/BD.
- WhatsApp sin avisos observados: Android aún no entregó una notificación nueva admisible.
- Avisos observados pero sin texto enviado: revisar el estado de reconocimiento/envío
  que muestra el Agente; avisos sin texto o respuestas propias no equivalen a una vista previa entrante.
- Texto enviado pero Centro vacío: comprobar teléfono seleccionado, sesión, conexión
  y caducidad de las vistas previas. No se recuperan después de vencer.
- Forzar detención, permisos retirados, restricciones de batería o reiniciar pueden
  interrumpir los servicios. No se promete captura permanente bajo esas condiciones.

La recepción real de WhatsApp reportada por el usuario sigue pendiente de confirmar
en la versión actual. Compilación/lint/firma no acreditan ese recorrido.
[Registros](evidencias/WHATSAPP-0.7.1-2026-10-29.json).
