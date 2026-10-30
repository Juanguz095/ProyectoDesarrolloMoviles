"""Package actual Android screenshots for Figma. Never redraw or invent app screens."""
from pathlib import Path
import base64,hashlib,json,zipfile
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/diseno/centro-capturas-0.7.4'
screens=sorted((OUT/'png').glob('*.png'))
assert screens, 'Capture the production UI on Android first'
font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',15)
cols=6;rows=(len(screens)+cols-1)//cols
board=Image.new('RGB',(cols*270+30,rows*540+100),'#f4f4ef');draw=ImageDraw.Draw(board)
draw.text((24,20),'AUDITLAB · CAPTURAS DE LA INTERFAZ EN ANDROID',font=font,fill='#10110c')
draw.text((24,48),'Centro 0.7.4 · emulador API26 · datos ficticios · sin redibujar controles',font=font,fill='#66675a')
items=[]
for i,path in enumerate(screens):
    image=Image.open(path).convert('RGB');w,h=image.size
    items.append({'name':path.stem,'width':w,'height':h,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'base64':base64.b64encode(path.read_bytes()).decode()})
    x=20+(i%cols)*270;y=100+(i//cols)*540
    draw.text((x,y),path.stem[:29],font=font,fill='#10110c')
    image.thumbnail((246,490));board.paste(image,(x,y+28))
board.save(OUT/'vista-general.png')
plugin=OUT/'figma-importador';plugin.mkdir(exist_ok=True)
(plugin/'manifest.json').write_text(json.dumps({'name':'AuditLab · Capturas reales del Centro','api':'1.0.0','main':'code.js','editorType':['figma'],'documentAccess':'dynamic-page','networkAccess':{'allowedDomains':['none']}},indent=2)+'\n')
code=r'''const screenshots=DATA;
function bytes(base64){const chars='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',out=[];let acc=0,bits=0;for(const c of base64){if(c==='=')break;const v=chars.indexOf(c);if(v<0)continue;acc=(acc<<6)|v;bits+=6;if(bits>=8){bits-=8;out.push((acc>>bits)&255);acc&=(1<<bits)-1;}}return new Uint8Array(out);}
async function main(){const page=figma.createPage();page.name='AuditLab · Capturas Android · Centro 0.7.4';await figma.setCurrentPageAsync(page);const frames=[];let maxHeight=Math.max(...screenshots.map(s=>s.height));for(let i=0;i<screenshots.length;i++){const s=screenshots[i];const f=figma.createFrame();page.appendChild(f);f.name=s.name;f.resize(s.width,s.height);f.x=(i%6)*(s.width+70);f.y=Math.floor(i/6)*(maxHeight+100);const img=figma.createImage(bytes(s.base64));f.fills=[{type:'IMAGE',imageHash:img.hash,scaleMode:'FIT'}];f.setPluginData('capture_sha256',s.sha256);f.setPluginData('origin','Actual production Compose UI on Android API26 with synthetic API data; raster screenshot, not editable native text.');frames.push(f);}page.selection=frames;figma.viewport.scrollAndZoomIntoView(frames.slice(0,6));figma.closePlugin(frames.length+' capturas Android importadas.');}main().catch(e=>{console.error(e);figma.closePlugin('No se completó la importación.');});
'''.replace('DATA',json.dumps(items))
(plugin/'code.js').write_text(code)
manifest={'product_version':'0.7.4-preview','android_api':26,'capture_method':'UiAutomation.takeScreenshot; production ControlScreen and native dialogs, synthetic in-process API fixture','redrawn':False,'screens':[dict((k,v) for k,v in item.items() if k!='base64') for item in items],'not_captured':['WhatsApp preview windows protected by FLAG_SECURE','Live screen content and live notification permission dialogs','Every scroll position or every possible data/error combination'],'figma_document_created':False,'native_text_editable':False,'figma_import_executed':False}
(OUT/'capturas.json').write_text(json.dumps(manifest,indent=2)+'\n')
(OUT/'LEEME.md').write_text('''# Centro de control — capturas correctas para Figma

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
''')
archive=ROOT/'docs/diseno/AuditLab-Centro-Capturas-0.7.4.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for path in sorted(OUT.rglob('*')):
        if path.is_file():z.write(path,Path('AuditLab-Centro-Capturas')/path.relative_to(OUT))
# Correct the old download URL as well; Git history retains the superseded draft.
(ROOT/'docs/diseno/AuditLab-Centro-Figma-0.7.4.zip').write_bytes(archive.read_bytes())
print(json.dumps({'screenshots':len(screens),'zip':str(archive),'bytes':archive.stat().st_size}))
