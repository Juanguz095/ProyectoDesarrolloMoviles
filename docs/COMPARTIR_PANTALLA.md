# Compartir pantalla — ampliación del 28 de octubre

El usuario pidió compartir la pantalla con autorización local y ver mensajes de
WhatsApp que se muestren en ella. Esta función opcional amplía el alcance anterior
de metadatos. No extrae el historial de WhatsApp ni sus notificaciones en segundo plano.

## Uso

1. Actualizar **la API y ambas apps**; ejecutar `ktor migrate` con la cuenta migradora
   para aplicar V4 antes de iniciar el servicio con la cuenta runtime.
2. En el Agente inscrito, habilitar el indicador visible. En las opciones inferiores
   de Monitoreo, pulsar **Compartir pantalla en vivo**, leer la explicación y aceptar
   el diálogo del sistema Android. Se requiere una autorización nueva cada sesión.
3. En el Centro: **Equipos → Ver dispositivo → Ver pantalla compartida**.
4. Para mostrar WhatsApp, abrirlo desde el teléfono durante esa sesión. En Android
   que permite compartir una sola aplicación, elegir WhatsApp o compartir la pantalla
   completa según lo que la persona decida mostrar.
5. Detener desde la notificación persistente o el botón del Agente. Pausar o retirar
   el consentimiento también detiene el servicio. No se reinicia tras cerrar su proceso.

## Transporte y límites

Se envían cuadros JPEG de 480 píxeles de ancho aproximadamente una vez por segundo.
Es una vista de pantalla actualizada, sin audio; no es video fluido. No se guardan
cuadros en una cola: el último JPEG se conserva en RAM y se reenvía periódicamente
mientras la sesión esté autorizada, incluso cuando la pantalla queda estática. No se guardan
cuadros en Room, PostgreSQL ni disco. El relay mantiene un cuadro por dispositivo,
con máximo de 16 dispositivos y 512 KiB por cuadro. Los cuadros dejan de estar
disponibles después de cinco segundos y una tarea elimina los vencidos cada segundo.

El Agente utiliza su token, el Centro su sesión admin/auditor y HTTPS fuera de
loopback. El servidor comprueba de nuevo estado y revocación. Cada consulta del
visor queda en auditoría. El relay es para una única instancia de API; un despliegue
con varias instancias necesita otro transporte y no está soportado por esta versión.

Sin conexión se detiene la captura; no hay cola de contenido ni reintento automático.
Al detener se solicita eliminar el cuadro. Si esa solicitud no llega al servidor,
el último cuadro vence a los cinco segundos. Android puede ocultar contenido protegido
o terminar la captura. La captura se cancela si la notificación está deshabilitada.

## Estado de verificación

La compilación de ambas apps fue satisfactoria. Las pruebas cubren límites, caducidad,
borrado, redirecciones, autorización, aislamiento y revocación del relay. El diálogo
local y su rotación tienen pruebas Robolectric. MediaProjection real, cancelación y
DETENER se aprobaron en API28. El visor mostró y retiró JPEG sintético vía API en API26.
También se aprobó el flujo simultáneo real Agente API28 → API → Centro API26,
incluidos 12 segundos estáticos y detención local. Pendiente Android reciente/teléfonos.
[Informe completo](HISTORICO.md).

Para repetir sobre dos AVD de QA sin registros/sesiones existentes:

```bash
python3 scripts/check_screen_share.py --serial emulator-5592 --pause-other emulator-5594
python3 scripts/check_live_screen_pair.py --agent-serial emulator-5592 --control-serial emulator-5594
```

El verificador requiere la API privada preparada con `scripts/local_fixture.py` y
los APK debug e instrumentación compilados. Abre el aviso del Agente y el diálogo
real de Android sobre datos ficticios. Rechaza teléfonos físicos y registros existentes;
no borra datos para conseguir un resultado satisfactorio.

## Corrección 0.8.4 — 4 de noviembre de 2026 (America/Lima)

Las capturas del usuario muestran el permiso aceptado y el Agente indicando
«compartiendo», mientras el visor responde «No hay una sesión ... activa».
Eso no identifica por sí solo la causa de la ausencia de imágenes. Se verificaron
estos defectos de código y se corrigieron en los clientes, sin cambiar el servidor:

- El Agente anunciaba compartición antes de recibir una imagen o un acuse HTTP.
  Ahora muestra esperando imagen y confirma únicamente después del POST204.
  En Detalles se indica la última imagen confirmada; la notificación se actualiza
  después del primer acuse. Detener invalida confirmaciones tardías.
- La lectura suponía disponible el relleno de la última fila del plano RGBA.
  Se empaquetan los píxeles según rowStride/pixelStride y límites reales del buffer.
  El listener se registra antes de crear el display; una tarea de adquisición
  diferida obtiene la última imagen sin descartar el último cambio rápido.
  Copia/cierre se serializan en un hilo propio y se conserva una sola imagen en RAM.
- Si Android no entrega la primera imagen en15s, la sesión se detiene con causa
  visible. No se mantiene indefinidamente un «compartiendo» sin ningún fotograma.
- GET204 significa que no hay imagen reciente; no demuestra que la persona no
  haya aceptado MediaProjection. El visor muestra ese estado, equipo/código y
  errores diferenciados de autenticación, permisos, formato y red/Tailscale.

Para comparar el destino, revisar el código corto de equipo en el visor y en
Agente → Detalles. La URL base del servidor debe coincidir en ambas apps.
No borrar inscripción/credenciales para intentar resolver una imagen ausente.

Agente0.8.4/código19 y Centro0.8.4/código17; servidor0.8.1/V11 sin cambios.
Las pruebas de buffers/HTTP/pipeline usan datos ficticios. No acreditan captura
nativa en el Huawei del usuario; la recepción real sigue pendiente de verificar.
