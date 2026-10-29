# Validación funcional — 28 de octubre de 2026

Resultados registrados en [JSON](VALIDACION-FUNCIONAL-2026-10-28.json). Pruebas sobre
datos ficticios, PostgreSQL privado y dos AVD: Agente API28 y Centro API26.

| Comprobación | Resultado final |
| --- | --- |
| Común / Agente / Centro JVM y Robolectric | 77 + 30 + 12 aprobadas |
| Backend unitario / PostgreSQL | 11 + 55 aprobadas; se conservan resultados del backend sin cambios posteriores |
| Herramientas | 32 aprobadas |
| Contrato HTTP en vivo | 23 respuestas verificadas |
| Contratos y documentación | 3 OpenAPI; 38 tareas y 15 criterios trazados |
| Recorrido nativo principal | 17/17 etapas, ninguna omitida |
| Pantalla: captura nativa | Cancelar no transmite; permiso Android; JPEG real decodificado; notificación y DETENER; imagen retirada |
| Pantalla: visor nativo | JPEG sintético transmitido por API, mostrado y retirado en la actividad de producción del Centro |
| Pantalla simultánea final | MediaProjection del Agente API28 → API → visor API26; contenido real de Ajustes visible, 12 segundos de pantalla estática y retirada de la imagen tras DETENER aprobados |
| Telefonía y dispositivo | SMS entrante/saliente, llamada perdida/saliente y estado; API, CSV, deduplicación y retirada aprobados |
| Capacidad | 10 000 eventos, 100 páginas, 5 lectores y CSV de 10 000 filas; p95 34,5 ms frente a objetivo 2 000 ms |
| Restauración | 10 tablas y 40 074 eventos; fingerprints iguales, origen preservado y consulta API sobre la copia aprobada |
| Compilación, lint y APK | APKs debug e instrumentación compilados; lint sin errores, con advertencias; permisos, TLS/red, firma y Dex aprobados |

El recorrido principal cubre consentimiento, inscripción, sesión, consulta entre apps,
creación de código, persistencia, desconexión, reintento de WorkManager, indicador visible,
revocación remota/local, retirada offline y reinicio, CSV, rotación, rol auditor, fuente
al 150 % y validación/rechazo de certificados HTTPS.

## Correcciones y repeticiones

- Se actualizaron selectores de pruebas nativas para los botones y filas del rediseño.
- El verificador documental no incluía las cuatro tareas nuevas; el de contratos omitía
  la extensión de pantalla. Ambos se corrigieron y volvieron a aprobar.
- La primera preparación nativa no validó Wi-Fi. Se recuperó la red del AVD mediante
  sus ajustes y se repitió con un servidor local HTTP 204; no se omitieron restricciones
  de WorkManager ni se habilitó acceso externo.
- La prueba de captura encontró una carrera al cancelar y volver a abrir el diálogo.
  Se sincronizó la prueba con el cierre real y se corrigió la restauración del diálogo
  al recrear la actividad. Se añadió una regresión Robolectric de rotación.
- Se protegió la bandera de envío entre hilos y se evitó iniciar otra captura cuando
  ya existe una sesión activa. La captura final y la prueba de metadatos usan el APK
  final cuyo hash está en el JSON. El recorrido principal conserva sus hashes iniciales:
  sus pruebas se hicieron antes de esos últimos ajustes específicos de pantalla.
- Tras reconectar el entorno, las primeras 16 etapas estaban aprobadas. Se retomó
  la etapa HTTPS que no se había ejecutado y el protocolo ordenado terminó 17/17.
- La prueba simultánea encontró un defecto que los ensayos iniciales no detectaban:
  ImageReader no produce cuadros nuevos si no cambia la pantalla. El envío ligado
  a sus callbacks dejaba vencer el relay y podía conservar un cuadro negro de transición.
  Se cambió a muestreo periódico y reenvío del último JPEG en RAM mientras la sesión
  esté autorizada. La regresión exige contenido claro de Ajustes y 12 lecturas continuas
  durante una pantalla estática; después otro AVD muestra ese flujo y comprueba DETENER.
- La automatización por volcados UI falló al esperar ventanas y desplazar bajo el aviso
  fijo. Se preservó ese intento y se sustituyó por coordinación de instrumentación
  entre ambos AVD, sin esperar inactividad mientras se actualiza el video.
- La revisión visual corrigió el título ACTIVIDAD RECIENTE en pantallas estrechas:
  el enlace pasa debajo cuando falta ancho. Compilación, 30 pruebas del Agente y lint
  volvieron a aprobar tras estos cambios. La prueba simultánea utiliza el APK final.

## Límites

Además de las comprobaciones separadas, se ejecutó una sesión simultánea mostrando
en el Centro el mismo flujo del Agente. Ambos emuladores permanecieron activos durante
la transmisión; la preparación pausó temporalmente el AVD que aún no participaba.
La pantalla usa cuadros JPEG de 480 px aproximadamente a 1 fps, sin audio ni grabación permanente.

No está implementada la extracción independiente de notificaciones/historial de
WhatsApp. Los chats abiertos pueden aparecer durante una sesión autorizada de pantalla;
no se instaló ni se probó WhatsApp real en estos AVD.

No se probaron teléfonos físicos, Android API34 o posterior, Windows/Tailscale ni un
servidor público HTTPS. Los SMS y llamadas usan el módem y apps del emulador. Los
resultados no garantizan compatibilidad con todos los fabricantes ni ausencia absoluta
de errores. P7.1 reciente/físico y P8 real siguen pendientes; P7.5 simultáneo queda verificado en QA.

Evidencia privada conservada en `.tooling/android-native/ccb21fa2cc6b4406`,
`.tooling/screen-share-validation`, `.tooling/real-events-validation/all-functions-20261028`
y `.tooling/full-delivery`. La pareja simultánea y sus capturas están en
`.tooling/live-screen-pair/final-native`; los intentos y el defecto previos se conservan
en su directorio padre. Se preservaron fuentes, claves, bases y fallos anteriores.
