# AuditLab 0.8 — candidato Windows

Contiene Agente y Centro Android, API Ktor/PostgreSQL V10 y fuentes en `source/`.
Es un candidato con firma debug y pruebas de QA; Windows físico, fabricantes,
firma del responsable y piloto siguen pendientes. No contiene contraseñas,
claves privadas ni bases de datos. Generarlo no autoriza publicarlo.

## Inicio

1. Mantener Defender activo. Si bloquea el archivo, conservar el bloqueo.
2. Extraer todo en una ruta corta, por ejemplo `C:\AuditLab`. Preparar Java21,
   Docker Desktop con WSL2 y Tailscale según [la conexión privada](docs/PRUEBA_SIN_USB.md).
3. Desde CMD, en la carpeta extraída:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1
```

4. Comprobar `http://127.0.0.1:8081/ready` en Windows y luego la URL HTTPS privada
   terminada en `/ready` desde cada teléfono. En teléfonos, 127.0.0.1 es el propio
   teléfono. La PC, Docker, API y Tailscale deben permanecer funcionando.
5. Instalar los APK de **esta carpeta `apks/`** con conocimiento del propietario.
   Iniciar sesión en el Centro, crear código e inscribir el Agente con consentimiento.
6. Activar **Batería y conexión** en el Agente. Recuperación tras reinicio es opcional;
   no activa sesiones de comunicaciones ni funciona con el teléfono apagado.

## Actualizaciones

El catálogo está vacío por defecto. Solo habilitar versiones aprobadas publicadas
con sus mismos hashes y certificado. Usar el lanzador actualizado:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\Iniciar-AuditLab.ps1 -ReleaseCatalog C:\AuditLab\releases.json
```

[Configuración, instalación confirmada y pruebas](docs/ACTUALIZACIONES_0.8.md).
No se instala en silencio ni se conceden permisos automáticamente.

## Conservar una instalación anterior

Verificar respaldo y restauración antes de migrar. Detener ordenadamente la API y
actualizar todos los archivos del paquete. Conservar `.auditlab-windows` y los
volúmenes Docker; no borrarlos para corregir errores. El lanzador aplica la migración
aditiva y los permisos. [Operación y respaldo](docs/despliegue.md).

`Revisar-AuditLab.ps1` comprueba requisitos y `/ready` sin modificar el sistema.
Los hashes del paquete están en `SHA256SUMS`; el alcance comprobado y los bloqueos
se conservan en `manifest.json` y [el informe](docs/evidencias/ADMINISTRACION-0.8-2026-10-31.json).
Este ZIP exige análisis Defender del archivo exacto antes de distribución. El
resultado del ZIP0.7 anterior no valida este candidato.
