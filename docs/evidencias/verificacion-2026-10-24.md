# Verificación de funcionamiento — 24 de octubre de 2026

Relación: comprobaciones adicionales sobre P3.2–P3.6 y P4.1–P4.3. No cierra ninguna fase nueva ni sustituye la ejecución en un dispositivo. Responde a la petición de demostrar que el sistema funciona, no solo que compila.

## Alcance ejecutado

Se ejecutaron cinco comprobaciones independientes sobre el artefacto y el servicio reales:

| # | Comprobación | Herramienta | Resultado |
| --- | --- | --- | --- |
| 1 | Auditoría de los APK construidos | `scripts/check_apk.py` | Aprobada en ambos APK |
| 2 | Concurrencia e idempotencia | `ConcurrencyIntegrationTest` | 6 pruebas, 0 fallos |
| 3 | Contrato sobre respuestas reales | `scripts/check_live_contract.py` | 23 respuestas, 0 fallos |
| 4 | HTTPS real de extremo a extremo | `scripts/check_tls.sh` | 6 etapas aprobadas |
| 5 | Pantallas Compose en JVM | Robolectric + Compose UI test | 7 pruebas, 0 fallos |

Entorno: Linux x86_64, JDK 21, Gradle 8.11.1, Android SDK 35 (build-tools 35.0.0), PostgreSQL 18.6 local en una base de prueba aislada, Python 3.14 con `jsonschema` 4.26, OpenSSL 3.6.4. **Sin `/dev/kvm`, sin imagen de sistema Android y sin dispositivo físico.**

## 1. Auditoría de los APK

```
python3 scripts/check_apk.py
```

Comprueba el artefacto, no el código fuente: manifiesto compilado, política de red compilada, firma y clases presentes en el dex.

- `com.auditlab.agent` y `com.auditlab.control`, `minSdk 26`, `targetSdk 35`, 9,3 MB cada uno.
- Permisos declarados: solo `android.permission.INTERNET` y el permiso propio `*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` que añade AndroidX.
- Sin permisos de SMS, llamadas, contactos, ubicación, cámara, micrófono, accesibilidad, uso de paquetes ni superposición.
- `usesCleartextTraffic=false`, `allowBackup=false`, política de red con HTTP permitido solo para `10.0.2.2`, `127.0.0.1` y `localhost`.
- Componentes exportados: la actividad de lanzamiento y `androidx.profileinstaller.ProfileInstallReceiver`, este último protegido por `android.permission.DUMP`.
- Firma verificada con esquema v2; las cinco clases propias esperadas de cada app están en el dex.

**Qué no prueba:** que la app se ejecute correctamente ni que el usuario final no pueda instalar otra build. La auditoría es estática.

**Hallazgo durante la sesión:** al añadir `androidx.compose.ui:ui-test-manifest` para las pruebas, el APK debug pasó a incluir `androidx.activity.ComponentActivity` **exportada**. Se sustituyó por una actividad anfitriona propia (`TestHostActivity`) declarada solo en la variante debug y con `exported="false"`, y se eliminó esa dependencia.

## 2. Concurrencia e idempotencia

```
./gradlew :backend:ktor:integrationTest --tests "com.auditlab.api.ConcurrencyIntegrationTest"
```

Contra una base PostgreSQL de prueba recién creada y la API reales, sin serializar las peticiones:

| Caso | Resultado observado |
| --- | --- |
| 16 entregas paralelas del mismo `eventId` | 16×202; `accepted=1`, `duplicates=15`; 1 fila persistida |
| 20 eventos distintos en paralelo | 20×202 aceptados; 20 filas |
| 16 inscripciones simultáneas con un código | exactamente 1×201; 15×401 `ENROLLMENT_CODE_INVALID`; 1 dispositivo |
| 12 códigos de inscripción simultáneos | 12 valores distintos |
| Revocación y lotes posteriores | 204 y luego 401 `DEVICE_REVOKED`; 0 eventos guardados |
| 8 entregas compitiendo con la revocación | todas 202 o 401; ningún evento con `received_at` posterior a `revoked_at` |

**Qué no prueba:** latencia bajo carga sostenida ni concurrencia entre varios nodos del servicio.

**Hallazgos:** dos fallos iniciales fueron del propio test, no del producto. (a) Usé `metadata={"sequence": n}`, que no está en la lista permitida, y el servidor respondió 400 correctamente; se cambió a `batteryPercent`. (b) El resumen de fallo llamaba a una función `suspend` dentro de `joinToString`, lo que impedía compilar; se corrigió. Ninguna garantía del servidor tuvo que relajarse.

## 3. Contrato sobre respuestas reales

```
AUDITLAB_LIVE_URL=https://localhost:18443 ... python3 scripts/check_live_contract.py
```

Recorre login, inscripción, ingesta (alta, duplicado, conflicto), consultas, CSV, auditoría, errores, revocación, refresh y logout. Valida **cada respuesta real** contra `contracts/openapi.yaml` con `jsonschema` + `referencing`, incluidas las cabeceras declaradas (`X-Correlation-ID` como UUID, `Cache-Control: no-store`, `WWW-Authenticate`, `Retry-After`).

Resultado: **23 respuestas validadas, 0 fallos**, sobre HTTPS.

**Hallazgo real en el validador:** las respuestas de error del contrato son `$ref` a `components/responses/*`; el script construía el puntero del esquema sobre `paths/.../responses/<estado>`, que no existe. Se corrigió para seguir la cadena de referencias y validar el nodo resuelto. También se añadió la validación de `application/problem+json`, que antes se ignoraba, y se cambió el conflicto de `eventId` para usar un `appEventCode` válido (antes provocaba 400 por enum, no 409).

