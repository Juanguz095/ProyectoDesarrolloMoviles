# AuditLab — Registro de avance

Actualización: 26 de octubre de 2026, UTC. Los registros anteriores conservan su fecha y zona indicadas.

Plan vigente: [PLAN_MAESTRO.md](PLAN_MAESTRO.md). Instrucciones de entrada: [AGENTS.md](../AGENTS.md).

## Ampliación activa — 0.3.0-rc1, captura real

Solicitud del 27 de octubre de 2026 (America/Lima): registrar eventos reales de SMS y
llamadas. [Alcance y activación](CAPTURA_REAL.md). Esta ampliación reemplaza la
limitación de simulación para esos metadatos, sin admitir contenido ni números.
Los cierres y 278 casos de 0.2 que siguen más abajo son históricos.

- P3.3: booleano real/simulado conservado, categorías `sms`/`call`, códigos cerrados,
  validación de dirección/origen y migración Flyway V3. **54 casos de integración
  PostgreSQL aprobados**, más 8 unitarios Ktor. La actualización de la base persistente
  conserva 30 035 eventos y el fingerprint de IDs/contenido.
- P5.3: servicio visible iniciado por el usuario, autorización adicional, READ_SMS y
  READ_CALL_LOG opcionales; proyección exclusiva `_id/date/type`, exclusión del historial
  previo y de borradores/fallidos, deduplicación, pausa/retirada y envío automático con
  reintento persistente. No hay captura de telefonía al reiniciar o morir el proceso.
- P6.2: categorías nuevas, nombres legibles de SMS/llamadas y distinción Real/Simulado
  en historial/detalle; CSV conserva el booleano.
- P7: **75 comunes + 26 agente + 9 centro**, 49 de contrato y 32 de herramientas
  aprobados. APKs debug y prueba Android compiladas; lint debug 0 errores (6 avisos agente,
  2 centro); auditoría de manifiestos/red/firmas/Dex aprobada. Firma debug coincide con 0.2.
- P7.1: recorrido de telefonía **aprobado sobre el APK final en API28**: cinco eventos
  reales (SMS recibido/enviado, llamada perdida/saliente, estado del dispositivo),
  consulta API, CSV, deduplicación y revocación. Se contrastaron los hashes del APK
  de agente y de instrumentación instalados contra los archivos finales. La nueva
  prueba de UI del Centro comprueba el título «SMS enviado» y la marca «Real».
  [Informe y límites](evidencias/CAPTURA-REAL-0.3.0-2026-10-27.md).
  Primer
  intento bloqueado por espacio; resuelto con copia de imagen base inicializada sin
  copiar ni modificar overlays de datos anteriores. Un fallo del orquestador no reconocía
  el marcador de inicio (`test` no es binario ejecutable por run-as en API28); corregido
  mediante shell. Ese intento no generó SMS/llamadas y no cuenta como aprobado.
  Otro intento esperó la llamada saliente sin terminar: se corrigió el driver para
  esperar al módem y colgar mediante Android; luego hubo dos recorridos completos
  aprobados. Solo el último corresponde al APK final, con ventana para llamadas largas.
- P8: nuevos APK/servidor **todavía no publicados**. Los enlaces de GitHub existentes
  siguen siendo 0.2. Windows/teléfonos reales y Android reciente siguen sin ejecución aquí.

Evidencias privadas: `.tooling/real-events-validation/`. Se conservan los fallos de
compilación inicial de la prueba, lint/manifiesto y el nombre de constraint corregido
en V3, junto con las repeticiones satisfactorias. El entorno se desconectó brevemente;
los cambios se conservaron y se verificó el estado antes de continuar.

Siguiente: empaquetar/publicar la actualización de
archivos en la rama de descargas ya autorizada. No declarar funciones físicas validadas
por resultados JVM, API o telefonía emulada.

## Alcance actual de la solicitud

**Producto activo: agente Android + centro Android + API Ktor/PostgreSQL.** Candidata **0.2.0-rc1**, código funcional `1dcdf5f` e instrumentación `ed8f3ef`. P4–P6 implementadas y comprobadas con actividades reales: agente API28 y centro API26, Room/Keystore/WorkManager, notificaciones, consulta cruzada, CSV/rotación, auditor y retirada local/remota. La secuencia final de 17 pasos pasó sobre las APKs actuales, con hashes comprobados contra las instaladas. [Resultados/hashes actuales](evidencias/artefactos-0.2.0-rc1-2026-10-26.json).

Última repetición: **278 casos aprobados**, incluidos los 17 pasos nativos completos sobre las APKs actuales, tras añadir quince regresiones de recuperación/empaquetado. [Informe del 26 de octubre](evidencias/P7-P8-2026-10-26.md). Ver [manual del producto](MANUAL_PRODUCTO.md), [informe anterior](evidencias/P5-P8-2026-10-25.md), [entorno reproducible y paquete](PRUEBAS_INTEGRADAS.md) y [plan maestro](PLAN_MAESTRO.md).

La app `academic/` permanece como entrega local I01–I12, sin cambios en esta ampliación. Sus pruebas y publicaciones históricas no sustituyen las del producto completo. [Entrega académica](ENTREGA_ACADEMICA.md), [avance académico](AVANCE_ACADEMICO.md).

## Estado del proyecto completo

