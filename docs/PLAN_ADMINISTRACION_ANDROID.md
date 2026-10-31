# AuditLab — ampliación de administración Android

31 de octubre de 2026, America/Lima. Plan aceptado por el responsable del proyecto.
Complementa el [plan maestro](PLAN_MAESTRO.md); estado real en [AVANCE](AVANCE.md).

## Meta y decisiones

Supervisar equipos inscritos desde el Centro Android y distribuir actualizaciones
de AuditLab con confirmación del propietario. Se conservan Kotlin/Compose, Room,
WorkManager, Ktor/PostgreSQL, Windows y HTTPS privado mediante Tailscale.
No se migra a FastAPI/SQLite ni se sustituye el Centro por una web.

Pantalla apagada y salida de la interfaz permiten continuar el servicio visible,
sujeto a Android. Recuperación opcional tras reiniciar solo para batería/conexión,
vinculada al consentimiento y normalmente después del primer desbloqueo. Apagado
completo no permite captura/transmisión. Detener, retirar consentimiento o forzar
detención no se eluden. Sin instalación oculta, identidad disfrazada ni captura
de comunicaciones reactivada desde el arranque.

## Fases y aceptación

| Fase | Trabajo | Cierre requerido |
| --- | --- | --- |
| F1 | Verificar Windows, Java21, Docker/WSL2, recursos, API, HTTPS y Tailscale. | Un Centro y tres Agentes se conectan, incluidas redes distintas; consumo Windows medido. |
| F2 | Identificar equipo, inscripción, último contacto, antigüedad y errores; conservar archivo local. | Lecturas/historial separados; error de consulta no se confunde con desconexión. |
| F3 | Revisar servicio básico, Room, reintentos, permisos y recuperación. | Pantalla apagada, pérdida de red, reinicio y detención probados; no se rearman sesiones de contenido. |
| F4 | Catálogo aprobado, solicitudes auditadas, descarga verificada e instalador Android. | Actualización conserva datos; alteración, otra firma, paquete o versión inferior rechazados. |
| F5 | Integrar consulta/solicitud/resultado en Centro y actualización propia en Ajustes. | Administrador completa el recorrido y auditor no ejecuta acciones administrativas. |
| F6 | Regresión, ensayos físicos, respaldo/restauración, firma y documentación. | APK/hashes, servidor, matriz de compatibilidad y evidencia reproducible. |

F1/F6 requieren acceso a la PC y teléfonos reales. Pruebas HTTP con tres agentes
ficticios no equivalen a tres teléfonos ni a probar Tailscale. Priorizar Android11+
de varias marcas; mantener mínimo API26 y anunciar solo compatibilidad comprobada.

## Interfaces y datos

- Extensión [OpenAPI de actualizaciones](../contracts/app-updates.openapi.yaml).
- Catálogo configurado por archivo del operador, vacío por defecto, solo paquetes
  AuditLab y HTTPS de este repositorio. No hay subida de APK arbitrarias ni shell.
- Solicitudes remotas únicamente para el Agente: admin crea; auditor consulta;
  dispositivo activo accede/reporta solo su solicitud. Una activa por equipo,
  repetición idempotente de la misma versión y vencimiento a siete días.
- Migración V10 aditiva: snapshot inmutable de versión y auditoría transaccional.
  Resultados conservados hasta 90 días después del vencimiento por defecto,
  siguiendo el plazo configurable de auditoría. PostgreSQL existente se conserva.
- La descarga comienza por acción local. Se verifican tamaño/hash, paquete,
  versión superior, Android compatible y mismo certificado que la app instalada.
  Android solicita autorizar la fuente y confirmar instalación cuando corresponda.
- `installed` significa comprobación de versión/firma por el propio Agente y
  reporte autenticado; no es atestación de hardware. Descargar o abrir instalador
  no demuestra instalación. El resultado desconocido se mantiene pendiente.
- ADB es herramienta manual de laboratorio en sesiones autorizadas. No se integra
  instalación silenciosa ni comandos remotos. Android Enterprise/MDM es otra entrega.

## Pruebas requeridas

Tres dispositivos y solicitudes separados; roles; reintentos concurrentes;
vencimiento; revocación; red/servidor caídos; APK corrupta/firma distinta/downgrade;
actualización y cancelación; privacidad de FileProvider; conservación de datos;
segundo plano, reinicio y permisos retirados. WhatsApp real se distingue de entradas
sintéticas. Windows8GB, fabricantes y Android recientes requieren pruebas propias.

La entrega final requiere firma del responsable y validación del ZIP Windows exacto.
No se declara terminada una fase por existir código o por compilar.
