# Captura real de llamadas y SMS — 0.3.0-rc1

Ampliación solicitada por el usuario el 27 de octubre de 2026 (America/Lima).
Sustituye la limitación de la candidata 0.2 que admitía únicamente eventos simulados.
Las evidencias históricas de 0.2 no validan esta ampliación.

## Qué registra

| Fuente | Eventos | Datos consultados |
| --- | --- | --- |
| Proveedor de SMS de Android | SMS recibido y enviado, no borradores/cola/fallidos | `_id`, `date`, `type` |
| Registro de llamadas de Android | Recibida, saliente, perdida, rechazada/bloqueada | `_id`, `date`, `type` |
| APIs del dispositivo | Cambios de batería y conexión | Porcentaje y estado de conexión |

Solo se transmite categoría, dirección, fecha, un código cerrado del evento y el contexto
de consentimiento/identidad del agente. El ID local se usa para deduplicar mediante un UUID
determinista, no se envía como campo independiente. No se consultan números, direcciones,
contenido de SMS, contactos, grabaciones ni notificaciones ajenas. No incluye RCS,
WhatsApp, Telegram ni llamadas por otras aplicaciones. Las llamadas aparecen cuando
Android escribe el registro al finalizar; no se observa el audio ni se promete aviso en vivo.

## Activación en dos teléfonos propios y Windows

1. Detener el servidor anterior con Ctrl+C. Actualizar **también el servidor**, conservando
   `.auditlab-windows`: contiene la configuración privada y referencia al volumen existente.
   No borrar volúmenes ni configuración para actualizar. El lanzador aplica V3 sin borrar eventos.
2. Ejecutar `Iniciar-AuditLab.ps1`, mantener Docker Desktop abierto y comprobar en la PC
   `http://127.0.0.1:8081/ready` → `{"status":"ready"}`.
3. Actualizar ambas APKs conservando datos y firma: `adb -s SERIAL install -r archivo.apk`.
   No desinstalar una app para resolver un error de firma; revisar la identidad del APK.
4. Mantener el reenvío USB en ambos: `adb -s SERIAL reverse tcp:8081 tcp:8081`.
5. Centro: URL `http://127.0.0.1:8081`, usuario `admin`, contraseña elegida en el primer
   inicio del servidor Windows. Generar código si el Agente todavía no está inscrito.
6. Agente: aceptar consentimiento, inscribir y habilitar la notificación visible.
7. Leer y marcar la autorización adicional de metadatos reales. Pulsar **Autorizar e iniciar
   captura real** y responder a las solicitudes de permisos del sistema.
8. Comprobar el estado individual de SMS y llamadas. Si indica **sin permiso**, esa fuente
   no se captura. Android considera ambos permisos sensibles/restringidos; su disponibilidad
   depende de la versión, instalador y política del dispositivo. La app no los concede sola.
9. Enviar/recibir un **SMS convencional**, terminar una llamada y actualizar el historial
   del Centro. Buscar categoría `sms` o `call`. Los registros deben indicar **Real**.
   El servicio intenta el envío al capturar y WorkManager conserva el reintento persistente;
   la entrega depende de la conexión y del servidor.
10. Pulsar **Detener captura real**, pausar o retirar consentimiento para detener la consulta.

## Consentimiento, visibilidad y conservación

- La captura adicional empieza únicamente por una acción del usuario, después de la
  inscripción, la casilla adicional y la comprobación de la notificación visible.
- Se conserva un recibo local con dispositivo, consentimiento base, alcance y fecha de activación.
- Solo se consultan filas desde la activación. No se importa el historial previo.
- Al pausar o detener se desactiva la consulta. Al reactivar se toma una fecha nueva;
  los eventos ocurridos durante la pausa no se recuperan como si se hubieran autorizado.
- El servicio es visible, no exportado y `START_NOT_STICKY`. No arranca la captura
  de telefonía al reiniciar el equipo o después de la muerte del proceso. Abrir la app
  y autorizar la activación nuevamente. No promete ejecución continua si Android la termina.
- Revocación local bloquea primero las nuevas capturas/envíos y conserva la limpieza
  y revocación remota existentes. Revocación desde el Centro se conoce en el siguiente contacto.
- Los eventos pendientes y recibidos mantienen la retención y los límites del producto.

## Compatibilidad y verificación

`isSimulated=false` se conserva en Room, envío HTTP, validación, PostgreSQL, consultas y CSV.
Las filas antiguas de Room sin esa propiedad se interpretan como simuladas. Flyway V3
amplía categorías/códigos sin modificar V1/V2. Campos privados siguen siendo rechazados.

Consultar [registro de avance](AVANCE.md) para resultados ejecutados y limitaciones.
Los APK de 0.2 y los 278 casos históricos no acreditan llamadas/SMS reales en teléfonos.
La instalación Windows y las restricciones específicas de cada fabricante requieren
ejecución en el equipo del usuario. Una publicación en Google Play requiere evaluar
por separado su política de permisos de SMS/registro de llamadas y servicio `specialUse`.