**Qué no prueba:** semántica no expresable en OpenAPI (por ejemplo, que un cursor caduque a los 10 minutos) ni el comportamiento del cliente Android.

## 4. HTTPS real de extremo a extremo

```
bash scripts/check_tls.sh
```

Ktor sirve TLS de forma nativa cuando se definen `AUDITLAB_TLS_CERTIFICATE` y `AUDITLAB_TLS_PRIVATE_KEY`; en ese modo **no se abre el puerto HTTP**.

| Etapa | Resultado |
| --- | --- |
| 1. `/health` y `/ready` por HTTPS con CA propia | 200; verificación TLS correcta |
| 2. HTTP en claro contra el puerto TLS | rechazado |
| 3. HTTPS sin la CA | rechazado |
| 4. Contrato en vivo por HTTPS | 23 respuestas, 0 fallos |
| 5. Cliente JVM de `apps/common` con truststore propio | `smokeTest` correcto |
| 6. Secretos en el registro del servidor | ninguno |

El material TLS se carga en un `KeyStore` PKCS#12 en memoria con contraseña aleatoria por proceso; no se escribe en disco ni se registra. La clave privada debe ser PKCS#8 sin cifrar y se rechaza cualquier otra forma al arrancar.

**Qué no prueba:** que un Android real confíe en la CA (requiere un certificado emitido por una CA del sistema o una configuración de confianza en el dispositivo), ni el despliegue tras un proxy.

## 5. Pantallas Compose en JVM (Robolectric)

```
python3 scripts/gradle.py :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest --no-daemon
```

| Suite | Pruebas | Resultado |
| --- | --- | --- |
| `AgentScreenTest` | 4 | 0 fallos, 0 errores, 0 omitidas |
| `ControlScreenTest` | 3 | 0 fallos, 0 errores, 0 omitidas |

Cubren: registro deshabilitado sin consentimiento y habilitado tras aceptarlo; inscripción y envío de un evento simulado desde la UI; revocación remota (`DEVICE_REVOKED`) que detiene el monitoreo y borra la credencial; revocación local que avisa y borra; listado y revocación de dispositivos por el admin; ausencia de acciones de administración para el rol auditor; consulta de eventos del dispositivo.

**Hallazgo real:** con la pantalla por defecto de Robolectric (320×470 dp) los botones quedaban fuera del área táctil y las pulsaciones no llegaban; las cuatro pruebas del agente fallaban. Se corrigió con una pantalla realista (`w411dp-h891dp`) y `performScrollTo()`. Fue un problema de la prueba, pero también evidencia que la UI depende del desplazamiento: en pantallas pequeñas hay que desplazarse para llegar a los controles.

**Qué no prueba:** red real, Android Keystore, ciclo de vida completo, rotación, permisos del sistema ni rendimiento. No es un dispositivo ni un emulador.

## Suite del backend desde cero

```
./gradlew :backend:ktor:test :backend:ktor:integrationTest --no-daemon --rerun-tasks
```

Sobre una base PostgreSQL vacía creada para la comprobación:

| Suite | Clases | Pruebas | Fallos | Errores | Omitidas |
| --- | --- | --- | --- | --- | --- |
| Unitarias | 4 | 8 | 0 | 0 | 0 |
| Integración | 6 | 53 | 0 | 0 | 0 |

Desglose de integración: autenticación 16, concurrencia 6, base de datos 15, dispositivos 8, eventos 5, retención 3.

## Otras comprobaciones

| Comprobación | Comando | Resultado |
| --- | --- | --- |
| Lógica y cliente compartidos | `:apps:common:test` | 23 pruebas, 0 fallos |
| Lint Android | `:apps:agent:lintDebug :apps:control:lintDebug` | 0 errores; 7 avisos por app (versiones más recientes disponibles y `UseKtx`) |
| Contrato OpenAPI | `scripts/check_contract.py` y pruebas de contrato | 0.2 y 0.1 válidos; 49 pruebas, 0 fallos |
| Prototipo Python | `unittest discover -s backend/api/tests` | 16 pruebas, 0 fallos |
| Documentación | `scripts/check_docs.py` | 48 documentos, 34 tareas y 15 criterios trazados |

## Límites de esta verificación

- **No se ejecutó el APK en un emulador ni en un dispositivo**: no hay `/dev/kvm`, imagen de sistema ni teléfono conectado. P4.1–P4.3 siguen «implementadas, sin validar en dispositivo».
- **No se comprobó CI alojada**: requiere `git push`, que no se autorizó en esta sesión. P0.3 sigue bloqueada.
- No hay entorno de piloto, certificado de CA pública ni despliegue. El prototipo Python sigue restringido a loopback.
- Las comprobaciones usan una base PostgreSQL local de prueba; no se borraron datos existentes fuera de esa base.
- No se registraron contraseñas, tokens, códigos de inscripción ni claves en este documento ni en los registros.

## Reproducción

```bash
python3 scripts/check_apk.py
python3 scripts/gradle.py :apps:agent:testDebugUnitTest :apps:control:testDebugUnitTest --no-daemon
python3 scripts/gradle.py :backend:ktor:installDist --no-daemon
bash scripts/check_tls.sh          # requiere variables de BD/clave y AUDITLAB_BOOTSTRAP_PASSWORD
python3 scripts/check_docs.py
python3 scripts/check_contract.py
```
