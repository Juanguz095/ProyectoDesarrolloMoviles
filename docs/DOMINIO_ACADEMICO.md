# Dominio académico — modelos, contratos y reglas

Ruta: `academic/domain`, paquete `com.auditlab.domain`. Versión de herramientas conservada: Kotlin JVM 2.2.10 y JDK17; ninguna dependencia Android en este módulo.

## Modelos I02

- `Event`: UUID, timestamp numérico en milisegundos, tipo CALL/MESSAGE, dirección INCOMING/OUTGOING y snapshot `ConsentContext`. Las cuatro combinaciones corresponden a los cuatro botones. `isSimulated` siempre es true; no se puede cambiar mediante constructor/copy.
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
| `EventSnapshotSource` | Instantánea completa y consistente para exportación, separada del historial limitado a 100. El servicio rechaza tamaño mayor de 1000 o IDs repetidos. |

`StorageException` transmite un fallo esperado sin texto sensible del almacenamiento. Un bug del adaptador no se oculta como una escritura satisfactoria. La revocación debe guardarse antes de intentar borrar eventos: no se revierte si falla DELETE.

Los dobles en `src/test` solo almacenan datos en memoria. Sus tests verifican modelos y reglas, pero **no demuestran SQLite ni aceptación persistida**. Los adaptadores de I05/I06 están conectados a la pantalla y cuentan con pruebas instrumentadas ejecutadas; se distinguen sus resultados en [persistencia y pruebas](PERSISTENCIA_ACADEMICA.md) y [evidencia I10](evidencias/academico/I10-2026-10-22.md).

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

Si falla **guardar la retirada inicial**, no se borra: se devuelve `ACCEPTANCE_WRITE_FAILED` y esa instancia del servicio bloquea nuevas operaciones que requieran aceptación hasta reintentar la revocación. Este bloqueo local no garantiza retirada tras recrear el servicio ni bloquea otra instancia cuyo almacén siga indicando Granted. La UI distingue ese error de una retirada guardada y ofrece reintentar; no promete persistencia tras escritura fallida. AuditSession conserva la misma instancia durante rotación y bloquea también reaceptación/exportación mientras falta guardar la retirada.

Todos los servicios que compartan almacenes reciben el mismo `SynchronizedAuditCoordinator`, reentrante. El repositorio mantiene además la inserción atómica. AuditSession/I08 añaden una SerialOperationQueue de alcance de proceso, busy, observadores que se retiran al dejar la Activity y cierre de BD detrás de operaciones pendientes. La exclusión no coordina procesos distintos; la app principal no declara procesos adicionales.

Los códigos `AuditError` permiten presentar errores específicos sin exponer mensajes internos. La lectura de aceptación fallida, una política desactualizada y un ID duplicado nunca se convierten en éxito. La pantalla I07–I10 utiliza estos resultados y mantiene bloqueadas las acciones protegidas mientras no se compruebe la aceptación.

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

Suite JVM: **93 casos, 0 fallos/errores/omitidos**: ModelTest (9), RepositoryContractTest (6), AuditServiceTest (30), AuditConcurrencyTest (3), CsvReportTest (10), AcceptanceServiceTest (17), SerialOperationQueueTest (5) y ExportServiceTest (13). Las iteraciones concurrentes no se cuentan como casos adicionales. Resultados por versión en [I10](evidencias/academico/I10-2026-10-22.md); conserva las evidencias de cada incremento anterior.

## CSV — I04

`CsvReport.render(snapshot)` no consulta la BD ni escribe archivos. Recibe una copia de eventos, ordena timestamp descendente/UUID ascendente y devuelve cabecera fija y filas con CRLF. No limita la exportación a los 100 visibles; una instantánea de 1000 conserva los 1000 IDs. El contexto de cada fila sigue siendo el de su generación. Las pruebas sintéticas de escape/fórmulas no amplían el modelo a texto libre. La escritura UTF-8 y el selector de destino corresponden a I09.

## Decisión del usuario — I06

`AcceptanceService` comparte coordinador con AuditService y el almacén. Su constructor no acepta; `load()` solo recupera la decisión, y un fallo o una política antigua devuelve un rechazo. La UI invoca `accept()` solo al marcar la casilla y confirmar: lee primero, bloquea limpieza pendiente (`CLEANUP_REQUIRED`), obtiene reloj/ID y devuelve éxito después de guardar. No crea eventos. Repetir la aceptación de una política vigente conserva su contexto; una nueva política exige un contexto nuevo. Un reloj anterior a la decisión previa o un generador que reutiliza el ID conocido se rechaza.

La lectura inicial de la pantalla comienza bloqueada. SQLiteAcceptanceStore confirma la retirada antes de que AuditService intente borrar en otra transacción. La retirada se conserva ante un fallo de DELETE, como prueban los casos Android de almacenamiento y de UI.

## Exportación y privacidad — I09/I10

ExportService prepara un permiso ligado al contexto vigente y revalida mediante AuditService.currentAcceptance antes de abrir el destino. Mantiene la exclusión hasta terminar write/flush/close del adaptador. Una instantánea inválida o una retirada no confirmada nunca producen un archivo satisfactorio. AuditSession añade tickets de un solo uso en memoria, invalidados al borrar/revocar; un resultado tardío no abre el documento. El selector y los streams están en app, no en domain.

La pantalla distingue borrar (conserva aceptación), revocar (retirada antes de limpieza), cancelar y los dos reintentos. Su funcionamiento y la conservación tras detener el proceso se documentan en [I10](evidencias/academico/I10-2026-10-22.md). Manual de uso: [USO_ACADEMICO.md](USO_ACADEMICO.md).