- **Implementación funcional del MVP terminada**, con verificación QA y correcciones de errores anteriores. P4.1–P4.3, P5.1–P5.4 y P6.1–P6.4 cuentan con evidencia nativa en las versiones indicadas.
- Host: **68 comunes + 21 agente + 8 centro + 8 Ktor + 53 integración PostgreSQL + 4 smoke HTTPS**, sin fallos/errores/omitidas. Además, 49 de contrato, 16 Python, 2 Node y **32 de herramientas** aprobadas: 261 host y 17 nativas, 278 en total.
- Carga repetida: 10 000 eventos, 100 páginas, CSV comparado por ID y cinco lectores; p95 **30,65 ms**, objetivo < 2000 ms, sin emuladores activos durante la medición.
- Restauración repetida: copia nueva idéntica en diez tablas, **30 032 eventos**, permisos runtime/mantenimiento, consulta por IDs y retención de la copia comprobados. Original y AVDs conservados.
- Dependencias runtime: Netty **4.1.137.Final**, Logback **1.5.34**, **156 módulos identificados/0 hallazgos** en la base de avisos registrada. No cubre SO/JDK/herramientas ni configuración operacional.
- Builds debug/release y lint aprobados. Debug firmadas para desarrollo; release auditadas con una copia firmada **solo con identidad QA**. Falta la firma del responsable para distribución.
- **No está cerrado el piloto:** faltan Android reciente y dos teléfonos propios, URL/infraestructura HTTPS con acceso autorizado, clave externa de distribución y comprobación operativa posterior. El usuario confirmó el 26 de octubre que no dispone de esos recursos; la entrega disponible es de desarrollo local con dos emuladores.
- Código y documentación se guardan en commits locales. La CI alojada de `a17dffd` sigue siendo evidencia histórica de P0.3; la candidata actual no se ha publicado en GitHub ni ha ejecutado su CI alojada.
- Nube: instalador y arranque del producto completo guardados en borrador; el instalador se ejecutó con código 0. Guardar no aplica/publica. Para activarlos, revisar y guardar en los ajustes del entorno y después publicar. La restauración en una tarea nueva no está acreditada, especialmente para commits solo locales.

## Seguimiento por fase

| Fase | Tareas pendientes | Tareas verificadas | Cierre |
| --- | --- | --- | --- |
| 0. Base | CI de la nueva candidata después de su publicación autorizada | P0.1, P0.2, P0.3 en su revisión histórica | Base reproducible; no atribuir su CI anterior al código nuevo |
| 1. Requisitos | Ninguna de especificación | P1.1, P1.2, P1.3 | Definidos |
| 2. Contratos/estructura | Ninguna | P2.1, P2.2, P2.3 | Verificada |
| 3. API | Ninguna funcional del MVP en QA | P3.1, P3.2, P3.3, P3.4, P3.5, P3.6 | Verificada en PostgreSQL y HTTPS QA |
| 4. Primer recorrido Android | Ninguna en API26/API28 | P4.1, P4.2, P4.3 | Verificada en dos emuladores |
| 5. Agente | Matriz Android reciente dentro de P7.1 | P5.1, P5.2, P5.3, P5.4 | Verificada en QA, incluido reintento de retirada por generación |
| 6. Centro | Validación adicional con lector de pantalla/dispositivos de entrega | P6.1, P6.2, P6.3, P6.4 en QA | Flujos nativos, texto al 150 %, selector y rotación comprobados |
| 7. Validación | P7.1: Android reciente/físico; completar la evaluación del entorno real de piloto | P7.2/P7.3 y revisión P7.4 en el alcance registrado | QA aprobada; aceptación de entrega pendiente |
| 8. Entrega | P8.1 HTTPS real; P8.2 firma/dispositivos; P8.3 repetición por tercero; P8.4 piloto | Restauración QA y manual/informe preparados | Condicionada a accesos, clave y dispositivos |

## Tabla de ejecución

