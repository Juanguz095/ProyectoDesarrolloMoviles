# AuditLab 0.5 — tarjetas completas y actividad de WhatsApp

## Qué cambia

- Se corrige el recorte curvo de títulos y textos en tarjetas: el contenido ya no
  está dentro de un botón con forma de cápsula. Se corrigen también botones de
  varias líneas en el historial del Agente y enlaces compartidos.
- **Eventos** ofrece filtros rápidos Todos, WhatsApp, SMS, Llamadas y Batería.
- La sesión WhatsApp puede registrar eventos persistentes de **tipo y fecha**,
  separados del contenido temporal de las vistas previas.

## Qué puede detectar WhatsApp

| Evento | Cuándo se registra |
| --- | --- |
| Notificación de WhatsApp recibida | Android publica una notificación nueva de mensaje durante la sesión autorizada. |
| Respuesta de WhatsApp observada | Android publica una respuesta propia en MessagingStyle o cambia el historial de respuesta directa de una notificación que ya se observó durante la sesión. |

Una respuesta observada **no confirma entrega ni lectura**. Los mensajes escritos
dentro de un chat abierto normalmente no generan notificación y no pueden
detectarse con este mecanismo. Las notificaciones ocultas, ausentes, anteriores a
la sesión o de origen ambiguo no se convierten en mensajes inventados. La primera
aparición de un historial de respuestas establece una referencia: no se importa
como actividad nueva. No se accede a bases de WhatsApp ni se usa accesibilidad.

El evento conserva identificador, dispositivo, contexto de consentimiento, tipo,
fecha y dirección; no guarda texto, nombre del contacto, número ni archivo adjunto.
Entra en el historial y CSV de eventos y utiliza su retención (cola local de hasta
7 días/10 000 registros; servidor 30 días por defecto). Detener la sesión impide
nuevas capturas; los metadatos ya registrados conservan la cola y retención de
eventos. Retirar el consentimiento base elimina la cola según la política existente.

El título/texto de vistas previas entrantes mantiene su sesión temporal: máximo
20 vistas previas/5 minutos en memoria, sin almacenamiento SQL/Room ni CSV. Las
respuestas propias observadas solo generan metadatos, sin compartir su texto.

## Actualizar los tres componentes

- [Agente 0.5.0-preview](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.5.0-preview.apk).
- [Centro 0.5.0-preview](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.5.0-preview.apk).
- [Servidor Windows 0.5.0-preview](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.5.0-preview.zip).

1. Detén la API con Ctrl+C en su ventana de Windows.
2. Conserva la carpeta **`.auditlab-windows`**, sus claves y los datos/volúmenes de
   Docker. Guarda la carpeta `api` anterior como respaldo. Extrae el paquete nuevo
   en la misma ubicación de la instalación existente.
3. Ejecuta `Iniciar-AuditLab.ps1`: reutiliza la configuración y aplica V7. No hay
   que cambiar la URL HTTPS de Tailscale ni crear otra cuenta de administrador.
4. Comprueba `/ready` desde ambos teléfonos e instala los dos APK nuevos como
   actualización, conservando sus datos.

El Agente 0.5 necesita servidor 0.5 para la sesión WhatsApp y sus eventos. El
servidor nuevo conserva compatibilidad con las sesiones de vista previa anteriores;
una sesión de política antigua no activa esta captura nueva en un Agente antiguo.

## Activar y consultar

1. En el Agente inscrito, habilita el indicador visible.
2. Abre **Configurar vistas previas de WhatsApp**, concede acceso Android a
   notificaciones, vuelve a la app y espera a que se conecte el servicio.
3. Lee y marca la autorización nueva de **vistas previas y registro de actividad**
   e inicia la sesión. La política es `whatsapp-preview-2`; una autorización anterior
   no se convierte silenciosamente en autorización nueva.
4. Recibe un mensaje nuevo de un contacto propio mientras WhatsApp no tenga ese
   chat abierto. Para observar una respuesta, usa la respuesta directa desde una
   notificación; su detección depende de lo que Android/WhatsApp publiquen.
5. En el Centro abre **Eventos → WhatsApp** y pulsa **Actualizar ahora**, o deja
   Automático activado. El texto temporal se consulta desde el detalle del equipo.

Los SMS requieren su permiso y las llamadas aparecen al terminar. Activar captura
de batería/eventos no activa automáticamente las notificaciones WhatsApp: tienen
su autorización propia. **DETENER** en la notificación termina la sesión opcional.

## Alcance de validación

Esta entrega de desarrollo registra compilación, lint, revisión de contratos,
auditoría estática y aplicación de V7 al servidor de desarrollo. Las comprobaciones
no sustituyen la recepción real desde una cuenta de WhatsApp ni la validación de
Windows/teléfonos. No se ejecutaron suites automáticas en esta revisión. Los
resultados de versiones anteriores permanecen separados.

Paquete reproducible, después de compilar y guardar el registro/commit:

```bash
python3 scripts/package_development.py --build-record docs/evidencias/ACTIVIDAD-0.5.0-2026-10-28.json --output .tooling/delivery/0.5.0-preview/AuditLab-Servidor-Windows-0.5.0-preview.zip
```
