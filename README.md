# AuditLab — Descargas

Monitoreo visible y autorizado de dispositivos Android propios o administrados.

## Aplicaciones 0.4.1-preview

| Componente | Descargar |
| --- | --- |
| Agente para el teléfono supervisado | [APK Agente](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.4.1-preview.apk) |
| Centro de control | [APK Centro](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.4.1-preview.apk) |
| Servidor Windows compatible 0.4.0 | [Servidor Windows](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.4.0-preview.zip) |

**Android 8.0 o superior. Instala ambos APK 0.4.1 conservando sus datos.** Puedes
mantener el servidor 0.4.0, la misma URL y tu inscripción. El ZIP del servidor
incluye APKs anteriores 0.4.0: descarga por separado los dos APK actuales de arriba.
Un servidor 0.3 requiere actualizarse a 0.4.0 para las funciones de pantalla y WhatsApp.

[Instalación Windows sin USB](PASOS-SIN-USB.md) ·
[Guía de actualización y batería](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/blob/main/docs/ACTUALIZACION_0.4.1.md) ·
[SHA256](SHA256SUMS-0.4.1-preview)

## Cambios en 0.4.1

- Centro: actualización automática cada 10 segundos y botón Actualizar ahora.
- Batería con porcentaje grande, hora de lectura y aviso si el dato es antiguo.
- Eventos con títulos legibles, fecha local y agrupación por día; filtros desplegables.
- Agente: activación de batería/eventos visible y botón Enviar estado ahora.
- Batería a partir del estado de Android; confirmación al minuto aunque el valor
  no cambie y aislamiento de errores en proveedores SMS/llamadas.

En el Agente activa **Batería y eventos del teléfono**; en el Centro deja
**Automático** encendido. Actualizar el Centro consulta el servidor; el propietario
activa la captura en el otro teléfono. La conexión y Android pueden retrasar envíos.

## Funciones

Batería/conexión, metadatos autorizados de SMS y llamadas, notificaciones nuevas de
WhatsApp/Business, pantalla compartida, historial, filtros, CSV de eventos, roles,
auditoría e inscripción/revocación. El contenido de pantalla/WhatsApp exige otra
sesión autorizada con indicador visible y acción DETENER. No recupera historial de chats.

## Estado de verificación

APKs con firma debug. En 0.4.1 finalizaron compilación, lint sin errores y auditoría
estática de los APK; se revisaron visualmente las pantallas iniciales en emuladores.
**No se ejecutaron las pruebas automáticas ni el recorrido funcional completo de
esta revisión**. Los 197 casos y el recorrido de notificaciones de 0.4.0 son históricos.
Pendiente validar 0.4.1 entre teléfonos, red, permisos, segundo plano y paginación.

Mantén la PC/API encendida y usa HTTPS. Para conectar redes diferentes, la guía
incluye Tailscale. Conserva `.auditlab-windows` y los datos Docker al actualizar.

[Código y documentación](https://github.com/Juanguz095/ProyectoDesarrolloMoviles) ·
[Registro de publicación](ENTREGA-0.4.1-preview.json)

Los archivos de versiones anteriores se conservan en esta rama.
