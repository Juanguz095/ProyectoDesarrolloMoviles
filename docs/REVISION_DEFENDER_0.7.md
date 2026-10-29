# Revisión del bloqueo del servidor Windows 0.7

Fecha: 29 de octubre de 2026, America/Lima.

## Estado: instalación del ZIP suspendida

El usuario reportó que Windows Defender detectó `Trojan:Script/Ulthar.A!ml` en
`AuditLab-Servidor-Windows-0.7.0-preview.zip`. Su captura indica **Removed** y
señala el archivo descargado; no identifica un miembro interno ni confirma
que haya sido ejecutado. No demuestra por sí sola infección de la PC.

No restaurar ni ejecutar ese archivo, desactivar Defender o añadir exclusiones.
No usar otra versión o un archivo renombrado para intentar eludir el bloqueo.
Los enlaces de instalación actuales del ZIP quedan suspendidos. El artefacto
histórico se conserva para permitir el análisis del mismo contenido reportado.

## Revisión realizada

- Se descargó el ZIP publicado sin ejecutar su contenido.
- SHA256 `280b7e57579d22c9ee97a65d060e6250a882b23665fa91a8f737cce353439778`,
  igual al registro original de publicación; 38 336 916 bytes.
- Las 449 entradas coinciden con el manifiesto, sin nombres duplicados ni rutas
  de extracción fuera del directorio. Los 311 archivos de `source/` coinciden
  byte por byte con el commit `cae5f9f8adc9ab130472a83176d3ff35ed6cd410`.
- `Iniciar-AuditLab.ps1` coincide con su fuente de ese commit. Se revisaron ese
  lanzador y `api/bin/ktor.bat`: creación de configuración local, inicio de
  PostgreSQL en Docker, migraciones, creación de administrador y arranque Java.
  En esos lanzadores no se identificaron descargas de comandos ofuscados,
  exclusiones de Defender ni tareas programadas de persistencia.

Estas comprobaciones establecen procedencia e integridad. No sustituyen un
análisis antimalware y no descartan código peligroso en una dependencia u otro
componente. No se determina qué miembro del ZIP disparó la detección.

## Pendiente para reanudar distribución

No hay Microsoft Defender ni otro motor antimalware disponible en este entorno
Linux. La validación anterior era compilación/lint/contratos/auditoría estática de
APK; **no incluía análisis antivirus de Windows**. No se declara falso positivo.

Se necesita identificar el miembro detectado, analizar el mismo artefacto con
Defender actualizado y resolver el resultado con el proveedor. Microsoft ofrece
[envío de muestras para análisis](https://www.microsoft.com/en-us/wdsi/filesubmission).
No se ha enviado una muestra ni obtenido un dictamen en esta sesión.

Si el archivo ya se ejecutó, realizar un análisis completo desde Seguridad de
Windows y conservar el resultado. No compartir credenciales ni el archivo privado
`.auditlab-windows/config.json` para investigar el aviso.

La captura afecta al ZIP Windows: no contiene un dictamen sobre los APK ni sobre
otras versiones. Su disponibilidad no demuestra que estén libres de detecciones.

[Evidencia de la revisión](evidencias/DEFENDER-0.7.0-2026-10-29.json).


## Continuación sin usar la PC del usuario

Se añadió `.github/workflows/windows-antimalware.yml` para analizar el mismo ZIP
original en Windows aislado, sin ejecutar sus scripts. `Analizar-Paquete.ps1`
comprueba SHA256, Defender activo, firma Microsoft del motor y firmas actualizadas.
Publica su resultado incluso si falla o no encuentra el motor, en `security-reports`.
Un resultado ausente, bloqueado o con error nunca habilita publicación.

`check_windows_release.py` exige evidencia antimalware reciente del ZIP exacto y
rechaza distribución mientras `delivery/windows/security-status.json` mantenga
el incidente abierto. No tiene opción de saltar el control. El empaquetador
marca cualquier nuevo archivo como candidato no autorizado para publicación.
La revisión de dependencias Trivy está separada del dictamen antimalware.
