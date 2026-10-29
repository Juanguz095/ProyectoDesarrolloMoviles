# AuditLab: descargas 0.4.0-preview

Actualiza **las dos apps y el servidor**. Incluye diseño de los mockups, metadatos
reales autorizados, pantalla compartida y vistas previas de notificaciones nuevas
de WhatsApp/Business con consentimiento adicional y notificación DETENER.
No recupera historial de chats ni mensajes que WhatsApp no notifique.

- [Agente para el celular A](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.4.0-preview.apk)
- [Centro de control para el celular B](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.4.0-preview.apk)
- [Servidor Windows actualizado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.4.0-preview.zip)

[Pasos completos: Windows y dos teléfonos, sin USB](PASOS-SIN-USB.md).
Se requiere la PC encendida con el servidor y conexión HTTPS; Tailscale permite
redes distintas sin comprar dominio. El ZIP contiene API compilada, APKs,
lanzador PowerShell, instrucciones, fuentes y checksums. Conserva
`.auditlab-windows` y los datos Docker si actualizas una instalación existente.

197 pruebas JVM/Robolectric/PostgreSQL y recorrido de la ampliación aprobado en
dos emuladores con notificaciones sintéticas. APK debug, Android 8 o posterior.
Windows, teléfonos físicos y recepción desde WhatsApp instalado aún no verificados.
Checksums: `SHA256SUMS-0.4.0-preview`. Código fuente: [main](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/tree/main).

---

# AuditLab: descargas

## Usar sin USB

Los mismos APK 0.3 admiten HTTPS. Puedes conectar la PC y los dos celulares mediante
Tailscale, una red privada por Wi-Fi o Internet, conservando la PC como servidor.
[Pasos para Windows y ambos celulares](PASOS-SIN-USB.md).
La guia requiere instalar/iniciar sesion en Tailscale en tus tres equipos; no configura
esos equipos desde el chat. Windows/Tailscale aun no se han ejecutado aqui.

## Version anterior: 0.3.1-rc1

Rediseño fiel a los mockups del Agente y Centro de control, conservando la captura
autorizada de metadatos reales de SMS/llamadas, batería y conexión. Solo tipo/fecha:
no contenido, números, contactos ni audio. Android 8 o superior.

- [Agente 0.3.1 para el celular A](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.3.1-rc1.apk)
- [Centro de control 0.3.1 para el celular B](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.3.1-rc1.apk)
- [Servidor Windows compatible](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.3.0-rc1.zip)

El backend no cambió entre 0.3.0 y 0.3.1, por eso el servidor Windows 0.3.0 sigue
siendo compatible. Se aprobaron 115 pruebas JVM, 32 pruebas de herramientas, una
secuencia completa de 17 etapas en dos emuladores y una prueba adicional con cinco
eventos reales emulados. Son APK debug para desarrollo; no se probaron en teléfonos
físicos ni tienen la firma de distribución del responsable.

Checksums de esta versión: `SHA256SUMS` y `SHA256SUMS-0.3.1-rc1`.

## Version anterior: 0.3.0-rc1

Captura autorizada de metadatos reales de SMS/llamadas, bateria y conexion.
Solo tipo/fecha: no contenido, numeros, contactos ni audio. Android 8 o superior.

- [Agente para el celular A](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.3.0-rc1.apk)
- [Centro de control para el celular B](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-0.3.0-rc1.apk)
- [Servidor Windows actualizado](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows-0.3.0-rc1.zip)

Actualizar los tres componentes. El ZIP contiene PASOS-WINDOWS.md, servidor precompilado,
asistente PowerShell y fuente exacta 9bc2ca92a6b4f84b6e615ac4601165060f31e628.
Conservar .auditlab-windows y la base existente al actualizar; seguir las instrucciones del ZIP.

En ambos celulares: USB con adb reverse, URL http://127.0.0.1:8081.
En la PC comprobar http://127.0.0.1:8081/ready. Usuario Windows: admin y la contrasena
creada al iniciar el servidor por primera vez. En el Agente aceptar la autorizacion
adicional y los permisos del sistema; una fuente sin permiso no se captura.

253 pruebas host y un recorrido completo del Agente en emulador API28 aprobados sobre
el APK final: SMS recibido/enviado, llamada perdida/saliente, estado, API, CSV,
deduplicacion y retirada. No se acredita Windows, telefonos fisicos ni Android reciente.
Firma debug para desarrollo, sin piloto publico ni firma de distribucion del responsable.
La publicacion de estos archivos no actualiza la rama main; la fuente nueva va en el ZIP.

Checksums de esta versión: `SHA256SUMS-0.3.0-rc1`.

## Version anterior: 0.2 (historica)

Los archivos auditlab-agent-debug.apk, auditlab-control-debug.apk y
AuditLab-Servidor-Windows.zip son la version anterior: no capturan SMS/llamadas reales.
Se conservan con SHA256SUMS-0.2.0 para no perder la entrega anterior.
