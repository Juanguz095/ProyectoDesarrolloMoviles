# AuditLab — Propuesta limitada a I01

**Fecha:** 22 de octubre de 2026, America/Lima.
**Fuente vigente:** [contexto entregado por el usuario](referencias/contexto-auditlab-2026-10-22.txt).
**Estado actualizado:** ejecución I01 autorizada e implementada; Build/lint CLI verificados. Sync de IDE y apertura Android pendientes. [Evidencia](evidencias/academico/I01-2026-10-22.md). La propuesta e inventario de abajo se conservan como referencia de entrada; no autorizan I02.

## 1. Objetivo y resultado esperado

Preparar una base Android vacía con módulos `app` y `domain`, las versiones exigidas y una Activity Kotlin mínima que compile. Mantener separados el código Android y el futuro dominio Kotlin puro. Entregar configuración, diff y comprobaciones reales para su revisión antes de pasar a I02.

No incluir modelos de eventos, AuditService, SQLite, aceptación, botones de simulación, CSV ni funcionalidades de incrementos posteriores en este cambio.

## 2. Inspección realizada

Revisión local de partida: **`118a738`**, rama `main`, sincronizada con la referencia local `origin/main` en la inspección. No se consultó GitHub nuevamente ni se revalidó su CI.

| Elemento | Observado el 22 de octubre | Implicación para I01 |
| --- | --- | --- |
| Proyecto académico | No existe `academic/settings.gradle.kts`. | Crear una base propia después de revisar la propuesta. |
| Repositorio anterior | Tiene `apps/agent`, `apps/control` y `backend/ktor`. | Conservarlos; quedan fuera de la fase 1 académica. |
| AGP / Kotlin anteriores | 8.9.2 / 2.1.20 declarados en el build raíz. | No cumplen las versiones nuevas; no modificar ese build para forzar reutilización. |
| Gradle preparado | 8.11.1; no se encontró distribución 9.3.1 en las rutas/cachés locales inspeccionadas. | Preparar wrapper 9.3.1, con checksum oficial, para el build académico. |
| JDK preparado | JDK completo 21.0.12.1 en `.tooling/jdk`; rutas del sistema inspeccionadas contienen JDK 21. | No sustituye el requisito JDK 17. Localizar o instalar JDK 17 en una ubicación permitida y usarlo solo en esta ruta. |
| SDK preparado | Plataforma 35 presente; `platforms/android-36/android.jar` ausente en el SDK local inspeccionado. | Instalar plataforma 36 y las herramientas compatibles necesarias cuando se implemente I01. |
| Herramientas en PATH | `java` presente; `javac` y `adb` no encontrados en PATH. | Esto no demuestra ausencia total: el JDK preparado tiene sus propios binarios. Comprobar rutas explícitas de JDK 17/SDK antes de compilar. |
| IDE / dispositivo | No se comprobó Android Studio ni un dispositivo o emulador disponible. | No afirmar Sync de IDE, instalación o ejecución en Android sin evidencia. |
| PDF del curso | Se recibieron beta y contexto textual; el PDF/prompt específico I01 no está adjunto. | Confirmar su lista exacta de archivos si impone restricciones adicionales. |

La inspección ejecutó lectura de archivos, Git y `java -version`; no instaló paquetes, no compiló el proyecto ni ejecutó pruebas de aplicación.

## 3. Configuración obligatoria

| Componente | Valor que debe usarse |
| --- | --- |
| Lenguaje | Kotlin |
| AGP | 9.1.1 |
| Gradle Wrapper | 9.3.1 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| app | Kotlin integrado en AGP; sin añadir `org.jetbrains.kotlin.android`. |
| domain | `kotlin.jvm` 2.2.10; sin dependencia de Android. |

La compatibilidad de Kotlin JVM 2.2.10 y Gradle 9.3.1 se considera **pendiente de comprobar**, no un fallo demostrado. Revisar documentación oficial y la resolución real de plugins. Si falla, distinguir incompatibilidad de errores de red, SDK o JDK. No bajar ni subir versiones automáticamente: presentar el error, alternativa y diff propuesto y solicitar aprobación antes del cambio, tal como exige el contexto del usuario.

## 4. Ubicación y archivos propuestos

Propuesta: build independiente dentro de `academic/`, abierto directamente en Android Studio. Esto evita alterar el backend y las apps existentes. Su ubicación y la lista siguiente son propuestas para revisión, no una lista atribuida al PDF.

| Archivo propuesto | Propósito en I01 |
| --- | --- |
| `academic/settings.gradle.kts` | Repositorios de plugins/dependencias e inclusión exclusiva de `:app` y `:domain`. |
| `academic/build.gradle.kts` | Declarar AGP 9.1.1 y Kotlin JVM 2.2.10 sin aplicar plugins a módulos incorrectos. |
| `academic/gradle.properties` | Configuración mínima de construcción; sin secretos ni rutas personales. |
| `academic/gradle/wrapper/gradle-wrapper.properties` | Gradle 9.3.1 y SHA-256 oficial de su distribución. |
| `academic/gradle/wrapper/gradle-wrapper.jar` | Bootstrap oficial del wrapper. |
| `academic/gradlew`, `academic/gradlew.bat` | Ejecutar la versión fijada en Linux y Windows. |
| `academic/app/build.gradle.kts` | App Android con Kotlin integrado, SDKs y JDK 17; dependencia del módulo domain. |
| `academic/app/src/main/AndroidManifest.xml` | Activity launcher mínima; sin INTERNET, servicios, receptores ni permisos de comunicaciones. |
| `academic/app/src/main/java/com/auditlab/academic/MainActivity.kt` | Activity vacía que muestre una identificación básica. Usar APIs de Views del framework sin anticipar la pantalla de I07. |
| `academic/domain/build.gradle.kts` | Módulo Kotlin JVM 2.2.10, objetivo JDK 17; todavía sin modelos o reglas. |
| `docs/evidencias/academico/I01-AAAA-MM-DD.md` | Registro real del incremento cuando se ejecute. |
| `docs/AVANCE_ACADEMICO.md` | Estado técnico, aprobación y siguiente paso. |

