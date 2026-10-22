# Dominio académico — modelos, contratos y reglas

Ruta: `academic/domain`, paquete `com.auditlab.domain`. Versión de herramientas conservada: Kotlin JVM 2.2.10 y JDK17; ninguna dependencia Android en este módulo.

## Modelos I02

- `Event`: UUID, timestamp numérico en milisegundos, tipo CALL/MESSAGE, dirección INCOMING/OUTGOING y snapshot `ConsentContext`. Las cuatro combinaciones representan los cuatro futuros botones. `isSimulated` siempre es true; no se puede cambiar mediante constructor/copy.
- `ConsentContext`: UUID de aceptación, versión de política de 1–40 caracteres identificadores y fecha no negativa. Un evento no puede preceder su aceptación.
- `Acceptance`: Pending, Granted(context) o Revoked(fecha opcional, borrado pendiente). Una fecha de revocación desconocida no se inventa ni implica aceptación.
- `AuditResult`: éxito con valor o rechazo con código estable. Los informes de revocación separan retirada guardada, borrado pendiente y borrado realizado con confirmación pendiente.

No hay números de teléfono, textos de mensajes ni datos de terceros en estos modelos. Este contrato local no modifica OpenAPI ni conecta el backend.

## Contratos y responsabilidades

| Contrato | Responsabilidad del adaptador |
| --- | --- |
| `EventRepository.insertIfBelowCapacity` | Contar e insertar atómicamente; rechazar capacidad/ID duplicado sin cambiar datos. |
| `EventRepository.latest` | Acotar el resultado y ordenar timestamp descendente e ID canónico ascendente para empates. |
| `EventRepository.deleteAll` | Borrado atómico de eventos con conteo no negativo. |
| `AcceptanceStore.read/save` | Ausencia = Pending; fallo de lectura lanza StorageException. Retornar de save solo tras confirmar escritura durable; nunca reaceptar sobre limpieza pendiente. |
| `EpochClock`, `EventIdGenerator` | Proporcionar tiempo e ID; sustituibles por valores deterministas en tests. |
| `AuditCoordinator` | Compartir exclusión entre servicios que operen sobre los mismos stores; no es la cola Android de I08. |

`StorageException` transmite un fallo esperado sin texto sensible del almacenamiento. Un bug del adaptador no se oculta como una escritura satisfactoria. La revocación debe guardarse antes de intentar borrar eventos: no se revierte si falla DELETE.

Los dobles en `src/test` solo almacenan datos en memoria. Sus tests verifican modelos y reglas, pero **no demuestran SQLite ni aceptación persistida**. Los adaptadores de I05/I06 están implementados y compilados en `app`; su ejecución instrumentada sigue pendiente. Consulta [persistencia y pruebas](PERSISTENCIA_ACADEMICA.md).

## AuditService — I03

Recibe repositorios, reloj, generador de UUID, versión actual de política y coordinador por constructor. No accede a Android ni crea sus propias dependencias; eso permite probar las reglas con datos ficticios deterministas.

| Operación | Regla y resultado |
| --- | --- |
| `record(kind, direction)` | Requiere Granted con política vigente, timestamp no anterior a la aceptación e ID válido. Inserta atómicamente hasta 1000; el registro 1001 se rechaza sin sobrescribir. |
| `history()` | Requiere aceptación vigente. Solicita como máximo 100, ordena por timestamp descendente/UUID ascendente y devuelve una copia acotada. Un fallo no se presenta como historial vacío. |
| `clearHistory()` | Borra sin leer ni cambiar la aceptación. Puede usarse sin aceptación o si falla su lectura. Devuelve conteo o error explícito; repetir devuelve cero. |
| `revokeAcceptance()` | Guarda Revoked con limpieza pendiente antes de borrar. Después intenta borrar y confirmar fin de limpieza. Un reloj averiado deja fecha desconocida, sin impedir retirar la aceptación. |
| `retryRevokedDeletion()` | Reintenta únicamente una limpieza con retirada confirmada; no necesita aceptación vigente. Si ya terminó, devuelve cero. |

