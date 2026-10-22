# Verificación solicitada — I01 a I03

Fecha: 22 de octubre de 2026, America/Lima. Ejecución nueva solicitada por el usuario: «Has las pruebas». Código probado: `c67e4c9719180b84c41eba5f3ca163289542b762`. No se añadieron funciones, dependencias ni cambios de versiones.

## Ejecución real

Desde la raíz del repositorio:

```bash
python3 .tooling/academic-setup/run-gradle.py :domain:test :domain:build :app:assembleDebug :app:lintDebug --rerun-tasks --warning-mode all --no-daemon
```

**Salida 0**, `BUILD SUCCESSFUL in 25s`; 48 tareas Gradle ejecutadas. `--rerun-tasks` fuerza nueva ejecución en vez de presentar resultados previos de caché. Los XML de la suite tienen timestamp UTC del 22 de octubre de 2026, 21:41:01, posterior al inicio de esta comprobación.

| Comprobación | Resultado observado |
| --- | --- |
| ModelTest | 9 pruebas aprobadas. |
| RepositoryContractTest | 6 pruebas aprobadas. |
| AuditServiceTest | 30 pruebas aprobadas. |
| AuditConcurrencyTest | 3 pruebas aprobadas. |
| **Suite JVM total** | **48 pruebas, 0 fallos, 0 errores, 0 omitidas.** |
| Compilación del dominio y APK debug | Correcta. |
| Android lint | XML con 0 incidencias. |
| Firma del APK | apksigner verify, salida 0. |
| Manifiesto empaquetado | Sin permisos solicitados, servicios, receptores ni proveedores. |
| Dependencia de pruebas | Sin clases org/junit en el DEX del APK. |

La suite cubre aceptación ausente/revocada, política antigua, fallos de lectura/escritura/reloj/ID, capacidad 1000 y rechazo del intento 1001, historial 100 y orden estable, duplicados, borrado, revocación antes de limpieza y reintentos. Los tests concurrentes ejercitan dos servicios, 1100 intentos de inserción y carreras registro/revocación; las iteraciones no incrementan el conteo JUnit.

APK generado: `academic/app/build/outputs/apk/debug/app-debug.apk`, 862289 bytes. SHA-256: `fdc7e70a81af216cc973d63bbd9a4028a65cfab4c421ce82cb04a2cc025c1bad`.

Informes originales: `academic/domain/build/test-results/test`, `academic/domain/build/reports/tests/test` y `academic/app/build/reports/lint-results-debug.xml`. Copias de XML, manifiesto, firma y resumen de esta ejecución en `.tooling/academic-setup/verification-i03/`; log del comando en `.tooling/academic-setup/verification-i03.log`. Estos archivos generados permanecen ignorados por Git.

## Prueba Android bloqueada

Se comprobó de nuevo la disponibilidad: no existen `/dev/bus/usb`, `/dev/kvm`, ejecutable de emulador ni imágenes Android en el SDK de este entorno. El intento de `adb devices -l`, con rutas Android dirigidas al workspace, terminó con **señal 6 (returncode -6)**:

```text
Cannot mkdir '/home/agent/.android': Read-only file system
```

Registro: `.tooling/academic-setup/verification-device-probe.log`. No se alteró HOME ni se intentó escribir en esa ruta protegida. No se realizó instalación, apertura, interacción Android o Sync en Android Studio; esas comprobaciones siguen pendientes. No hay casos instrumentados Android en el proyecto académico actual.

Las 48 pruebas ejecutan el dominio con dobles en memoria; no prueban SQLite, aceptación tras reinicio ni interfaz conectada, todavía no implementadas. La Activity solo muestra el título base de I01. Para completar la prueba de apertura se necesita Android Studio con emulador operativo o un dispositivo propio conectado por ADB; debe abrirse el build `academic/` y registrarse instalación/apertura reales.

## Conclusión

No se detectaron fallos en el alcance JVM y de compilación comprobado. No se acredita un MVP completo ni aceptación en Android. I04 y funciones posteriores no se iniciaron. La configuración de nube existente ya contiene el comando y las herramientas necesarias; esta sesión no requirió modificar ni publicar su borrador.