Aprovechar las exclusiones Git existentes de builds, `.gradle`, `.tooling` y `local.properties`. Modificar `.gitignore` solo si aparece una necesidad concreta y se declara en el diff. `local.properties` con ruta SDK, si se necesita, será configuración local ignorada. La instalación de herramientas y caches se mantiene fuera del código de app.

## 5. Secuencia propuesta para ejecutar I01

1. Contrastar el prompt I01 del PDF cuando esté disponible; confirmar archivos y ubicación antes de implementar.
2. Localizar/preparar JDK 17 y SDK 36 en rutas permitidas, sin reemplazar la instalación previa. Conservar proxy/TLS y comprobar fuentes y checksums oficiales.
3. Crear el wrapper 9.3.1 y el build mínimo `app`/`domain` con las versiones exigidas.
4. Crear únicamente la Activity y el manifiesto mínimos, con un applicationId propio.
5. Verificar configuración Gradle y compilación; diagnosticar y corregir fallos dentro de la lista autorizada. Pedir aprobación para cambios de versiones/dependencias o de alcance.
6. Abrir `academic/` en Android Studio y registrar Sync real. La CLI en nube no sustituye esa evidencia del IDE.
7. Si hay Android propio/emulador, instalar y abrir la app vacía. Si no, declarar esa comprobación pendiente.
8. Mostrar diff, revisar archivos/versiones, registrar comandos/resultados y cerrar la propuesta de aprobación. **Detenerse antes de I02.**

## 6. Comprobaciones previstas

Los comandos siguientes **no se han ejecutado para academic**, porque aún no existe. Se ejecutarán desde `academic/` con JDK 17 y SDK 36 correctamente seleccionados:

```bash
./gradlew --version
./gradlew help
./gradlew :domain:build :app:assembleDebug
```

En Windows usar `gradlew.bat`; en Omarchy usar `./gradlew`. Registrar códigos de salida, la JVM efectiva y las versiones resueltas. Android Studio debe tener el mismo JDK de Gradle.

- Si `domain:test` no tiene casos todavía, registrar **0 pruebas / NO-SOURCE**; no introducir lógica de I02 solo para producir una cifra ni declarar tests aprobados.
- Guardar APK debug identificada y su hash para la comprobación local, sin subir el binario a Git.
- La instalación/apertura se comprueba en un dispositivo identificado; no inferirla de que exista un APK.
- No ejecutar ni publicar el backend, las apps anteriores o GitHub Actions para demostrar esta base académica.

## 7. Criterios de salida y aprobación

I01 queda técnicamente comprobado cuando las versiones efectivas coinciden, Gradle configura y compila la app/domain y el diff contiene solo lo previsto. Registrar por separado Sync en Android Studio y apertura en Android; cualquier elemento no observado queda pendiente de revisión, no aprobado por inferencia.

La evidencia debe incluir archivos modificados y motivo, diff, comandos reales, salidas, fallos/correcciones y pendientes. Conservar el avance revisado mediante commit local al ejecutar el incremento; no empujar a GitHub ni distribuir artefactos por esta propuesta.

**Aprobación para el siguiente incremento:** pendiente. La regla de detenerse procede de las secciones 6 y 8 del contexto del usuario: no avanzar automáticamente a I02 aunque Build sea satisfactorio.

## 8. Uso de la base implementada

En Omarchy/Android Studio:

1. Abrir la carpeta `academic/` de este repositorio como proyecto.
2. Elegir JDK17 como Gradle JDK e instalar SDK36 desde SDK Manager.
3. Ejecutar Sync; conservar el resultado real y cualquier error.
4. Desde `academic/`, ejecutar `./gradlew help :domain:build :app:assembleDebug :app:lintDebug`.
5. Seleccionar un Android propio o emulador compatible (API26 o superior) y ejecutar app. La pantalla esperada solo muestra «AuditLab · Base académica».
6. Registrar dispositivo/API y captura; revisar I01 antes de autorizar I02.

En esta nube: desde la raíz usar `python3 .tooling/academic-setup/run-gradle.py` con las mismas tareas; el helper ignorado selecciona JDK17/SDK36, caches escribibles, proxy y CA. `scripts/gradle.py` pertenece al build histórico y no selecciona esta ruta. Los helpers se reproducen mediante el instalador guardado en el borrador del entorno.

La ejecución añadió tres archivos de recursos de la Activity (strings, icono y reglas de extracción) para corregir las advertencias de lint, sin incorporar funcionalidades de incrementos posteriores. Los resultados y límites reales están en la evidencia I01.
