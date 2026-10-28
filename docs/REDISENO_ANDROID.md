# Rediseño Android según mockups — 0.3.1-rc1

Fecha: 28 de octubre de 2026 (America/Lima).
Alcance: P5.3, P6.2, P6.4 y preparación de artefactos P8.2/P8.3.

## Referencia visual

Se siguen los cinco mockups del usuario: dos del Centro de control (vista de
estado y eventos) y tres del Agente (consentimiento, monitoreo y revocación).

- Fondo beige `#DAD7C0`, panel claro `#F0EDDB`, texto `#10110C`.
- Tipografía monoespaciada, encabezados en mayúsculas, divisores finos y esquinas rectas.
- Botones negros y botones secundarios con borde; sin gradientes ni tarjetas redondeadas.
- Espaciado lateral de 26 dp, contenido desplazable y ancho máximo de 620 dp.
- Controles de al menos 48 dp, texto escalable, espacio para teclado y barras del sistema.
- Se aumenta el contraste del texto secundario respecto al gris tenue del mockup.
- Avisos y progreso fuera del contenido desplazable para mantener visibles los errores.

La fuente de diseño está en `apps/design/src/main/`: ambos módulos Android la
compilan con sus herramientas y dependencias existentes. No se añade otro módulo
ni una biblioteca. Los selectores nativos heredan la paleta y tipografía.

## Pantallas del Centro de control

1. **Login:** servidor, usuario, contraseña y recuperación de sesión local bloqueada.
2. **Inicio:** dispositivo seleccionado, inscripción, último contacto, batería,
   conexión, eventos recientes e indicadores con fecha de lectura.
3. **Eventos:** dispositivo o UUID manual, categoría, calendario UTC, horas exactas
   opcionales, ID/código exacto, filtros, limpieza, páginas y exportación CSV.
4. **Detalle de evento:** origen real/simulado, fechas, identificadores, datos
   permitidos y contexto de consentimiento.
5. **Dispositivos:** filtro activo/revocado, actualización y paginación.
6. **Detalle de dispositivo:** ID copiable, versión, inscripción, consentimiento,
   historial, auditoría del grupo y revocación confirmada para administradores.
7. **Inscripción:** generación de código de un uso y fecha de caducidad.
8. **Auditoría:** actor, acción, recurso, resultado, filtros y correlación.
9. **Más:** cuenta, rol, servicio, auditoría, inscripción y salida confirmada.

La navegación inferior conserva cuatro destinos. Las opciones administrativas se
ocultan al auditor; la autorización efectiva permanece en la API. El selector y
el guardado de CSV existentes se conservan.

### Datos mostrados con precisión

- No se inventan nombres/modelos remotos ni contadores globales.
- «Inscripción activa» no significa conexión en vivo.
- El panel inicial consulta hasta 50 eventos recientes y, por separado, hasta 50
  eventos de `device_status` del dispositivo. La lectura se elige únicamente entre
  los registros reales de esa consulta, usando su fecha; nunca entre los simulados.
- Si una lectura real no está en esa página, se informa que no hay datos recibidos.
- El estado inicial y sus consultas no reutilizan ni modifican los filtros/cursores
  del historial; no se muestran lecturas de otro dispositivo durante un cambio.
- La antigüedad visible se actualiza cada minuto. No se infiere desconexión por edad.
- Las consultas adicionales del panel usan los endpoints existentes y quedan
  sujetas a la auditoría y a los permisos del servidor.
- Las fechas elegidas con calendario incluyen los días en UTC. Internamente, el
  extremo final se envía como el inicio del día siguiente, exclusivo en la API.
- La exportación usa los filtros aplicados; se avisa si hay cambios sin aplicar.

## Pantallas del Agente

1. **Consentimiento:** aviso destacado, aceptación explícita y política de datos.
2. **Inscripción:** servidor y código; cambiar de servidor exige nuevo consentimiento.
3. **Monitoreo:** indicador, contadores de cola, modelo local, actividad reciente,
   pausa/reanudación y retirada confirmada.
4. **Estado del dispositivo:** batería, conexión y fecha de la última lectura real
   guardada en Room. No se consulta telefonía desde la interfaz.
5. **Captura autorizada:** autorización adicional, permisos opcionales, inicio y
   detención del servicio visible. Se conservan las restricciones anteriores.
6. **Historial local:** últimos 100 registros con estado pendiente/enviado/rechazado,
   origen real/simulado y detalle. No se presenta como todo el historial del servidor.
7. **Dispositivo revocado/retirada local:** captura detenida, nuevos envíos
   bloqueados, retirada pendiente y reinscripción con consentimiento nuevo.
8. **Almacenamiento bloqueado:** motivo y acción de retirada/limpieza.

«Activo» requiere servicio de captura en ejecución, indicador disponible y ausencia
de retirada. Inscrito con indicador habilitado, sin captura en ejecución, se muestra
como «Listo para activar». No se inventa una hora de sincronización: se muestran los
contadores confirmados, porque la cola actual no guarda la hora de acuse de cada lote.

Las nuevas consultas DAO leen las tablas existentes: no cambia el esquema de Room
ni se borran datos. La lectura del estado y la cola comparte el bloqueo del runtime.

## Distribución y compatibilidad

Ambas apps: **0.3.1-rc1, versionCode 4**, Android 8.0/API26 o superior, target35.
El servidor y el contrato 0.3 no cambian. Los nuevos APK se construyen con la misma
configuración de firma debug; son artefactos de desarrollo.

Las descargas públicas 0.3.0-rc1 anteriores no contienen este rediseño. El usuario
autorizó publicar el código 0.3.1-rc1 en `origin/main`; los APK aquí descritos siguen
siendo artefactos debug y no sustituyen una distribución firmada por el responsable.

## Comprobación de esta revisión

La evidencia específica se registra en
[evidencias/REDISENO-0.3.1-2026-10-28.json](evidencias/REDISENO-0.3.1-2026-10-28.json).

Comprobaciones finales de esta revisión:

- **115 JVM**: 75 comunes, 28 del agente y 12 del centro, sin fallos, errores ni
  omitidas. Las pruebas de pantalla se adaptaron a los textos y navegación nuevos.
- **17/17 etapas nativas** en dos emuladores separados: agente Android 9/API28 y
  centro Android 8/API26 contra la API y PostgreSQL reales del fixture local.
- Prueba nativa adicional del agente con cinco eventos reales emulados, consulta
  por API, CSV, deduplicación y detención tras retirar consentimiento.
- 32 pruebas de herramientas, ambos contratos OpenAPI, compilación debug, Android
  Lint y auditoría de los dos APK aprobados. Lint: 0 errores, 9 avisos agente y
  5 centro.
- APK finales: agente `83940f71630c70608213fb0e3e78eaea127baf8fa00f2d426b406bd70d7dd4fa`;
  centro `6179f4955ec6e83f5309d19cee5751dc3170c37fb887f2f84457442e3ef3f901`.

La máquina no tiene KVM. Los AVD se ejecutaron con TCG y el orquestador pausó el
dispositivo inactivo durante instalaciones y etapas aisladas. Los intentos fallidos
por selectores duplicados, selector de archivos, System UI y plazos insuficientes
se conservan en `.tooling/redesign-validation/`; no se cuentan como aprobaciones.

No hubo prueba en teléfonos físicos, Android reciente, servidor HTTPS operativo ni
firma de distribución. Tampoco se realizó una comparación visual píxel a píxel; la
validación de esta sesión es funcional, semántica y de accesibilidad básica.
