> **BORRADOR SUSTITUIDO.** Estas 41 vistas fueron reconstruidas y no coinciden
> exactamente con el APK. Usa las [capturas Android comprobadas](../centro-capturas-0.7.4/LEEME.md).
> El enlace ZIP original ahora entrega el paquete de capturas corregido.

# AuditLab — vistas del Centro para Figma

Referencia de diseño de la app 0.7.4 · 30 de octubre de 2026, America/Lima.
41 vistas/variantes con datos ficticios. No son capturas exactas del APK ni un
archivo .fig exportado desde Figma. No se ha creado un documento en tu cuenta.

## Crear todas las vistas editables dentro de Figma

1. Descomprime el ZIP en tu PC.
2. Abre **Figma Design de escritorio** y un archivo nuevo.
3. En el menú busca **Plugins → Development → Import plugin from manifest…**
   (en español puede aparecer como Complementos → Desarrollo).
4. Selecciona `figma-importador/manifest.json` dentro de la carpeta descomprimida.
5. Ejecuta **AuditLab · Centro 0.7.4** desde Plugins → Development.
6. Se crea una página nueva con todos los frames, textos editables, paneles,
   colores y enlaces de navegación. Guarda el archivo con el nombre que prefieras.

Si Figma exige un ID de plugin para importar el manifest: crea uno con
Plugins → Development → New plugin → Figma Design → Run once. Conserva el `id`
generado por Figma, copia nuestro `code.js` en su carpeta y combina los campos de
nuestro `manifest.json` con ese `id`. Después ejecútalo desde Development.

El importador no necesita claves, conexión de red ni acceso a tu cuenta fuera del
archivo abierto. No borra páginas existentes. Ejecutarlo otra vez crea otra página.
Requiere permiso para ejecutar plugins de desarrollo en tu organización de Figma.

## Alternativa rápida con SVG

Arrastra los archivos de `svg/` al lienzo de Figma. Conservan vectores; el importador
es la opción recomendada para obtener texto nativo y los enlaces de navegación.
`vista-general.png` permite revisar todas las vistas antes de importar.

## Cobertura y alcance

Incluye acceso, Inicio, equipos/archivo, detalle/identificación, revocación,
eventos/filtros, SMS, llamadas, WhatsApp, alertas, pantalla, auditoría, inscripción,
ajustes por rol, diálogos, carga y fallos. `vistas.json` enumera el archivo fuente
que sirve como referencia para cada vista.

- Las vistas largas muestran el contenido completo: representan pantallas con
  scroll. La navegación está al final de la lámina, no simula un viewport Android.
- Las superficies del sistema (CSV/notificaciones) y el calendario son referencias:
  Android/Material controla su aspecto real. No son pantallas propias nuevas.
- Los diálogos se representan en frames independientes para facilitar su edición.
- Los controles de selección y formularios son representaciones estáticas. Los
  botones enlazados permiten recorrer ejemplos; no ejecutan API ni permisos reales.
- La vista de equipos incluye accesos ilustrativos al filtro Mostrar; en el APK
  ese cambio se realiza en el menú desplegable. No modifica la aplicación.
- El cierre de sesión y las confirmaciones reutilizan el estilo del Centro; sus
  frames son referencias de diseño, no una reproducción píxel por píxel de Material.
- WhatsApp muestra únicamente vistas previas recibidas y autorizadas. No se diseña
  captura oculta ni mensajes enviados dentro del chat. Alertas con servicio visible,
  archivo/nombres locales y roles conservan las limitaciones del producto actual.

## Validación

SVG/XML y JSON comprobados; enlaces internos de diseño comprobados y vista general
renderizada. El importador no se ha ejecutado dentro de Figma: esta sesión no tiene
conector Figma disponible. La sintaxis JavaScript se comprueba si Node está instalado.
No se ejecutan pruebas de Android porque este trabajo modifica solo diseño/docs.

## Inventario

- 01 · Inicio de sesión — `MainActivity.kt`
- 02 · Error de acceso — `MainActivity.kt`
- 03 · Inicio con datos — `ControlViews.kt`
- 04 · Inicio sin equipos — `ControlViews.kt`
- 05 · Lectura antigua y red — `ControlViews.kt`
- 06 · Equipos visibles — `ControlViews.kt`
- 07 · Equipos archivados — `ControlViews.kt`
- 08 · Detalle activo — `ControlViews.kt`
- 09 · Detalle archivado — `ControlViews.kt`
- 10 · Detalle revocado — `ControlViews.kt`
- 11 · Información técnica — `ControlViews.kt`
- 12 · Cambiar nombre — `MainActivity.kt / ControlViews.kt`
- 13 · Confirmar archivo — `MainActivity.kt / ControlViews.kt`
- 14 · Confirmar restauración — `MainActivity.kt / ControlViews.kt`
- 15 · Confirmar revocación — `MainActivity.kt / ControlViews.kt`
- 16 · Eventos y mensajes — `ControlViews.kt`
- 17 · Filtros expandidos — `ControlViews.kt`
- 18 · Detalle SMS — `MainActivity.kt`
- 19 · Detalle llamada — `MainActivity.kt`
- 20 · Actividad WhatsApp — `MainActivity.kt`
- 21 · Detalle técnico de evento — `MainActivity.kt`
- 22 · Eventos vacíos — `ControlViews.kt`
- 23 · Exportación CSV — `ControlViews.kt` · superficie del sistema
- 24 · CSV guardado — `ControlViews.kt`
- 25 · WhatsApp autorizado — `WhatsAppPreviewsView.kt`
- 26 · WhatsApp detenido — `WhatsAppPreviewsView.kt`
- 27 · Alertas activas — `WhatsAppAlerts.kt`
- 28 · Notificación Android — `WhatsAppAlerts.kt` · superficie del sistema
- 29 · Pantalla compartida — `ScreenViewerActivity.kt`
- 30 · Pantalla sin sesión — `ScreenViewerActivity.kt`
- 31 · Auditoría — `ControlViews.kt`
- 32 · Auditoría técnica — `ControlViews.kt`
- 33 · Crear inscripción — `ControlViews.kt`
- 34 · Código generado — `ControlViews.kt`
- 35 · Ajustes administrador — `MainActivity.kt`
- 36 · Ajustes auditor — `MainActivity.kt`
- 37 · Confirmar cierre — `MainActivity.kt / ControlViews.kt`
- 38 · Actualización en curso — `ControlViews.kt`
- 39 · Fallo de red — `ControlViews.kt`
- 40 · Selector de dispositivo — `ControlViews.kt`
- 41 · Selector de fecha — `ControlViews.kt`
