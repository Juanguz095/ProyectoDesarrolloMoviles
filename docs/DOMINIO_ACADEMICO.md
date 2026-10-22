# Dominio académico — modelos y contratos

Ruta: `academic/domain`, paquete `com.auditlab.domain`. Versión de herramientas conservada: Kotlin JVM 2.2.10 y JDK17; ninguna dependencia Android en este módulo.

## Modelos I02

- `Event`: UUID, timestamp numérico en milisegundos, tipo CALL/MESSAGE, dirección INCOMING/OUTGOING y snapshot `ConsentContext`. Las cuatro combinaciones representan los cuatro futuros botones. `isSimulated` siempre es true; no se puede cambiar mediante constructor/copy.
- `ConsentContext`: UUID de aceptación, versión de política de 1–40 caracteres identificadores y fecha no negativa. Un evento no puede preceder su aceptación.
- `Acceptance`: Pending, Granted(context) o Revoked(fecha opcional, borrado pendiente). Una fecha de revocación desconocida no se inventa ni implica aceptación.
- `AuditResult`: éxito con valor o rechazo con código estable. Los informes de revocación separan retirada guardada, borrado pendiente y borrado realizado con confirmación pendiente.

No hay números de teléfono, textos de mensajes ni datos de terceros en estos modelos. Este contrato local no modifica OpenAPI ni conecta el backend.

## Contratos y responsabilidades

| Contrato | Responsabilidad del adaptador futuro |
| --- | --- |
| `EventRepository.insertIfBelowCapacity` | Contar e insertar atómicamente; rechazar capacidad/ID duplicado sin cambiar datos. |
| `EventRepository.latest` | Acotar el resultado y ordenar timestamp descendente e ID canónico ascendente para empates. |
| `EventRepository.deleteAll` | Borrado atómico de eventos con conteo no negativo. |
| `AcceptanceStore.read/save` | Ausencia = Pending; fallo de lectura lanza StorageException. Retornar de save solo tras confirmar escritura durable; nunca reaceptar sobre limpieza pendiente. |
| `EpochClock`, `EventIdGenerator` | Proporcionar tiempo e ID; sustituibles por valores deterministas en tests. |
| `AuditCoordinator` | Compartir exclusión entre servicios que operen sobre los mismos stores; no es la cola Android de I08. |

`StorageException` transmite un fallo esperado sin texto sensible del almacenamiento. Un bug del adaptador no se oculta como una escritura satisfactoria. La revocación debe guardarse antes de intentar borrar eventos: no se revierte si falla DELETE.

Los dobles en `src/test` solo almacenan datos en memoria. Sus tests verifican modelos y el contrato de esos dobles; **no demuestran SQLite ni aceptación persistida**, pendientes de I05/I06.

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
