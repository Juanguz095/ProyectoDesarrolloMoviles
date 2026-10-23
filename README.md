# AuditLab

AuditLab es un sistema de monitoreo autorizado para dispositivos Android propios o administrados. Este MVP usa eventos simulados y metadatos explícitamente permitidos; no lee SMS, llamadas, chats ni notificaciones.

Para continuar el desarrollo, sigue el [plan maestro por fases](docs/PLAN_MAESTRO.md) y el [registro de avance](docs/AVANCE.md). Las instrucciones para el agente están en [AGENTS.md](AGENTS.md). El registro distingue el código existente de las capacidades comprobadas.

## Primera entrega académica

La ruta académica está implementada hasta **I10**: consentimiento, cuatro botones de eventos SIMULADOS, historial SQLite, cola estable durante rotación, exportación manual CSV, borrado y revocación con recuperación de fallos. Versión `0.1.0-i10`. **93 pruebas JVM, 15 del SQL y 49 Android API28 aprobadas**, más recorrido con selector real y detención/relanzamiento del proceso. Lint sin incidencias. **API36 sigue sin validación completa**: dos intentos encontraron ANR de componentes del emulador, sin KVM. Resultados y límites en el [avance](docs/AVANCE_ACADEMICO.md) y la [evidencia I10](docs/evidencias/academico/I10-2026-10-22.md).

Para instalar y usar, sigue el [manual académico](docs/USO_ACADEMICO.md); el [dominio](docs/DOMINIO_ACADEMICO.md) y la [persistencia](docs/PERSISTENCIA_ACADEMICA.md) explican el diseño. **Quedan I11 (revisión de seguridad) e I12 (cierre/evidencias del curso)**, pendientes de autorización. Sync en Android Studio, dispositivo físico y contraste de la rúbrica siguen pendientes. La fase 2 sigue fuera del alcance actual.

El plan maestro de dos apps/API que sigue pertenece al trabajo previo; sus módulos se conservan como referencia y no forman parte de la implementación autorizada de esta entrega local.

## Producto objetivo

- **Agente Android:** consentimiento revocable, notificación visible y eventos simulados/locales.
- **Centro de control Android:** consulta con roles, exportación y revocación.
- **API HTTPS:** inscripción, recepción y consulta de eventos, revocación y auditoría.

## MVP disponible en este repositorio

Incluye una API local en Python/SQLite y una interfaz de navegador para recorrer los flujos del agente A y del centro de control B. Su contrato es [prototype-v0.1.yaml](contracts/prototype-v0.1.yaml); la guía para ejecutarlo está en [backend/api/README.md](backend/api/README.md). Las pantallas son una simulación de desarrollo, no apps Android instalables. [OpenAPI 0.2](contracts/openapi.yaml) corresponde a las interfaces definitivas de Ktor, todavía en implementación.

La base definitiva tiene módulos Android en `apps/agent` y `apps/control`, y un servicio Ktor en `backend/ktor` con salud, disponibilidad PostgreSQL, migraciones y bootstrap seguro de cuentas y [login/refresh/logout HTTP](docs/autenticacion.md). Las pantallas nativas son informativas; la inscripción, el login Android, los eventos y la sincronización todavía deben implementarse. Los comandos y versiones están en la [guía de desarrollo](docs/desarrollo.md); los resultados reales se registran en [AVANCE.md](docs/AVANCE.md).

Los requisitos, estados e historias del producto están definidos en [requisitos](docs/requisitos.md), [estados](docs/estados.md) e [historias de usuario](docs/historias.md).

## Límites de seguridad del MVP

- No se recopilan datos antes de un consentimiento vigente.
- La captura se limita a eventos propios de AuditLab y metadatos permitidos.
- La revocación detiene nuevas transmisiones.
- Las operaciones de consulta y administración requieren autenticación y generan auditoría.
- No se incluyen permisos de SMS, llamadas, contactos, micrófono ni accesibilidad.

## Próximo hito del sistema ampliado (fuera de la entrega actual)

Implementar inscripción con código de un uso y consentimiento (P3.2), según el [contrato 0.2](docs/contrato.md), sobre el [modelo y migraciones PostgreSQL](docs/base-datos.md). Después, recepción/consulta/revocación y conexión de ambas apps. El estado de publicación y las comprobaciones se registran en [AVANCE.md](docs/AVANCE.md).

## Alcance y limitaciones del prototipo

El recorrido del sistema ampliado previo pertenece al prototipo Python/web: escucha en loopback, usa SQLite y autenticación simplificada. En ese prototipo faltan HTTPS, refresh tokens HTTP y controles completos contra abuso. Su flujo Android de dos apps/API sigue pendiente; es distinto de la app académica local ya funcional. No expongas el prototipo a una red. Ktor tiene infraestructura de BD, creación de cuentas por CLI y autenticación HTTP con refresh; todavía no acepta eventos. El plan maestro define lo necesario antes de un piloto real.
