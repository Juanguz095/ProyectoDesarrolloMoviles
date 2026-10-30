# Centro de control — capturas correctas para Figma

Este paquete reemplaza el borrador ilustrativo de 41 vistas. Sus PNG son capturas
de las pantallas Compose reales, ejecutadas en un emulador Android API26 con datos
ficticios. No se han redibujado títulos, botones ni diálogos. Las barras del sistema,
densidad y tipografía corresponden al emulador, y pueden variar en tu teléfono.

## Abrir en Figma

1. Descomprime el ZIP y abre un archivo nuevo de Figma Design.
2. Selecciona todos los PNG de `png/` y arrástralos al lienzo: es la opción más simple.
3. Si deseas frames nombrados, usa Plugins → Development → Import plugin from
   manifest y selecciona `figma-importador/manifest.json`. Ejecuta el plugin.
4. Si Figma exige un ID, crea un plugin de desarrollo sin interfaz (Run once),
   conserva el `id` generado y copia nuestro `code.js` y demás campos del manifest.

No se ha creado un archivo en tu cuenta porque no hay conexión a Figma disponible.
El importador no requiere red ni claves. No se ha ejecutado dentro de Figma.

## Alcance real

- Son imágenes de referencia exactas del render capturado, no texto/capas nativas
  editables. No se promete una reconstrucción editable píxel por píxel.
- `capturas.json` enumera los estados realmente obtenidos y el SHA256 de cada PNG.
- Los nombres, eventos y código de inscripción son datos ficticios sin validez.
- Cada imagen representa un viewport Android, no toda la pantalla desplazable.
- Las ventanas protegidas por FLAG_SECURE no se capturan ni se desactiva su
  protección. No se rellenan con pantallas inventadas. Tampoco se incluyen
  contenido de pantalla en vivo ni todas las combinaciones de errores/permisos.
- El borrador anterior no sirve para afirmar cómo se ve exactamente el APK.

La prueba fuente está en `NativeControlScreenshots.kt`. Se ejecuta exclusivamente
en un AVD de QA sin sesión del usuario y sin llamadas a una API externa.
