# ADR 0001 — Base nativa y conservación del prototipo

Estado: decisión adoptada para las tareas P0.2/P2.1; compilación y evidencias se registran por separado.

## Decisión

- Conservar `backend/api` como prototipo local Python/web, con su suite HTTP y sus limitaciones documentadas.
- Crear `apps/agent` y `apps/control` como apps Android independientes (IDs `com.auditlab.agent` y `com.auditlab.control`).
- Crear `backend/ktor` para el servicio definitivo. La base inicial solo contiene salud en loopback; no implica que autenticación/eventos ya estén migrados.
- Construcción común con Gradle Wrapper 8.11.1 y SHA-256 fijado, Kotlin 2.1.20, AGP 8.9.2, SDK 35/build-tools 35.0.0, JDK completo 21.0.12.1 con bytecode JVM 17, Compose BOM 2025.04.01 y Ktor 3.1.2.
- PostgreSQL 17.6 en una imagen fijada por digest para comprobar herramientas; revisar versiones/correcciones antes del despliegue real.
- Instalar SDK y cachés de nube en `.tooling` y dependencias Python en `.venv`; no requieren cambios en rutas de sistema ni contienen código que deba subirse a Git.

## Motivos y consecuencias

La ausencia inicial de herramientas se resuelve instalándolas; se conserva el producto Android pedido. El prototipo permite comparar comportamiento, pero no se comparte su autenticación artesanal con Ktor. Las versiones se fijan como conjunto compatible y reproducible, sin afirmar que sean las últimas ni aptas para publicar sin revisión.

No crear una librería compartida de dominio antes de que existan necesidades reales. Actualizar contratos/DTOs de ambos clientes al definir la API definitiva. Las pantallas de la base son informativas y no capturan datos ni fingen una sesión o inscripción funcional.