La revocación tiene tres resultados de limpieza: `PENDING` si no se confirmó el borrado, `CLEARED` si terminó y se confirmó, o `CLEARED_CONFIRMATION_PENDING` si se borró pero no pudo guardarse el fin de limpieza. En los tres casos la retirada ya se guardó y no se revierte para recuperar una aceptación anterior.

Si falla **guardar la retirada inicial**, no se borra: se devuelve `ACCEPTANCE_WRITE_FAILED` y esa instancia del servicio bloquea nuevas operaciones que requieran aceptación hasta reintentar la revocación. Este bloqueo local no garantiza retirada tras recrear el servicio ni bloquea otra instancia cuyo almacén siga indicando Granted. La durabilidad real depende del adaptador de I06; no puede prometerse tras una escritura fallida. La UI futura debe distinguir este error de una retirada confirmada.

Todos los servicios que compartan almacenes deben recibir el mismo `SynchronizedAuditCoordinator`; así las operaciones síncronas quedan serializadas entre sí. El repositorio mantiene además el contrato de inserción atómica. Esto **no implementa la cola Android, busy ni ciclo de vida de I08**, ni coordinación entre procesos. Una futura escritura de aceptación debe respetar el mismo coordinador.

Los códigos `AuditError` permiten presentar errores específicos sin exponer mensajes internos de almacenamiento. La lectura de aceptación fallida, una política desactualizada y un ID duplicado nunca se convierten en éxito. La pantalla base aún no llama a este servicio.

## Pruebas

El usuario aprobó expresamente `kotlin.test` 2.2.10 con JUnit4. Se declaró `kotlin-test-junit:2.2.10` únicamente como `testImplementation`, sin añadir una dependencia de pruebas al APK.

En `academic/`, con JDK17/SDK36 configurados:

```bash
./gradlew :domain:test :domain:build
```

En esta nube, desde la raíz:

```bash
python3 .tooling/academic-setup/run-gradle.py :domain:test :domain:build --no-daemon
```

JUnit XML: `academic/domain/build/test-results/test`; HTML: `academic/domain/build/reports/tests/test`. Verificar número real de casos y fallos; no dar por probada la app Android por tener tests JVM satisfactorios.

Suite actual: **75 casos, 0 fallos/errores/omitidos**: ModelTest (9), RepositoryContractTest (6), AuditServiceTest (30), AuditConcurrencyTest (3), CsvReportTest (10) y AcceptanceServiceTest (17). Los casos concurrentes incluyen 1100 intentos de inserción sobre dos servicios y carreras entre registro/revocación; esas iteraciones no se cuentan como 1100 tests. Evidencias [I02](evidencias/academico/I02-2026-10-22.md), [I03](evidencias/academico/I03-2026-10-22.md), [I04](evidencias/academico/I04-2026-10-22.md) e [I06](evidencias/academico/I06-2026-10-22.md).

## CSV — I04

`CsvReport.render(snapshot)` no consulta la BD ni escribe archivos. Recibe una copia de eventos, ordena timestamp descendente/UUID ascendente y devuelve cabecera fija y filas con CRLF. No limita la exportación a los 100 visibles; una instantánea de 1000 conserva los 1000 IDs. El contexto de cada fila sigue siendo el de su generación. Las pruebas sintéticas de escape/fórmulas no amplían el modelo a texto libre. La escritura UTF-8 y el selector de destino corresponden a I09.

## Decisión del usuario — I06

`AcceptanceService` comparte coordinador con AuditService y el almacén. Su constructor no acepta; `load()` solo recupera la decisión, y un fallo o una política antigua devuelve un rechazo. `accept()` debe invocarse únicamente por una acción explícita de la futura UI: lee primero, bloquea limpieza pendiente (`CLEANUP_REQUIRED`), obtiene reloj/ID y devuelve éxito después de guardar. No crea eventos. Repetir la aceptación de una política vigente conserva su contexto; una nueva política exige un contexto nuevo. Un reloj anterior a la decisión previa o un generador que reutiliza el ID conocido se rechaza.

La lectura inicial de la futura pantalla debe comenzar bloqueada y habilitar acciones únicamente tras resultado confirmado; I06 no conecta aún esa pantalla. La persistencia real la proporciona SQLiteAcceptanceStore, con guardado de revocación y borrado en transacciones separadas. Su verificación Android sigue pendiente, como se detalla en la guía de persistencia.
