# Compartir texto manualmente — 0.8.1

Reconstrucción iniciada el 4 de noviembre de 2026 desde `0b1aa19`.
Los archivos locales de la máquina anterior no estaban en este checkout ni en main.
No se presentan como recuperados. Estado y resultados: [avance](AVANCE.md).

## Uso y conservación

En Android, selecciona un texto y usa Compartir → AuditLab si la aplicación de
origen lo permite. También puedes abrir Agente → Compartir un texto → Pegar.
El portapapeles solo se consulta al pulsar Pegar; no se solicitan permisos SMS,
notificaciones ni accesibilidad para este recorrido.

Revisa el texto (1–2 000 caracteres, sin truncamiento), prepara el envío y revisa
el servidor y dispositivo. Marca la aceptación independiente y confirma cada texto.
Puedes cancelar antes del envío. Después de confirmarlo, cerrar no revierte una
transmisión que el servidor ya recibió. Un error de red puede dejar el resultado
incierto; reintentar en la misma ventana conserva ID, fecha y contenido.

El borrador vive solo en RAM. Rotar, cerrar o perder el proceso lo descarta; no
hay recuperación de borradores, cola Room, WorkManager ni reintentos automáticos.
Enviar no reactiva el monitoreo ni vacía su cola anterior. Requiere inscripción
activa y coincidente; retirar el consentimiento bloquea nuevos envíos. La política
de red conserva HTTPS fuera de loopback.

En Centro → Eventos → Compartidos, abre el detalle del texto. Es texto elegido por
la persona, sin atribución a WhatsApp, remitente o dirección de conversación.
Administradores y auditores autenticados pueden consultarlo y exportarlo. El
servidor aplica la retención de eventos (30 días por defecto, configurable);
retirar consentimiento no promete borrar exportaciones ya descargadas. La purga y
borrado siguen los procedimientos del [manual](MANUAL_PRODUCTO.md).

## Contrato y actualización

Evento `category=manual_share`, `direction=occurred`, `isSimulated=false`.
Metadatos exactos: `appEventCode=text_shared_manually`, `sharedText` y
`shareConsentVersion=manual-share-1`. No se permiten otros campos en ese evento;
los campos manuales están prohibidos en otras categorías. Se usa la autenticación,
auditoría, aislamiento e idempotencia de `/v1/events`; CSV incluye sus metadatos.
V11 amplía restricciones sin cambiar V1–V10. Actualizar y respaldar servidor antes
de usar apps 0.8.1; un servidor anterior rechazará la categoría nueva.

Agente código 16 y Centro código 15, ambos `0.8.1-preview`. Compilación o pruebas
0.8 anteriores no validan estos APK. Firma de publicación, ensayos nativos,
Windows/Defender y piloto requieren sus evidencias propias.

## Siguientes propuestas

Ubicación puntual, diagnósticos propios y estado ampliado del Agente son trabajo
posterior; no están implementados por esta reconstrucción. La ubicación deberá
iniciarse localmente, mostrar precisión/fecha/destino y confirmar cada punto; sin
seguimiento ni segundo plano. Los diagnósticos deben excluir secretos y mensajes;
el estado debe indicar permisos, servicios propios, sincronización y errores sin
atribuir actividad privada de otras aplicaciones. Llamadas SIM de ambas voces no
están implementadas ni se ofrecen como una grabación de micrófono.