| Tarea | Estado | Entregable / evidencia | Resultado, bloqueo y siguiente acción |
| --- | --- | --- | --- |
| P0.1 | verificada | [Evidencia](evidencias/P0.1-2026-10-17.md) | Fallos reproducidos/corregidos y límites del prototipo registrados. |
| P0.2 | verificada | [Evidencia](evidencias/P0.2-2026-10-17.md), [desarrollo](desarrollo.md). | Toolchain completo, PostgreSQL y compilación nativa comprobados. |
| P0.3 | verificada | [Evidencia](evidencias/P0.3-2026-10-17.md). | Ejecución alojada [40150599234](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/actions/runs/40150599234): 3 de 3 trabajos en verde, incluida la auditoría de APK. |
| P1.1 | verificada | [Evidencia fase 1](evidencias/P1-2026-10-17.md), [requisitos](requisitos.md). | Datos, alcance, acceso, retención y límites definidos. |
| P1.2 | verificada | [Estados](estados.md), [evidencia](evidencias/P1-2026-10-17.md). | Flujos positivos/negativos y revocación offline definidos. |
| P1.3 | verificada | [Historias](historias.md), [evidencia](evidencias/P1-2026-10-17.md). | 12 historias relacionadas con los 15 criterios, sin declarar aceptación funcional. |
| P2.1 | verificada | [Evidencia](evidencias/P0.2-2026-10-17.md), [ADR](decisiones/0001-estructura-y-herramientas.md). | Dos bases Android y Ktor compiladas; prototipo conservado. |
| P2.2 | verificada | [Evidencia](evidencias/P2.2-2026-10-17.md), [contrato](contrato.md). | OpenAPI 0.2 y 49 pruebas de contrato aprobadas. Prototipo 0.1 conservado. |
| P2.3 | verificada | [Evidencia](evidencias/P2.3-2026-10-17.md), [BD](base-datos.md). | Flyway repetible, bootstrap Argon2id, permisos y disponibilidad; 5 unitarios + 15 PostgreSQL satisfactorios. |
| P3.1 | verificada | [Evidencia](evidencias/P3.1-2026-10-17.md), [autenticación](autenticacion.md). | Login/refresh/logout, roles, límites, auditoría y concurrencia; 8 unitarios + 31 integración aprobados. |
| P3.2 | verificada | [Evidencia P3.2–P3.6](evidencias/P3.2-P3.6-2026-10-24.md). | Código de un uso con consumo atómico, consentimiento versionado, credencial opaca con hash; doble inscripción concurrente y roles probados. |
| P3.3 | verificada | [Evidencia P3.2–P3.6](evidencias/P3.2-P3.6-2026-10-24.md). | Ingesta por lotes atómica, límites, solo eventos simulados, idempotencia y conflicto de contenido probados. |
| P3.4 | verificada | [Evidencia P3.2–P3.6](evidencias/P3.2-P3.6-2026-10-24.md). | Filtros, cursores ligados a actor/filtros, detalle, CSV con límite y auditoría de consultas probados. |
| P3.5 | verificada | [Evidencia P3.2–P3.6](evidencias/P3.2-P3.6-2026-10-24.md). | Revocación remota y local, bloqueo inmediato de escrituras, auditoría y retención programada con cuenta de mantenimiento. |
| P3.6 | verificada | [Evidencia P3.2–P3.6](evidencias/P3.2-P3.6-2026-10-24.md), [despliegue](despliegue.md). | Reinicio conserva datos, `/ready` 503 sin BD, retención CLI 0, secretos ausentes del registro y guía HTTPS. |
| P4.1 | verificada en API28 | [Informe](evidencias/P5-P8-2026-10-25.md). | Consentimiento, código, inscripción real, credencial Keystore y evento contrastado en API/PostgreSQL. |
| P4.2 | verificada con centro API26 | [Informe](evidencias/P5-P8-2026-10-25.md). | Una segunda app consulta el ID enviado por el agente, detalle y auditoría. |
| P4.3 | verificada en ambos sentidos | [Informe](evidencias/P5-P8-2026-10-25.md). | Revocación remota, rechazo posterior, retirada offline, proceso reiniciado y confirmación al volver red. |
| P5.1–P5.4 | implementadas y verificadas en QA | [Informe](evidencias/P5-P8-2026-10-25.md). | Room/migración/capacidad/retención, WorkManager real, indicador/ajustes, Keystore, pausa durable y retirada por generación sin intervención manual. |
| P6.1–P6.4 | implementadas y verificadas en QA | [Informe](evidencias/P5-P8-2026-10-25.md), [manual](MANUAL_PRODUCTO.md). | Login/roles/reinicio, filtros/detalle/auditoría, CSV por URI real, cancelación/rotación y confirmaciones con texto al 150 %. Lector de pantalla no ejecutado. |
| P7.1/P7.2 | matriz QA aprobada; Android reciente/físico pendiente | [Matriz](evidencias/P5-P8-2026-10-25.md). | Negativos/concurrencia/almacenamiento, contrato, sesiones y recorridos nativos; no aprobación de plataformas no ejecutadas. |
| P7.3 | verificada en QA local | [Medición](evidencias/P5-P8-2026-10-25.md). | 10 000 eventos; cinco lectores, 100 consultas, p95 34,68 ms; CSV y páginas comparados por ID. |
| P7.4 | revisada en el alcance QA registrado | [Seguridad](evidencias/P5-P8-2026-10-25.md). | Permisos/artefactos/backup/logs/cifrado/TLS Android; 156 dependencias runtime identificadas, 0 hallazgos. Configuración real, SO/JDK y herramientas fuera de ese scan. |
| P8.1 | restauración QA verificada; HTTPS de piloto pendiente | [Despliegue](despliegue.md), [informe](evidencias/P5-P8-2026-10-25.md). | Base nueva idéntica en diez tablas/20 022 eventos y API contra la copia. TLS QA validado desde Android con confianza explícita solo en la prueba. |
| P8.2 | builds/instalación QA comprobados; distribución pendiente | [Manual](MANUAL_PRODUCTO.md). | Debug instaladas en dos emuladores; release no depurables auditadas con firma QA. Falta clave del responsable e instalación en teléfonos de entrega. |
| P8.3 | manual e informe preparados | [Manual](MANUAL_PRODUCTO.md), [matriz](evidencias/P5-P8-2026-10-25.md). | Procedimientos reproducidos aquí; repetición independiente por un tercero aún no realizada. |
| P8.4 | pendiente | [Plan](PLAN_MAESTRO.md). | No hay despliegue/piloto acreditado; requiere URL, acceso, autorización y responsable operativo. |

## Bitácora de sesiones

### 27 de octubre de 2026 UTC — Guia Windows para dos celulares por USB

- P8.3: preparada [guia paso a paso para Windows](PRUEBA_WINDOWS_USB.md), con Docker Desktop/Java21, API precompilada y `adb reverse` individual para dos celulares propios. URL local en ambos: `http://127.0.0.1:8081`; no requiere tunel publico ni cambia la politica TLS fuera de loopback.
- Preparado paquete descargable `AuditLab-Windows-USB.zip` con asistente PowerShell de arranque. Genera configuracion privada, cuentas separadas, contenedor/volumen propios y conserva los datos; exige identidad del recurso antes de reutilizarlo. El usuario elige la contrasena de admin. Las APKs y la distribucion API mantienen los bytes de la entrega anterior.
- Comprobacion de contenido/CRC/checksums y correspondencia de APKs; sin ejecucion del asistente en Windows, PowerShell disponible o telefonos fisicos en este host Linux. No se suman pruebas ni se cierran P7.1/P8.2/P8.4 por preparar la guia. Sin push, despliegue ni publicacion.
- Publicacion posterior autorizada por el usuario: subida la rama de descargas [descargas-auditlab](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/tree/descargas-auditlab), commit `e3ab29074781ae9b9fe02cb925d1e2c2f673eaf2`, con las dos APKs, servidor Windows, README y SHA256SUMS. Los tres enlaces HTTPS de descarga devolvieron 200 y los archivos descargados coinciden por SHA256 con los preparados. Se publica la entrega de archivos; el piloto operativo sigue pendiente. Los enlaces internos del chat no funcionaron para el usuario. Evidencia local: `.tooling/download-link-check/summary.json`.

### 26 de octubre de 2026 UTC — Repetición completa y entrega de desarrollo

