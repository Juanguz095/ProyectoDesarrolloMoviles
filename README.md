# AuditLab — Descargas

Monitoreo visible y autorizado de dispositivos Android propios o administrados.

## Versión actual: 0.4.0-preview

| Componente | Descargar |
| --- | --- |
| Agente para el dispositivo supervisado | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.4.0-preview.apk) |
| Centro para consultar y administrar | [APK Centro de control](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.4.0-preview.apk) |
| Servidor Windows, aplicaciones y fuentes | [Paquete Windows](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.4.0-preview.zip) |

**Android 8.0 o superior. Actualiza las dos aplicaciones y el servidor a la misma
versión.** La API 0.3 no incluye las funciones de pantalla y WhatsApp de 0.4.

[Instalación paso a paso sin USB](PASOS-SIN-USB.md) ·
[Verificación SHA256](SHA256SUMS-0.4.0-preview) ·
[Código y documentación](https://github.com/Juanguz095/ProyectoDesarrolloMoviles)

## Funciones disponibles

- Batería, conexión, eventos de la aplicación y último contacto con el servidor.
- Metadatos de SMS/llamadas con permiso Android: tipo y fecha, sin números ni audio.
- Vistas previas de notificaciones nuevas de WhatsApp/Business, con autorización
  adicional por sesión. No recupera historial de chats.
- Pantalla compartida con permiso local de Android y notificación para detenerla.
- Historial, filtros, detalle, reportes CSV de eventos, roles y auditoría de acceso.
- Inscripción por código y revocación desde el Agente o el Centro de control.

## Instalación

1. Extrae el paquete completo en la PC e inicia `Iniciar-AuditLab.ps1` con Java 21
   y Docker Desktop disponibles. En el primer inicio crea tu contraseña de `admin`.
2. Configura una URL HTTPS accesible desde ambos teléfonos. La guía incluye
   Tailscale para conectar la PC y los teléfonos sin USB, incluso en redes distintas.
3. Instala el Agente y el Centro en sus dispositivos. Inicia sesión en el Centro,
   genera un código e inscribe el Agente con consentimiento e indicador visible.
4. Activa las funciones opcionales desde el Agente y concede sus permisos.

Mantén la PC/API y la conexión funcionando. Al actualizar, conserva
`.auditlab-windows` y los datos Docker; guarda una copia de la API anterior y sigue
[la guía](PASOS-SIN-USB.md).

## Privacidad y estado de la versión

Las sesiones de contenido se inician localmente y tienen una acción DETENER.
El texto de WhatsApp se limita a notificaciones nuevas y permanece temporalmente
en memoria: hasta 20 vistas previas/5 minutos, sin CSV ni persistencia en la base.
La pantalla se actualiza aproximadamente una vez por segundo, sin grabación ni audio.

Esta versión usa firma debug. Registra 197 pruebas JVM/Robolectric/PostgreSQL y
un recorrido de notificaciones aprobado en dos emuladores con entradas sintéticas.
Windows, Android reciente, teléfonos físicos y recepción desde WhatsApp instalado
siguen pendientes de validación; la firma de distribución y el despliegue operativo
HTTPS también están pendientes.

[Informes de validación](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/AVANCE.md) ·
[Registro de publicación y hashes](ENTREGA-0.4.0-preview.json)

Los archivos de versiones anteriores se conservan en esta rama.
