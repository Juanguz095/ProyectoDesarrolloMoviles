# AuditLab

AuditLab es un sistema de monitoreo autorizado para dispositivos Android propios o administrados. Este MVP usa eventos simulados y metadatos explícitamente permitidos; no lee SMS, llamadas, chats ni notificaciones.

Para continuar el desarrollo, sigue el [plan maestro por fases](docs/PLAN_MAESTRO.md) y el [registro de avance](docs/AVANCE.md). Las instrucciones para el agente están en [AGENTS.md](AGENTS.md). El registro distingue el código existente de las capacidades comprobadas.

## Primera entrega académica

La solicitud del 22 de octubre define una app local con `app`/`domain` y doce incrementos. Consulta el [plan académico mejorado](docs/PLAN_ACADEMICO.md), la [propuesta de I01](docs/PLAN_I01.md) y su [registro propio](docs/AVANCE_ACADEMICO.md). El usuario autorizó continuar hasta I03: modelos, contratos y AuditService implementados, con **48 pruebas JVM satisfactorias**, build Android y lint sin incidencias. El [dominio académico](docs/DOMINIO_ACADEMICO.md) controla aceptación, límites 1000/100 y revocación con reintento de borrado. Los tests usan almacenamiento en memoria; SQLite y su integración con la pantalla llegan después. La Activity sigue siendo la base de I01 y faltan Sync en Android Studio y ejecución en Android. I04 (CSV) es el siguiente incremento, pendiente de autorización. La fase 2 y la transmisión siguen fuera del alcance actual.

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

El recorrido funcional actual pertenece al prototipo local Python/web: escucha en loopback, usa SQLite y autenticación simplificada. En ese prototipo faltan HTTPS, refresh tokens HTTP y controles completos contra abuso. El flujo funcional Android también está pendiente. No expongas el prototipo a una red. Ktor tiene infraestructura de BD, creación de cuentas por CLI y autenticación HTTP con refresh; todavía no acepta eventos. El plan define lo necesario antes de un piloto real.