- P7/P8: repetidas las 17 etapas nativas en agente API28/centro API26, con protocolos completos y sin fallos/omisiones. Las dos APKs de app conservan sus hashes; la instrumentación recompilada del centro tiene hash nuevo y su ejecución propia.
- 261 casos host finales y 17 nativos: **278 aprobados**. Build forzado de 279 tareas, debug/release/instrumentación, lint 0 errores; 53 PostgreSQL, HTTPS/contrato, carga p95 30,65 ms y restauración de 30 032 eventos aprobados. Runtime: 156 módulos/0 hallazgos en la base de avisos del 26 de octubre.
- Corregida la suposición de 100 filas en la restauración y añadidas comprobaciones de permisos/retención recuperados. Nuevo empaquetador con APKs/API/manuales/código, checksums y verificación de los hashes instalados; quince regresiones nuevas aprobadas.
- Comprobadas restauraciones reales de 0 y 3 eventos. La entrega académica conservada aprobó también sus 131 casos host, builds/políticas y lint 0; su Android no se repitió. Datos académicos intactos y ambos emuladores propios detenidos, sin borrar AVDs/bases.
- Paquete extraído: checksums y exclusión de credenciales comprobados, API del ZIP probada por HTTPS contra una copia QA y código incluido reconstruido en 52,88 s (80 tareas). El primer verificador se invocó con Python sin Requests; la repetición con el `.venv` fijado pasó. [Manifiesto](evidencias/artefactos-0.2.0-rc1-2026-10-26.json).
- El usuario confirmó que no tiene servidor/dominio, clave de firma de distribución ni teléfonos. P7.1/P8.1/P8.2/P8.4 conservan sus requisitos externos; no se declara cerrado el piloto. P8.3 mantiene pendiente la repetición independiente por un tercero.
- Sin push, despliegue ni publicación del entorno. [Informe y comandos](evidencias/P7-P8-2026-10-26.md), [paquete de desarrollo](PRUEBAS_INTEGRADAS.md#paquete-instalable-de-qa).
- **Siguiente tarea externa:** P7.1 en Android reciente/teléfonos y P8.1/P8.2/P8.4 al disponer de recursos. La entrega local cuenta con las comprobaciones de paquete y recuperación adicionales registradas en el informe.

### 25 de octubre de 2026, Lima (26 UTC) — MVP completo y aceptación QA

- Producto completo: P4–P6 verificadas con agente API28 y centro API26 contra API/PostgreSQL reales. Cola Room/retención, WorkManager, visibilidad, Keystore, revocación offline/remota, roles, historial/filtros/detalle, CSV/auditoría y confirmaciones.
- **263 pruebas distintas aprobadas**, incluidas 17 etapas nativas completas, más 23 respuestas HTTPS contrastadas con OpenAPI. Se conservaron los fallos previos del reloj Compose, generación de trabajos, firma/auditor release y selector tras rotación, con sus correcciones y repeticiones. No se contabilizan como aprobaciones ni se ocultan las campañas fallidas.
- Carga final: 10 000 eventos, p95 34,68 ms con cinco lectores; restauración nueva de 20 022 eventos/10 tablas y permisos reales comprobados. Runtime Maven: 156 módulos/0 hallazgos tras corregir Netty/Logback; ámbito y base de avisos documentados.
- Compilación limpia debug/release/AndroidTest/Ktor y lint 0 errores; firma release QA comprobada, sin clave de distribución del responsable. Las APKs actuales coinciden por SHA256 con las instaladas en la secuencia nativa final.
- Conservadas fuentes y datos académicos (dump antes/después idéntico), AVDs y volúmenes. Solo se detuvieron procesos propios y se retiraron entradas temporales de las pruebas. API/BD QA listas en loopback.
- Código en commits locales `1dcdf5f` y `ed8f3ef`; sin push, CI alojada nueva, despliegue o publicación del entorno. Instalador/arranque guardados en borrador y comprobados en esta instancia.
- **Siguiente tarea:** P7.1 en Android reciente/teléfonos; después P8.1/P8.2/P8.4 con URL/acceso, certificado, firma y autorización del responsable. P8.3 tiene manual/informe; repetición independiente pendiente. [Informe](evidencias/P5-P8-2026-10-25.md), [manual](MANUAL_PRODUCTO.md), [hashes](evidencias/artefactos-0.2.0-rc1.json).

### 25 de octubre de 2026 UTC — implementación P5/P6 y verificaciones P7/P8

- Solicitud de completar el proyecto y realizar todas las pruebas posibles: implementadas las funciones del agente y del centro descritas arriba, manteniendo API/contrato y app académica.
- Corregidos fallos de cola/ACK, retirada offline/concurrente, sesión con rol renovado, origen de credenciales, errores de cifrado/almacenamiento, exportación fallida y conservación de contraseña en formulario.
- Nuevas pruebas y regresiones: 63 comunes, 18 agente/7 centro JVM, 53 PostgreSQL; contrato 49, prototipo 16 + 2 Node; HTTPS/contrato real aprobados. Debug/release construidos, lint 0 errores; release sin firma de distribución.
- P7.3 cumplida en QA local. Restauración real en BD nueva comprobada; corregida propiedad de tablas para aplicar grants. [Evidencia y comandos](evidencias/P5-P8-2026-10-25.md).
- API28 inicia sin borrar datos; recorrido nativo en curso. P4 y aceptación final siguen pendientes hasta contar con evidencia propia. Sin push/despliegue/publicación en esta sesión.

### 25 de octubre de 2026 UTC — pull, recuperación del fixture e intento API30

- Pull solicitado por el usuario: incorporados `b3d90b7`…`a17dffd` sin reescribir commits y conservando `9c62c7e`. Conflicto de `docs/AVANCE.md` resuelto con ambos historiales y estado actual; merge `5b263ba`. La API de GitHub Actions devolvió `Forbidden` al reconsultarla.
- Nuevo `scripts/local_fixture.py`: PostgreSQL con volumen propio y secretos/certificados bajo `.tooling/integration-fixture`, fuera de `/tmp`; venv existente validado contra el lock. API reiniciada sin cambios en dispositivos/eventos. [Guía](PRUEBAS_INTEGRADAS.md).
- Corregidos configuración de home/proxy/CA de las JVM hijas, selección explícita de CA en Requests, aislamiento de mantenimiento/TLS en el chequeo PostgreSQL y rutas temporales. Resultado: 8 unitarias, 53 integración, 23 comunes, 7 UI JVM, 3 humo HTTPS, contrato 23 respuestas y auditoría de ambos APK aprobados.
- Imagen API30/AVDs de 2 GiB: ambos completaron boot. El agente abrió detrás de ANR de System UI; la repetición con un solo AVD también mostró el bloqueo. Intentos, rechazos por espacio, recuperación de lock zombie y capturas conservados. P4 no se cierra. [Evidencia](evidencias/P4-2026-10-25.md).
- AVDs/datos conservados, emuladores propios detenidos; fixture API/BD disponible en loopback. Próximo desarrollo independiente P5.1; ejecución Android, entrega firmada y piloto con sus requisitos pendientes.

### 24 de octubre de 2026 UTC — verificación de funcionamiento (5 vías)

- Solicitud: «¿ahora todo funciona bien? ¿qué puedes hacer para probarlo?». Se eligieron cinco comprobaciones: auditoría de APK, concurrencia, contrato en vivo, HTTPS real y UI con Robolectric.
- **Auditoría de APK** (`scripts/check_apk.py`, nuevo): ambos APK pasan con solo `INTERNET` y el permiso propio de AndroidX, sin permisos peligrosos, `usesCleartextTraffic=false`, HTTP solo en loopback, firma v2 y clases propias en el dex. Hallazgo: `ui-test-manifest` inyectaba una `ComponentActivity` exportada en el APK debug; se sustituyó por una actividad anfitriona propia no exportada y se eliminó la dependencia.
- **Concurrencia** (`ConcurrencyIntegrationTest`, nuevo): 6 pruebas, 0 fallos. 16 entregas del mismo `eventId` → 1 aceptada y 15 duplicadas con una sola fila; 16 inscripciones con un código → exactamente 1; revocación y 8 entregas simultáneas → ningún evento posterior a `revoked_at`.
- **Contrato en vivo** (`scripts/check_live_contract.py`, nuevo): 23 respuestas reales validadas contra OpenAPI 0.2, 0 fallos, incluidas cabeceras y `application/problem+json`. Hallazgo del propio validador: no seguía los `$ref` de `components/responses/*`; corregido.
- **HTTPS real** (`scripts/check_tls.sh`, nuevo): TLS nativo en Ktor con `AUDITLAB_TLS_CERTIFICATE`/`AUDITLAB_TLS_PRIVATE_KEY`; con TLS solo se abre el puerto cifrado. Comprobados HTTPS con CA propia, rechazo de HTTP en claro, rechazo sin CA, contrato en vivo por HTTPS, cliente JVM con truststore propio y ausencia de secretos en el registro.
- **UI con Robolectric** (`AgentScreenTest` 4, `ControlScreenTest` 3): 7 pruebas, 0 fallos. Cubren consentimiento, inscripción, evento, revocación remota y local, roles admin/auditor y consulta de eventos. Hallazgo: con la pantalla por defecto (320×470 dp) las pulsaciones no llegaban a los botones; corregido con `w411dp-h891dp` y `performScrollTo()`.
- **Suite completa desde base vacía**: 8 unitarias y 53 de integración PostgreSQL, 0 fallos; 23 pruebas JVM de `apps/common`; lint 0 errores y 7 avisos por app (versiones disponibles y `UseKtx`); contrato 49 pruebas; prototipo 16 pruebas; documentación válida.
- **Publicación y CI**: con autorización explícita se publicaron los tres commits en `main` (`b3d90b7`, `c72c367`, `e918fed`) y la CI alojada pasó entera en [40150599234](https://github.com/Juanguz095/ProyectoDesarrolloMoviles/actions/runs/40150599234), cerrando P0.3. Antes hubo dos fallos reales de CI: `sdkmanager` fuera del `PATH`, la prueba de retención del prototipo leyendo antes del commit y `check_apk.py` usando `/tmp/opencode`; los tres se corrigieron.
- **Límites**: no se ejecutó el APK en emulador ni dispositivo (sin `/dev/kvm`, imagen ni teléfono), así que P4.1–P4.3 siguen sin validar en dispositivo. Evidencia: [verificación](evidencias/verificacion-2026-10-24.md).
- Siguiente tarea: **P4.1–P4.3 en un emulador o dispositivo compatible**.

### 24 de octubre de 2026 UTC — fase 3 verificada y fase 4 implementada

- Solicitud «sigue el avance y haz 3 fases asegurándote de que todo funciona bien»: se corrigió el prototipo y el workflow de CI, se completó la API definitiva (P3.2–P3.6) y se implementó el primer recorrido Android (P4.1–P4.3).
- **P0.3 (local):** la CI estaba en rojo por `sdkmanager` fuera del PATH y por una prueba de retención del prototipo que leía antes del commit. Se corrigieron ambos, se actualizaron las actions y se añadió `:apps:common:test` al job nativo. **Sigue bloqueada** la verificación alojada: no se hizo `push`.
- **P3:** código de un uso con consumo atómico, consentimiento versionado, credencial opaca con hash, ingesta idempotente por lotes, consultas con cursores ligados a actor/filtros, CSV con límite, revocación y retención con cuenta de mantenimiento. 8 unitarios y **47 integración PostgreSQL, 0 fallos**; comprobación manual de reinicio, `/ready` 503, retención CLI y ausencia de secretos. [Evidencia](evidencias/P3.2-P3.6-2026-10-24.md), [despliegue](despliegue.md).
- **P4:** módulo `apps/common` (cliente HTTP y lógica), agente y centro Compose, credencial/sesión cifradas con Android Keystore, TLS obligatorio fuera de loopback y revocación en ambos sentidos. **23 pruebas JVM y 3 de humo contra Ktor/PostgreSQL reales**, 0 fallos; ambos APK compilan y lint da 0 errores. **No se ejecutaron en emulador ni dispositivo** (sin `/dev/kvm` ni imagen de sistema), por lo que P4 no se declara validada. [Evidencia](evidencias/P4-2026-10-24.md).
- Sin despliegue, HTTPS de piloto ni validación Android. Próxima tarea: ejecutar P4.1–P4.3 en un emulador o dispositivo compatible.

### 24 de octubre de 2026 UTC — aclaración y restitución del alcance completo

- El usuario ratificó que solicita el proyecto original de dos apps conectadas a una API y pidió aclarar sus planes. Se restablece `PLAN_MAESTRO.md` como guía activa; la app local I01–I12 conserva su documentación y evidencias.
- Revisión de solo lectura del checkout limpio: `Application.kt` conecta autenticación, `/health` y `/ready`; las actividades de agente/centro son pantallas base. Inscripción, eventos y recorrido entre dispositivos siguen pendientes según la tabla del plan.
- Se aclaró el alcance en README, este registro y las entradas académicas. Siguiente tarea disponible P3.2; no se marca como implementada o verificada por haber revisado el plan. La petición actual de explicar las fases no acredita nuevas funciones ni pruebas de integración.

### 24 de octubre de 2026 UTC — publicación académica en GitHub

- Solicitud explícita «Sube los cambios a github». Se actualizó la referencia remota y se comprobó que `main` podía avanzar desde `118a738` sin sobrescribir historia.
- `git push origin main` terminó con código 0: `118a738..652b698 main -> main`. Los 14 commits pendientes incluyen implementación I01–I12 y la comprobación de 55/55 casos en cada uno de los emuladores API26/API28.
- Verificación independiente con `git ls-remote origin refs/heads/main`: `652b6988931ad3d191249c749f2b5c53ea08445d`, idéntico al HEAD publicado. Este registro se incorpora después de confirmar esa publicación.

### 23 de octubre de 2026 UTC — comprobación en dos emuladores

- Solicitud «Pruébalo con dos emuladores»: 55/55 Android8/API26 y repetición 55/55 Android9/API28; cero fallos, errores u omitidos. Misma APK y árbol fuente que I12, sin cambios de dependencias ni aserciones. Volcado normal API28 idéntico antes/después; API26 AVD nuevo. [Evidencia](evidencias/academico/DOS-EMULADORES-2026-10-23.md).
- Intentos preparatorios y cuarto intento API36 conservados con sus límites: watchdog del sistema antes de instalar la app; interrupción deliberada durante instalación, sin pruebas de la app ejecutadas. Pendientes API36 estable, teléfono físico, IDE y rúbrica. Fase 2 no iniciada.

### 23 de octubre de 2026 UTC — I12 académico

- Final 0.1.0-i12/12 con 55 Android API28, 93 JVM, 15 SQL y 23 herramientas aprobadas; lint debug/release 0 y firmas/políticas verificadas. Nueve checks reales incluidos rotación del selector, proceso detenido y fallo de DELETE/reintento persistente. Original de fixture restaurado; CSVs anteriores conservados.
- Herramienta de entrega corregida para XML agrupado e instalación que conserva datos; intentos previos preservados. [Informe](evidencias/academico/I12-2026-10-23.md), [entrega](ENTREGA_ACADEMICA.md). API36 no aprobada: 46 casos pasaron, 4 timeouts de UI y 5 incompletos ante ANR del sistema, con captura y logs preservados. Límites externos IDE/físico/rúbrica. Commit local, sin push/despliegue o fase 2.

### 23 de octubre de 2026 UTC — I11 académico

- Revisión SSDF y políticas de manifiestos/backup/transferencia/Dex en debug y release, sin dependencias nuevas. Tres fallos reproducidos (52 casos/3 fallos) y corregidos; luego 54/54 Android API28, 93 JVM, 15 SQL y 14 políticas aprobadas. Lint debug/release 0.
- [Evidencia I11](evidencias/academico/I11-2026-10-23.md), [seguridad](SEGURIDAD_ACADEMICA.md). Commit local y continuación I12 autorizada; sin push/publicación.

### 22 de octubre de 2026 — Ejecución I10 académica

- Controles de borrado/revocación con confirmación, errores/reintentos y bloqueo inmediato de exportaciones pendientes; app 0.1.0-i10. Sin nuevas dependencias de producción o cambios de herramientas.
- 93 JVM, 15 SQL host y completa final 49/49 Android API28 aprobadas. Siete comprobaciones del recorrido real y proceso detenido/relanzado aprobadas, con CSV externo comparado por IDs. Ajuste de fondo/contraste revisado en capturas y repetido en tests; respaldo manual corregido para incluir WAL.
- API36: intento conectado fallido (44 casos, 4 fallos de UI); repetición con otro renderizador/un vCPU también bloqueada por ANR de Quickstep/System UI (4 terminados, 3 fallos). Capturas y XML preservados; no se declara aprobada. Repetir en una instancia estable con KVM o dispositivo compatible. [Evidencia I10](evidencias/academico/I10-2026-10-22.md), [manual](USO_ACADEMICO.md). Commit local de cierre y documentación; sin push/despliegue ni fase 2. I11 es el siguiente incremento, pendiente de autorización.

### 22 de octubre de 2026 — Ejecución I09 académica

- Exportación explícita por SAF, UTF-8, snapshot completo y revalidación de contexto. 93 JVM y 6 pruebas Android de exportación aprobadas; 33 base aprobadas en la completa previa. Fallos de fixtures de proveedor/permisos corregidos y registrados sin atribuir éxito a sus intentos fallidos. [Evidencia](evidencias/academico/I09-2026-10-22.md).
- I10 repetirá la suite integrada. Imagen/AVD API36 preparados, pruebas aún pendientes. Commit local separado; sin push o publicación.

### 22 de octubre de 2026 — Ejecución I08 académica

- Sesión/cola compartidas y WindowInsets implementados; 80 JVM, 15 SQL host y 33 Android aprobadas. Primer intento 27 Android detectó creación concurrente de tablas; corregida con DDL idempotente, sin omitir pruebas ni cambiar versiones. [Evidencia](evidencias/academico/I08-2026-10-22.md).
- Verificados botones, consentimiento, pulsaciones rápidas, recreación, reapertura y recuperación de error. Muerte de proceso pendiente. Commit local separado, continúa I09 autorizado.

### 22 de octubre de 2026 — Ejecución I07 académica

- Pantalla Views/ScrollView conectada a servicios y SQLite, aceptación explícita, cuatro eventos y lista de 100; errores en español. Dos builds salida 0; Overdraw corregido y lint final 0. [Evidencia](evidencias/academico/I07-2026-10-22.md).
- ADB resuelto mediante permiso específico y creación de .android; emulador oficial API28 instalado y en arranque software, sin afirmar todavía pruebas Android. Commit local separado; sigue I08 autorizado.

### 22 de octubre de 2026 — Ejecución I06 académica

- Aceptación persistente en SQLite, migración 1→2 conservando eventos y servicio de decisión explícita con dependencias inyectadas. Sin dependencias nuevas ni cambios de herramientas; UI base conservada.
- Suite renovada: 75 JVM y 15 SQLite host aprobadas. APK principal/pruebas, firmas y lint (0 incidencias) verificados. 27 casos instrumentados compilados y sin ejecutar por falta de Android operativo; persistencia Android no acreditada. [Evidencia I06](evidencias/academico/I06-2026-10-22.md), [guía](PERSISTENCIA_ACADEMICA.md).
- Commit local separado; borrador de arranque actualizado, sin publicación. Se detiene antes de I07. Sin push, despliegue, cambios del sistema ampliado o fase 2.

### 22 de octubre de 2026 — Ejecución I05 académica

- Repositorio SQLite Android implementado con restricciones, índice, cupo/inserción transaccional y errores explícitos. APK principal y de instrumentación compilados; lint 0 incidencias. Sin nuevas dependencias externas.
- Siete pruebas del DDL en SQLite host aprobadas; 12 instrumentadas compiladas pero sin ejecutar por bloqueo ADB y falta de dispositivo/emulador. Cierre Android pendiente, sin inventar verificación. [Evidencia](evidencias/academico/I05-2026-10-22.md).
- Commit local separado; continúa I06 por autorización actual. Sin UI de I07, push o fase 2.

### 22 de octubre de 2026 — Ejecución I04 académica

- Autorizada continuación I04–I06. CSV puro implementado, sin dependencias nuevas; 10 casos nuevos y 58/58 JVM aprobados. [Evidencia I04](evidencias/academico/I04-2026-10-22.md).
- Commit local separado; I05 es el siguiente incremento autorizado. No se implementó exportación Android, I07 ni fase 2.

### 22 de octubre de 2026 — Pruebas académicas solicitadas

- Reejecutados tests, build y lint de `academic/` sobre `c67e4c9`, forzando tareas: salida 0; 48 pruebas JVM aprobadas, 0 fallos/errores/omitidas; lint 0 incidencias. Firma y manifiesto del APK comprobados.
- Prueba Android sin ejecutar: sin USB, KVM, emulador o imagen instalada; ADB falla intentando crear una carpeta personal de solo lectura. No se declara un recorrido funcional Android ni SQLite verificados.
- [Evidencia de esta ejecución](evidencias/academico/VERIFICACION-I01-I03-2026-10-22.md). Sin cambios de código/dependencias, nueva funcionalidad, push o publicación.

### 22 de octubre de 2026 — Ejecución I02/I03 académica

- Solicitud de continuar hasta I03 atendida en orden; dependencia de pruebas kotlin.test2.2.10/JUnit4 expresamente aprobada por el usuario, sin cambiar versiones de herramientas.
- Modelos/contratos y AuditService implementados solo en `academic/domain`: aceptación, límites 1000/100, errores explícitos, borrado y revocación antes de limpieza. Coordinación compartida comprobada con dobles en memoria; no acredita SQLite, durabilidad ni cola Android.
- 15 pruebas de I02 y 33 nuevas de I03: **48 aprobadas, 0 fallos/errores/omitidas**. Build Android, firma debug y lint (0 incidencias) verificados. Evidencias [I02](evidencias/academico/I02-2026-10-22.md) e [I03](evidencias/academico/I03-2026-10-22.md).
- Commits locales separados por incremento; sin push, despliegue o fase 2. Instrucciones de arranque de nube actualizadas en borrador, sin publicación. I01 mantiene pendientes IDE/Android; I04 requiere una nueva autorización.

### 22 de octubre de 2026 — Ejecución I01 académica

- Base `academic/app` y `academic/domain` creada sin modificar fuentes/configuración de apps/backend anteriores.
- Versiones exigidas preservadas; Build/lint y probe de compilación JVM satisfactorios, APK debug firmado y manifiesto sin permisos. Evidencia y límites en [I01](evidencias/academico/I01-2026-10-22.md).
- Sin pruebas funcionales todavía: domain vacío/NO-SOURCE. Sync IDE y ejecución en Android pendientes por falta de sesión IDE y fallo ADB en ruta personal de solo lectura; no se acredita una aceptación completa.
- Preparación de nube repetible guardada en borrador; commit local para revisión, sin push ni despliegue. Se detiene antes de I02 por la regla explícita del contexto del usuario.

### 22 de octubre de 2026 — Plan académico y propuesta I01

- Leídos ambos textos aportados; el contexto posterior determina requisitos y proceso de aprobación.
- Entregados plan académico, inventario/propuesta limitada a I01 y registro independiente de doce incrementos pendientes.
- Corregidas diferencias del primer beta: cuatro botones de eventos, Views/ScrollView, límites 1000/100, CSV numérico/CRLF y revocación persistente aunque falle el borrado.
- No se modificó código, configuración Gradle ni contratos del sistema anterior. Sin instalación de herramientas, Build, Sync, pruebas de aplicación, commit o push. No se ejecutó P3.2 ni se autorizó fase 2.

### 18 de octubre de 2026 — Publicación inicial en GitHub

- El usuario autorizó subir el proyecto a `Juanguz095/ProyectoDesarrolloMoviles`.
- El origen HTTPS coincide con el repositorio indicado; lectura remota correcta y sin referencias existentes al comenzar. No hay historial remoto que reemplazar.
- Preparados 77 archivos de código, configuración, pruebas y documentación para el primer commit en `main`. Revisión de rutas, tamaños y patrones habituales de secretos sin hallazgos; excluidos `.tooling`, `.venv`, builds, datos SQLite, variables locales y llaves de firma.
- Push normal a `origin/main` completado, salida 0, sin forzar. Commit inicial `a88103a319d65091dfe32e660e97114ee7728022`; consulta posterior con `git ls-remote` confirma el mismo SHA en GitHub. Esta actualización documental se publica en un segundo commit y su SHA final se verifica al terminar.
- Comprobación documental satisfactoria (34 tareas, 15 criterios, 22 documentos). `diff --check` reconoce los finales CRLF del wrapper Windows mediante `core.whitespace=cr-at-eol`; el wrapper no se modifica.
- No se repiten suites funcionales en esta sesión de publicación; resultados previos y límites en las evidencias P0–P3.1. CI alojada pendiente de verificación. Sin despliegue ni publicación del entorno.
- El avance funcional se conserva: P3.1 verificada, siguiente P3.2.

### 17 de octubre de 2026 — Autenticación HTTP definitiva

- P3.1: tres rutas auth, JWT por biblioteca con emisor/audiencia/tipo, refresh rotatorio, logout, sesión absoluta y comprobación de rol/estado en cada acción protegida.
- V2 añade cuotas PostgreSQL compartidas por cuenta/cliente/global con HMAC; V1 conservada. Auditoría y acciones confirmadas en la misma transacción.
- Ocho unitarios y 31 integración aprobados sin omitidos; tres ejecuciones completas sobre fixtures diferentes. El servidor de la última usa únicamente credenciales runtime, sin variables administrativas. Refresh concurrente y logout no dejan descendientes válidos; fallo de auditoría revierte el login.
- Distribución real: login/refresh, reinicio propio, refresh persistente, logout y rechazo posterior; caída de BD produce `/ready` y login 503, liveness 200. Contratos válidos y 49 fixtures aprobados.
- `start_skill` y cuatro requisitos de variables BD/firma guardados en borrador confirmado, sin valores; instalador/red/repositorios preservados. Para activar: revisar y guardar en ajustes del entorno, después publicar. No se realizó esa publicación.
- Documentación final válida: 34 tareas, 15 criterios trazados y 22 documentos. Sin cambios Android, push, despliegue o CI alojada. Evidencia y límites en P3.1; siguiente P3.2.

### 17 de octubre de 2026 — Contratos y persistencia definitiva

- P2.2: contrato 0.2 de 13 paths/14 operaciones, esquemas estrictos, errores, ejemplos y 49 tests aprobados; prototipo 0.1 conservado.
- P2.3: migración V1 de ocho tablas, FK/checks/índices, Flyway, pool y CLI bootstrap/grants. Cinco unitarios y quince tests PostgreSQL aprobados, sin omitidos.
- Comprobados comandos de distribución instalada, repetición de migraciones/bootstrap, rechazo sin contraseña y `/ready` 200→503 al detener BD; `/health` permanece vivo.
- CI ampliada para contrato, JVM e integración; ejecución alojada sigue pendiente. No se cambió código Android ni se atribuyó progreso funcional a los APKs base.
- `start_skill` actualizado y guardado como borrador confirmado con instrucciones de BD/CLI, prueba aislada y disponibilidad. El instalador y los demás campos se conservaron. Para activar esa configuración hay que revisarla y guardarla en los ajustes del entorno y después publicar; esta sesión no la publicó.
- Comprobación documental final: salida 0; 34 tareas, 15 criterios trazados y 19 documentos con enlaces válidos.
- Evidencias de fallos/correcciones y límites en P2.3; siguiente tarea P3.1.

### 17 de octubre de 2026 — Primera ejecución del plan

- P0.1: pruebas reales del prototipo, correcciones de validación/consentimiento/idempotencia y revocación local anterior a red.
- P0.2: instalación verificada de herramientas en rutas escribibles; construcción Android/Ktor y prueba PostgreSQL con contraseña incorrecta/correcta.
- P1.1–P1.3: requisitos, estados e historias con trazabilidad de los 15 CA.
- P2.1: módulos, ADR, APKs debug y distribución Ktor base; lint regenerado sin incidencias y arranque HTTP comprobado.
- P0.3: estructura/CI y guías preparadas; GitHub Actions pendiente de publicación autorizada y resultado alojado.
- Configuración: `install_script` y `start_skill` guardados en borrador; no se publicó el entorno. Sin commit, push o despliegue.
- Repetibilidad: ejecutado el contenido exacto del instalador guardado, salida 0. Comprobación final documental: 34 tareas, 15 CA trazados y 14 documentos con enlaces válidos; contrato de 9 rutas válido y XML actual de lint sin incidencias.
- Siguiente sesión: P2.2 y P2.3. Mantener como pendientes los flujos Android, API definitiva y aceptación en dispositivos.

### 17 de octubre de 2026 — Documento de ejecución

- Inspeccionados: archivos presentes, Git, documentos y partes de código/contrato pertinentes al estado inicial.
- Entregados: plan maestro, guía de agente, registro de avance y enlace de entrada desde README.
- Comprobación documental ejecutada con Python, salida 0: enlaces locales válidos, 34 tareas únicas presentes en el seguimiento y 15 criterios de aceptación definidos. Esta comprobación no valida la aplicación.
- Alcance: documentación; no implementación nueva, compilación, prueba funcional, commit, push ni despliegue.
- Siguiente sesión de desarrollo: comenzar P0.1; continuar tareas disponibles tras registrar resultados.

## Actualización obligatoria al terminar una sesión

Registrar tarea realizada, estado real, archivos y evidencias, comprobaciones ejecutadas, defectos/bloqueos y siguiente tarea. Mantener separadas las decisiones propuestas de las verificadas. No reemplazar resultados fallidos por un estado genérico de «listo».
