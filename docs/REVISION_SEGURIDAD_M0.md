# Revisión de privacidad y seguridad M0 — mejoras consentidas 0.9

11 de noviembre de 2026 (America/Lima). Fase M0 de
[PLAN_MEJORAS_CONSENTIDAS.md](PLAN_MEJORAS_CONSENTIDAS.md). Revisión de solo lectura
comparando código, contratos y documentación; sin ejecución de compilaciones ni pruebas.

## Verificado en la base 0.8.8

- Contrato y código: `EventRoutes.kt` acepta solo los campos permitidos
  (networkState, batteryPercent, appEventCode, phoneNumber, smsText,
  smsTextTruncated, sharedText, shareConsentVersion) y rechaza cualquier otro;
  `EventPolicy` y `ManualSharePolicy` validan categoría, dirección y versión de
  política. Contratos separados por ampliación en `contracts/`.
- Aislamiento por dispositivo, idempotencia de ingesta y auditoría de acceso
  presentes en la API Ktor. Retención automática de eventos (30 días) y auditoría
  (90 días) en `Retention.kt`, con cuenta de mantenimiento separada.
- Autenticación con sesiones, límites de intentos (`auth_rate_limits`) y credenciales
  de dispositivo en Keystore. TLS configurable en `Tls.kt`.
- Consentimiento versionado, revocación local y remota, indicador visible y
  notificación permanente en el Agente. Sin captura sin acción local confirmada.

## Pendiente de validar en teléfonos físicos

- Recepción real de SMS y reporte previo sin resolver.
- MediaProjection y visor de pantalla en el Huawei del usuario.
- Texto manual 0.8.1 en recorrido nativo completo.
- F1–F6 de administración en Windows físico y análisis Defender del ZIP exacto.
- Android 13/14/15, comportamiento de fabricantes y ejecución prolongada.
- Recepción real de notificaciones de WhatsApp.

## Brechas para las mejoras 0.9

- El consentimiento actual es único por dispositivo; los módulos nuevos exigen
  M1 antes de capturar nada.
- La allowlist de metadata de eventos debe ampliarse para ubicación, video y
  álbumes con sus propias validaciones de tamaño y versión de política.
- No existe almacenamiento de medios en el servidor; D11 (filesystem con
  referencia en PostgreSQL) requiere migración, límites de subida y borrado manual auditado.
- Las descargas de contenido sensible deben quitar la exportación por defecto y
  registrar cada acceso en auditoría.
- Los permisos nuevos (cámara, ubicación aproximada) requieren cadenas de permiso,
  tipos de foreground service y pruebas de denegación.

## Datos fuera del sistema

Agenda y contactos; historial previo de SMS; RCS; audio de llamadas y audio
ambiental; historial de chats; archivos de galería no seleccionados; ubicación
precisa; biometría; accesibilidad; datos de otras aplicaciones.

## Conclusión

Las mejoras son viables sobre la base actual. Condiciones: completar M1 antes de
cualquier captura nueva, mantener los límites del plan y no declarar validación
nativa sin evidencia en teléfono físico.
