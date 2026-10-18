# Historias y trazabilidad de aceptación

Relación: P1.3. Estados de implementación en [AVANCE.md](AVANCE.md). Esta matriz define la verificación futura; no marca pruebas como aprobadas.

| Historia | Necesidad y resultado | Componentes / tareas | Criterios | Caso negativo obligatorio |
| --- | --- | --- | --- | --- |
| HU-01 | Como persona de A, conozco los datos y acepto o rechazo antes de iniciar. | Agente; P4.1, P5.3. | CA-01, CA-10 | Rechazar/no tocar la casilla y denegar indicador: cero captura/trabajos. |
| HU-02 | Como admin, genero un código temporal para inscribir un único agente autorizado. | API/centro/agente; P3.2, P4.1, P6.1. | CA-02, CA-06 | Código vencido/reutilizado, solicitudes concurrentes y auditor intentando generarlo. |
| HU-03 | Como agente, envío solo eventos propios bajo mi credencial y consentimiento. | API/agente; P3.3, P4.1. | CA-03, CA-04, CA-05 | Sin token, token de usuario, dispositivo ajeno, contexto alterado y campos extra/tipos incorrectos. |
| HU-04 | Como persona de A, conservo pendientes ante un corte de red sin duplicarlos. | Room/WorkManager/API; P5.1, P5.2, P3.3. | CA-07, CA-14 | Timeout tras commit, reinicio del proceso, mismo ID con distinto contenido y cola llena. |
| HU-05 | Como persona de A, detengo el monitoreo inmediatamente y revoco incluso sin red. | Agente/API; P4.3, P5.4. | CA-08, CA-09 | Red bloqueada, reinicio durante revocación y respuesta de envío tardía. |
| HU-06 | Como admin, revoco remotamente y el servidor deja de aceptar la credencial. | API/centro/agente; P3.5, P4.3, P6.4. | CA-06, CA-09 | Auditor por API directa; envío concurrente con revocación y dispositivo offline. |
| HU-07 | Como admin/auditor, inicio/cierro sesión y accedo según mi rol. | API/centro; P3.1, P6.1. | CA-03, CA-06, CA-13 | JWT alterado/vencido/algoritmo incorrecto, refresh reutilizado y token tras logout. |
| HU-08 | Como auditor, encuentro un evento y consulto su detalle sin acceder a contenido prohibido. | API/centro; P3.4, P6.2. | CA-04, CA-12, CA-14 | Rango inválido, páginas límite, búsqueda inexistente, recurso no autorizado y vacío/red caída. |
| HU-09 | Como auditor, exporto el conjunto filtrado y queda constancia. | API/centro; P3.4, P6.3. | CA-06, CA-12, CA-14 | Límite excedido, descarga interrumpida, valores CSV peligrosos y token inválido. |
| HU-10 | Como responsable, identifico quién consultó, exportó o revocó y el resultado. | API/centro; P3.5, P6.3. | CA-12 | Intento de editar auditoría y fallo de escritura del registro antes de devolver éxito. |
| HU-11 | Como persona participante, los datos dejan de conservarse cuando vence el plazo comunicado. | Agente/API; P3.5, P5.1. | CA-11 | Servidor sin consultas, datos exactamente en límite y reloj de dispositivo alterado. |
| HU-12 | Como responsable del piloto, instalo una versión reproducible y recupero los datos ante un fallo. | Todos; P0.2, P0.3, P7.1, P8.1–P8.3. | CA-13, CA-15 | Certificado inválido, BD inaccesible, restauración aislada y reinicio de servicios/apps. |

## Cobertura por criterio

| Criterio | Historias | Evidencia prevista |
| --- | --- | --- |
| CA-01 | HU-01 | Prueba Android de rechazo/aceptación y verificación de BD/workers. |
| CA-02 | HU-02 | Integración PostgreSQL de consumo atómico y expiración. |
| CA-03 | HU-03, HU-07 | API con tokens ausentes, alterados, expirados y de tipo incorrecto. |
| CA-04 | HU-03, HU-08 | Pruebas de contrato/tipos y transacción del lote. |
| CA-05 | HU-03 | Dos dispositivos con credenciales/contextos cruzados. |
| CA-06 | HU-02, HU-06, HU-07, HU-09 | Matriz de acceso UI + peticiones API directas por rol. |
| CA-07 | HU-04 | Red intermitente, IDs conservados y reinicio del proceso. |
| CA-08 | HU-05 | Revocación offline y reinicio sin reactivar monitoreo. |
| CA-09 | HU-05, HU-06 | Revocación/recepción concurrentes en PostgreSQL y contacto posterior del agente. |
| CA-10 | HU-01 | Permisos/indicador en Android mínimo y reciente soportados. |
| CA-11 | HU-11 | Tiempo controlado y tareas programadas sin tráfico. |
| CA-12 | HU-08, HU-09, HU-10 | Comparación acciones, filtros, resultado y actor con registros. |
| CA-13 | HU-07, HU-12 | Sesiones, logs saneados, storage y certificado inválido. |
| CA-14 | HU-03, HU-04, HU-08, HU-09 | Fixtures conocidos, filtros, páginas, CSV y conflictos de ID. |
| CA-15 | HU-12 | CI/compilación, dos dispositivos propios, reinicio y restauración. |

La revisión de esta matriz cierra la especificación de P1.3, no los criterios CA. Cada historia se verifica al implementar sus tareas y debe enlazar un informe en `docs/evidencias/`.
