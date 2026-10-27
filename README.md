# AuditLab: descargas para Windows y dos celulares por USB

Archivos preparados de la candidata 0.2.0-rc1. Las APKs de desarrollo conservan los hashes de las pruebas nativas registradas. El asistente PowerShell esta preparado, pero no se ha ejecutado en Windows ni en telefonos fisicos.

Descarga estos tres archivos desde esta rama:

- [Servidor Windows](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/AuditLab-Servidor-Windows.zip)
- [Agente: celular A](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-debug.apk)
- [Centro: celular B](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-control-debug.apk)

Extrae el servidor en C:\AuditLab. Guarda ambos APKs dentro de C:\AuditLab\apks.
Sigue PASOS-WINDOWS.md dentro del ZIP. Incluye API compilada, fuentes y asistente Iniciar-AuditLab.ps1.

La conexion de prueba usa adb reverse por USB y http://127.0.0.1:8081 en ambos celulares. No se declara un piloto HTTPS publicado ni firma de distribucion del responsable.
