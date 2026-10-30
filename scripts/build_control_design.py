"""Export Control 0.7.4 design references as editable Figma nodes and standalone SVGs.

Design representations with synthetic data, not screenshots or an exported .fig.
Requires Pillow only for previews; no network or credentials.
"""
from pathlib import Path
import json
import html
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/diseno/centro-0.7.4'
OUT.mkdir(parents=True, exist_ok=True)
COLORS = dict(paper='#DAD7C0', panel='#F0EDDB', ink='#10110C', muted='#66675A', red='#9A3027', green='#287336')
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
MEASURE = ImageDraw.Draw(Image.new('RGB', (1, 1)))
SCREENS = []


class Screen:
    def __init__(self, key, name, title, subtitle='', tab='INICIO', source='ControlViews.kt', system=False):
        self.key, self.name, self.tab, self.source = key, name, tab, source
        if key.startswith('login') or source=='ScreenViewerActivity.kt': self.tab=''
        self.items, self.y, self.system = [], 32, system
        self.text('AUDITLAB / CENTRO DE CONTROL', 11, color='muted')
        self.text(title, 24, True)
        if subtitle: self.text(subtitle, 12, color='muted')
        self.rule()
        SCREENS.append(self)

    def rect(self, x, y, w, h, color='panel', stroke=True, target=None):
        self.items.append(dict(type='rect', x=x, y=y, w=w, h=h, fill=COLORS[color], stroke=COLORS['muted'] if stroke else None, target=target))

    def text(self, text, size=15, bold=False, color='ink', x=24, width=342):
        font=ImageFont.truetype(BOLD if bold else FONT, size)
        lines=[]
        for paragraph in text.split('\n'):
            line=''
            for word in paragraph.split(' '):
                if line and MEASURE.textlength(line+' '+word,font=font)>width:
                    lines.append(line);line=word
                else: line=(line+' '+word).strip()
            lines.append(line)
        h=len(lines)*(size+7)
        self.items.append(dict(type='text', x=x,y=self.y,w=width,h=h+3,text='\n'.join(lines),size=size,bold=bold,color=COLORS[color]))
        self.y+=h+10

    def rule(self):
        self.items.append(dict(type='line',x=24,y=self.y,w=342,h=1,fill=COLORS['muted']))
        self.y+=22

    def button(self, label, target=None, outlined=False):
        y=self.y
        self.rect(24,y,342,48,'paper' if outlined else 'ink',True,target)
        self.y=y+12;self.text(label,13,True,'ink' if outlined else 'panel',x=38,width=314)
        self.y=y+62

    def field(self,label,value):
        self.text(label.upper(),11,color='muted')
        y=self.y;self.rect(24,y,342,46)
        self.y=y+10;self.text(value,13,x=36,width=318);self.y=y+60

    def card(self,title,body,action=None,target=None):
        index=len(self.items);top=self.y;self.y+=16
        self.text(title,18,True,x=40,width=310)
        self.text(body,14,x=40,width=310)
        if action:self.button(action,target,True)
        self.y+=10
        self.items.insert(index,dict(type='rect',x=24,y=top,w=342,h=self.y-top,fill=COLORS['panel'],stroke=COLORS['muted'],target=None))
        self.y+=20

    def done(self):
        self.y=max(self.y,760)
        self.rule()
        if not self.tab:
            self.height=self.y+34
            return
        for i,(label,target) in enumerate(zip(['INICIO','EVENTOS','EQUIPOS','MÁS'],['inicio','eventos','equipos','ajustes'])):
            x=24+i*86
            self.rect(x,self.y,82,42,'panel' if label==self.tab else 'paper',False,target)
            self.items.append(dict(type='text',x=x+5,y=self.y+12,w=79,h=20,text=label,size=11,bold=label==self.tab,color=COLORS['ink']))
        self.height=self.y+72


def dialog(key,name,title,body,confirm,target,field=None):
    s=Screen(key,name,title,'DIÁLOGO · SOBRE LA VISTA DE ORIGEN',tab='',source='MainActivity.kt / ControlViews.kt')
    s.card(title,body)
    if field:s.field('Nombre del dispositivo',field)
    s.button(confirm,target);s.button('Cancelar','detalle',True)
    return s


s=Screen('login','01 · Inicio de sesión','INICIAR SESIÓN','CONECTA CON TU SERVIDOR',source='MainActivity.kt')
s.field('URL del servicio','https://tu-servidor.example:8443');s.text('Pega la URL HTTPS sin /ready ni /v1.',12,color='muted');s.field('Usuario','administrador');s.field('Contraseña','••••••••••••');s.button('INICIAR SESIÓN','inicio');s.text('Acceso por rol: administrador o auditor.',12,color='muted')
s=Screen('login-error','02 · Error de acceso','INICIAR SESIÓN',source='MainActivity.kt');s.card('No se pudo iniciar sesión','Revisa tus credenciales, la URL y la conexión.');s.field('URL del servicio','https://tu-servidor.example:8443');s.field('Usuario','administrador');s.field('Contraseña','••••••••••••');s.button('REINTENTAR','login')
s=Screen('inicio','03 · Inicio con datos','TU DISPOSITIVO DE UN VISTAZO');s.field('Dispositivo seleccionado','Teléfono principal  ⌄');s.button('ACTUALIZAR','inicio',True);s.text('Actualización automática · cada 10 s',12,color='muted');s.card('Teléfono principal','Inscripción activa\nÚltimo contacto: hace menos de 1 min','VER EQUIPO','detalle');s.card('Batería y conexión','Batería: 72 %\nConexión disponible\nÚltima lectura: Hoy · 10:30');s.card('Actividad reciente','SMS recibido · Hoy · 10:29\nWhatsApp: notificación recibida · 10:28');s.button('VER HISTORIAL DE EVENTOS','eventos')
s=Screen('inicio-vacio','04 · Inicio sin equipos','TU DISPOSITIVO DE UN VISTAZO');s.card('SIN DISPOSITIVOS','Revisa los equipos archivados o vincula un teléfono desde Más → Crear código de inscripción.');s.button('ACTUALIZAR','inicio',True)
s=Screen('inicio-antiguo','05 · Lectura antigua y red','TU DISPOSITIVO DE UN VISTAZO');s.card('No se pudo actualizar','Comprueba Internet, Tailscale y el servidor. Se muestran los últimos datos recibidos.');s.card('Teléfono principal','Inscripción activa\nÚltimo contacto: Ayer · 18:40');s.card('Lectura antigua','Batería: 42 %\nÚltima lectura: Ayer · 18:40\nEste dato no garantiza conexión actual.');s.button('ACTUALIZAR','inicio',True)
for key,name,archived in [('equipos','06 · Equipos visibles',False),('archivados','07 · Equipos archivados',True)]:
    s=Screen(key,name,'DISPOSITIVOS','SELECCIONA EL TELÉFONO',tab='EQUIPOS');s.field('Estado de inscripción','Todos  ⌄');s.field('Mostrar','Archivados  ⌄' if archived else 'Sin archivar  ⌄');s.text('Nombres y archivo guardados solo en este Centro.',12,color='muted');s.card('Teléfono anterior' if archived else 'Teléfono principal',('Archivado en este Centro\nSin contacto reciente' if archived else 'Contacto reciente · Inscripción activa')+'\nInscrito: 29 oct · 09:00\nÚltimo contacto: Hoy · 10:30','VER DISPOSITIVO','detalle-archivado' if archived else 'detalle');s.button('VER SIN ARCHIVAR' if archived else 'VER ARCHIVADOS','equipos' if archived else 'archivados',True);s.text('Referencia del filtro Mostrar. Se aplica a la página cargada.',11,color='muted')
for key,name,state in [('detalle','08 · Detalle activo','Inscripción activa'),('detalle-archivado','09 · Detalle archivado','Archivado en este Centro'),('detalle-revocado','10 · Detalle revocado','Revocado')]:
    s=Screen(key,name,'DISPOSITIVO','ESTADO, CONSENTIMIENTO Y ACCIONES',tab='EQUIPOS');s.button('VOLVER A DISPOSITIVOS','equipos',True);s.text('Teléfono principal' if key=='detalle' else 'Teléfono anterior',21,True);s.button('CAMBIAR NOMBRE','nombre',True);s.text('Nombre y archivo locales a este Centro.',12,color='muted');s.button('RESTAURAR DISPOSITIVO' if key=='detalle-archivado' else 'ARCHIVAR DISPOSITIVO','restaurar' if key=='detalle-archivado' else 'archivar',True);s.card(state,'Inscrito: 29 oct · 09:00\nÚltimo contacto: Hoy · 10:30\nUna inscripción activa no confirma conexión.');s.card('Consentimiento registrado','Aceptado: 29 oct · 09:00\nEl propietario puede retirarlo desde el Agente.')
    if key!='detalle-revocado':
        s.card('Alertas de WhatsApp','Consulta visible cada 5 s. Puede detenerse por restricciones de Android.');s.button('ACTIVAR ALERTAS PARA ESTE TELÉFONO','alertas');s.button('VER NOTIFICACIONES DE WHATSAPP','whatsapp',True);s.button('VER PANTALLA COMPARTIDA','pantalla',True)
    s.button('VER EVENTOS','eventos');s.button('VER AUDITORÍA DEL GRUPO','auditoria',True)
    if key!='detalle-revocado':s.button('REVOCAR DISPOSITIVO','revocar',True)
    s.button('INFORMACIÓN TÉCNICA','tecnico',True)
s=Screen('tecnico','11 · Información técnica','INFORMACIÓN TÉCNICA',tab='EQUIPOS');s.card('Identificador de inscripción','00000000-0000-4000-8000-000000000001\nDatos ficticios de diseño.');s.button('COPIAR IDENTIFICADOR',outlined=True);s.text('Versión de la app: 0.7.1-preview\nInscrito: 29 oct · 09:00\nPolítica: mvp-1');s.button('VOLVER AL DISPOSITIVO','detalle',True)
dialog('nombre','12 · Cambiar nombre','Nombre del dispositivo','Entre 1 y 60 caracteres. El nombre se guarda en este Centro.','Guardar','detalle','Teléfono principal')
dialog('archivar','13 · Confirmar archivo','Archivar dispositivo','Se ocultará de Inicio y de la lista habitual de este Centro. Conserva su historial y puede seguir enviando eventos. Para bloquearlos utiliza Revocar.','Confirmar','archivados')
dialog('restaurar','14 · Confirmar restauración','Restaurar dispositivo','Volverá a aparecer en Inicio y en la lista habitual de este Centro.','Confirmar','equipos')
dialog('revocar','15 · Confirmar revocación','Revocar dispositivo','Se bloquearán nuevas transmisiones. Un Agente sin conexión detectará la revocación al volver a contactar. Reinscribir exige consentimiento y código nuevos.','Confirmar revocación','detalle-revocado')
s=Screen('eventos','16 · Eventos y mensajes','EVENTOS','ACTIVIDAD RECIBIDA DEL TELÉFONO',tab='EVENTOS');s.text('Todo  ·  WhatsApp  ·  SMS  ·  Llamadas',14,True);s.button('FILTRAR EVENTOS','filtros',True);s.button('EXPORTAR CSV','csv',True);s.text('RESULTADOS',12,True);s.card('WhatsApp recibido','De: Contacto de prueba\nHola, llego en diez minutos.\nHoy · 10:30\nTeléfono principal','VER MENSAJE COMPLETO','whatsapp');s.text('Vista previa temporal, fuera del historial y CSV.',12,color='muted');s.card('SMS recibido','Teléfono principal\nDe: +51 900 000 001\nMensaje ficticio para diseño.\nHoy · 10:29','VER DETALLE','sms');s.card('Llamada saliente','Teléfono principal\nPara: +51 900 000 002\nHoy · 10:25','VER DETALLE','llamada');s.text('Página 1 · Hasta 50 eventos\nLa consulta queda registrada en auditoría.',12,color='muted')
s=Screen('filtros','17 · Filtros expandidos','FILTRAR EVENTOS',tab='EVENTOS');s.field('Dispositivo','Teléfono principal  ⌄');s.field('Categoría','Mensajes y llamadas  ⌄');s.field('Desde · UTC','Sin límite  ⌄');s.field('Hasta · UTC','Sin límite  ⌄');s.field('ID o código exacto','Todos');s.button('APLICAR FILTROS','eventos');s.button('EXPORTAR CSV','csv',True);s.button('LIMPIAR FILTROS','eventos',True);s.text('Los filtros de fecha/ID consultan el historial de actividad; no recuperan texto temporal de WhatsApp.',12,color='muted')
for key,name,title,body in [('sms','18 · Detalle SMS','SMS recibido','De: +51 900 000 001\nTexto del SMS\nHola, este es un mensaje ficticio.\nTeléfono principal\nHoy · 10:29\nEvento real del dispositivo'),('llamada','19 · Detalle llamada','Llamada saliente','Para: +51 900 000 002\nTeléfono principal\nHoy · 10:25\nNo incluye audio ni nombres de contactos.'),('whatsapp-evento','20 · Actividad WhatsApp','Notificación de WhatsApp recibida','Teléfono principal\nHoy · 10:28\nEste registro conserva tipo y fecha. La vista previa de texto pertenece a una sesión temporal autorizada.')]:
    s=Screen(key,name,'DETALLE DE EVENTO','DIÁLOGO',tab='EVENTOS',source='MainActivity.kt');s.card(title,body);s.button('INFORMACIÓN TÉCNICA','evento-tecnico',True);s.button('CERRAR DETALLE','eventos')
s=Screen('evento-tecnico','21 · Detalle técnico de evento','DETALLE DE EVENTO',tab='EVENTOS',source='MainActivity.kt');s.card('Información técnica','Recibido por el servidor: Hoy · 10:29\nDispositivo: ID de inscripción ficticio\nCódigo: sms_received\nCategoría: SMS\nDirección: Entrante\nConsentimiento: ID ficticio\nPolítica: mvp-1');s.button('CERRAR DETALLE','eventos')
s=Screen('eventos-vacio','22 · Eventos vacíos','EVENTOS',tab='EVENTOS');s.button('FILTRAR EVENTOS','filtros',True);s.card('SIN DATOS RECIBIDOS','No se recibieron eventos para los filtros aplicados.');s.button('ACTUALIZAR','eventos',True)
s=Screen('csv','23 · Exportación CSV','EXPORTAR CSV','DIÁLOGO DEL SISTEMA · REFERENCIA',tab='EVENTOS',system=True);s.card('Guardar archivo','AuditLab-eventos.csv\nAndroid permite elegir carpeta y destino. Su aspecto cambia según el teléfono.');s.button('GUARDAR','csv-listo');s.button('CANCELAR','eventos',True);s.text('Hasta 10 000 eventos según filtros. Incluye SMS autorizado; excluye texto temporal de WhatsApp e imágenes.',12,color='muted')
s=Screen('csv-listo','24 · CSV guardado','EVENTOS',tab='EVENTOS');s.card('Reporte guardado','Exportación completada.');s.button('ABRIR ÚLTIMO CSV GUARDADO',outlined=True);s.button('VOLVER AL HISTORIAL','eventos',True)
for key,name,active in [('whatsapp','25 · WhatsApp autorizado',True),('whatsapp-off','26 · WhatsApp detenido',False)]:
    s=Screen(key,name,'WHATSAPP','VISTAS PREVIAS AUTORIZADAS',tab='EVENTOS',source='WhatsAppPreviewsView.kt');s.text('Texto disponible en notificaciones nuevas; no incluye todo el chat ni lo enviado dentro de él.',13,color='muted');s.button('ACTUALIZAR WHATSAPP','whatsapp',True);s.card('SESIÓN ACTIVA' if active else 'SESIÓN DETENIDA','De: Contacto de prueba\nHola, llego en diez minutos.\nHoy · 10:30\nWhatsApp Business' if active else 'En el teléfono Agente concede acceso Android y autoriza la sesión para compartir nuevas vistas previas.');s.text('Últimas 20 vistas previas, hasta 5 minutos en memoria. Desaparecen al detener; no se incluyen en CSV.',12,color='muted');s.button('VOLVER AL DISPOSITIVO','detalle',True)
s=Screen('alertas','27 · Alertas activas','ALERTAS DE WHATSAPP','SECCIÓN DEL DETALLE DEL EQUIPO',tab='EQUIPOS',source='WhatsAppAlerts.kt');s.card('Alertas activas para Teléfono principal','Servicio visible que consulta cada 5 segundos. Android puede detenerlo por batería o límites del sistema.');s.button('DETENER ALERTAS','detalle',True);s.card('Permiso necesario','Android 13 o superior pide permitir notificaciones del Centro. La sesión del Agente también debe estar autorizada.');s.button('VER EJEMPLO DE AVISO','aviso',True)
s=Screen('aviso','28 · Notificación Android','AVISO EN EL CENTRO','SUPERFICIE DEL SISTEMA · REFERENCIA',tab='EQUIPOS',source='WhatsAppAlerts.kt',system=True);s.card('AuditLab · Alertas activas','Consultando Teléfono principal cada 5 segundos.','DETENER','detalle');s.card('Teléfono principal · Contacto de prueba','Hola, llego en diez minutos.','ABRIR EVENTOS','eventos');s.text('Contenido oculto en bloqueo. Los avisos de texto caducan; el sistema puede modificar su presentación.',12,color='muted')
for key,name,active in [('pantalla','29 · Pantalla compartida',True),('pantalla-off','30 · Pantalla sin sesión',False)]:
    s=Screen(key,name,'PANTALLA EN VIVO','SESIÓN AUTORIZADA DESDE EL AGENTE',tab='EQUIPOS',source='ScreenViewerActivity.kt');s.card('Pantalla compartida' if active else 'No hay sesión activa','[Contenido compartido por el propietario]\n\nReferencia visual, sin imagen privada.\n\nActualización aproximada cada segundo.' if active else 'Esperando una sesión iniciada en el Agente…');s.text('El propietario controla el inicio y el fin. No se graba video ni audio.',13,color='muted');s.button('CERRAR VISOR','detalle')
s=Screen('auditoria','31 · Auditoría','AUDITORÍA','QUIÉN CONSULTÓ O MODIFICÓ QUÉ',tab='MÁS');s.button('ACTUALIZAR AUDITORÍA',outlined=True);s.card('Consulta de eventos','Hoy · 10:30\nResultado: Completado','INFORMACIÓN TÉCNICA','auditoria-tecnica');s.card('Dispositivo inscrito','Hoy · 09:00\nResultado: Completado');s.button('VOLVER','ajustes',True)
s=Screen('auditoria-tecnica','32 · Auditoría técnica','AUDITORÍA',tab='MÁS');s.card('Consulta de eventos','Actor: usuario ficticio\nRecurso: eventos del equipo\nResultado: Completado\nFiltros: Teléfono principal\nCorrelación: ID ficticio');s.button('OCULTAR INFORMACIÓN TÉCNICA','auditoria',True)
for key,name,created in [('inscripcion','33 · Crear inscripción',False),('codigo','34 · Código generado',True)]:
    s=Screen(key,name,'INSCRIPCIÓN','VINCULA UN DISPOSITIVO AUTORIZADO',tab='MÁS');s.card('UN CÓDIGO · UN DISPOSITIVO','Introduce el código en el Agente tras aceptar el consentimiento. Vence en 10 minutos y tiene un uso.');s.button('CREAR CÓDIGO DE UN USO','codigo')
    if created:s.card('CÓDIGO DE EJEMPLO','NO-VALIDO-DEMO\nVence: en 10 minutos\nEste texto no inscribe ningún equipo. No publicar códigos reales.')
    s.button('VOLVER','ajustes',True)
for key,name,admin in [('ajustes','35 · Ajustes administrador',True),('ajustes-auditor','36 · Ajustes auditor',False)]:
    s=Screen(key,name,'AJUSTES','CUENTA, SERVIDOR Y DISPOSITIVOS',tab='MÁS',source='MainActivity.kt');s.card('usuario@example.test','Rol: '+('administrador' if admin else 'auditor')+'\nServidor conectado\nhttps://tu-servidor.example:8443');s.text('Para cambiar el servidor, cierra sesión y edita la URL al volver a entrar.',12,color='muted');s.button('CONSULTAR AUDITORÍA','auditoria')
    if admin:s.button('CREAR CÓDIGO DE INSCRIPCIÓN','inscripcion')
    s.button('ACTUALIZAR DISPOSITIVOS','equipos',True);s.button('CERRAR SESIÓN','logout',True)
dialog('logout','37 · Confirmar cierre','Cerrar sesión','Se eliminará la sesión local de este Centro de control.','Confirmar salida','login')
s=Screen('cargando','38 · Actualización en curso','EVENTOS','ESTADO TRANSITORIO',tab='EVENTOS');s.card('Actualizando…','Consultando los últimos datos recibidos. Se conservan los datos de la consulta anterior mientras carga.');s.text('Los controles que puedan solapar operaciones quedan deshabilitados.',12,color='muted')
s=Screen('error-red','39 · Fallo de red','EVENTOS',tab='EVENTOS');s.card('No se pudo actualizar','Comprueba Internet, Tailscale y el servidor. Se muestran los últimos datos recibidos.');s.button('ACTUALIZAR','eventos',True)
s=Screen('selector','40 · Selector de dispositivo','SELECCIONAR DISPOSITIVO','MENÚ DESPLEGABLE · REFERENCIA');s.button('TELÉFONO PRINCIPAL','inicio',True);s.button('TELÉFONO SECUNDARIO','inicio',True);s.text('Los equipos archivados se consultan desde Equipos → Archivados.',12,color='muted')
s=Screen('fecha','41 · Selector de fecha','SELECCIONAR FECHA','DIÁLOGO MATERIAL · REFERENCIA',tab='EVENTOS');s.field('Rango UTC','30 de octubre de 2026');s.card('OCTUBRE 2026','L    M    X    J    V    S    D\n\n             1     2    3     4\n5    6    7    8     9   10   11\n12  13   14   15   16   17   18\n19  20   21   22   23   24   25\n26  27   28   29  [30]  31');s.button('ACEPTAR','filtros');s.button('CANCELAR','filtros',True)

for s in SCREENS:s.done()
data=dict(version='0.7.4',date='2026-10-30',width=390,colors=COLORS,screens=[dict(id=s.key,name=s.name,height=s.height,source=s.source,system=s.system,items=s.items) for s in SCREENS])
(OUT/'vistas.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

# Same layout model drives portable vector files, preview and native Figma import.
svgdir=OUT/'svg';svgdir.mkdir(exist_ok=True)
images=[]
for s in SCREENS:
    svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="390" height="{s.height}" viewBox="0 0 390 {s.height}"><title>{html.escape(s.name)}</title><rect width="390" height="{s.height}" fill="{COLORS["paper"]}"/>']
    image=Image.new('RGB',(390,s.height),COLORS['paper']);draw=ImageDraw.Draw(image)
    for e in s.items:
        x,y,w,h=e['x'],e['y'],e['w'],e['h']
        if e['type'] in ('rect','line'):
            svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{e["fill"]}" stroke="{e.get("stroke") or "none"}" stroke-width="0.7"/>')
            draw.rectangle((x,y,x+w,y+h),fill=e['fill'],outline=e.get('stroke'))
        else:
            size=e['size'];font=ImageFont.truetype(BOLD if e['bold'] else FONT,size)
            for j,line in enumerate(e['text'].split('\n')):
                yy=y+j*(size+7)
                svg.append(f'<text x="{x}" y="{yy+size}" font-family="DejaVu Sans, Inter, sans-serif" font-size="{size}" font-weight="{700 if e["bold"] else 400}" fill="{e["color"]}">{html.escape(line)}</text>')
                draw.text((x,yy),line,font=font,fill=e['color'])
    svg.append('</svg>');(svgdir/(s.name[:2]+'-'+s.key+'.svg')).write_text('\n'.join(svg))
    images.append(image)

cols=7;rows=(len(SCREENS)+cols-1)//cols
board=Image.new('RGB',(cols*242+32,rows*590+100),'#F7F7F2');d=ImageDraw.Draw(board)
d.text((28,18),'AUDITLAB · CENTRO DE CONTROL · 41 VISTAS',font=ImageFont.truetype(BOLD,25),fill=COLORS['ink'])
d.text((28,53),'Diseño editable de referencia · datos ficticios · app 0.7.4 · no son capturas del APK',font=ImageFont.truetype(FONT,14),fill=COLORS['muted'])
for i,(s,image) in enumerate(zip(SCREENS,images)):
    x=24+(i%cols)*242;y=102+(i//cols)*590
    d.text((x,y),s.name[:31],font=ImageFont.truetype(BOLD,11),fill=COLORS['ink'])
    # Fit long scrollable views completely rather than silently cropping controls.
    image.thumbnail((218,540));board.paste(image,(x,y+24))
board.save(OUT/'vista-general.png')

plugin=OUT/'figma-importador';plugin.mkdir(exist_ok=True)
(plugin/'manifest.json').write_text(json.dumps({'name':'AuditLab · Centro 0.7.4','api':'1.0.0','main':'code.js','editorType':['figma'],'documentAccess':'dynamic-page','networkAccess':{'allowedDomains':['none']}},indent=2)+'\n')
code=r'''const data=DATA;
function rgb(hex){return {r:parseInt(hex.slice(1,3),16)/255,g:parseInt(hex.slice(3,5),16)/255,b:parseInt(hex.slice(5,7),16)/255};}
async function main(){
 await figma.loadFontAsync({family:'Inter',style:'Regular'});
 await figma.loadFontAsync({family:'Inter',style:'Bold'});
 const page=figma.createPage();page.name='AuditLab · Centro 0.7.4 · Vistas';
 await figma.setCurrentPageAsync(page);
 const frames=new Map(),links=[];
 const rowHeights=[];for(let i=0;i<data.screens.length;i++){const row=Math.floor(i/7);rowHeights[row]=Math.max(rowHeights[row]||0,data.screens[i].height);}
 const ys=[];let y=0;for(const h of rowHeights){ys.push(y);y+=h+110;}
 for(let i=0;i<data.screens.length;i++){
  const s=data.screens[i],frame=figma.createFrame();const hotspots=[];page.appendChild(frame);
  frame.name=s.name;frame.x=(i%7)*470;frame.y=ys[Math.floor(i/7)];frame.resize(390,s.height);
  frame.fills=[{type:'SOLID',color:rgb(data.colors.paper)}];frame.clipsContent=true;
  frame.setPluginData('source',s.source);frame.setPluginData('reference','Design reference, not an APK screenshot; synthetic data.');
  frames.set(s.id,frame);
  for(const e of s.items){let n;
   if(e.type==='text'){n=figma.createText();n.fontName={family:'Inter',style:e.bold?'Bold':'Regular'};n.fontSize=e.size;n.lineHeight={value:e.size+7,unit:'PIXELS'};n.characters=e.text;n.fills=[{type:'SOLID',color:rgb(e.color)}];n.resize(e.w,e.h);n.textAutoResize='HEIGHT';n.name=e.text.split('\n')[0];}
   else {n=figma.createRectangle();n.resize(e.w,e.h);n.fills=[{type:'SOLID',color:rgb(e.fill)}];if(e.stroke){n.strokes=[{type:'SOLID',color:rgb(e.stroke)}];n.strokeWeight=0.7;}n.name=e.target?'Acción → '+e.target:(e.type==='line'?'Separador':'Panel');}
   frame.appendChild(n);n.x=e.x;n.y=e.y;
   if(e.target)hotspots.push([e,e.target]);
  }
  for(const [e,target] of hotspots){const hit=figma.createRectangle();frame.appendChild(hit);hit.name='Lien → '+target;hit.resize(e.w,e.h);hit.x=e.x;hit.y=e.y;hit.fills=[{type:'SOLID',color:{r:1,g:1,b:1},opacity:0.001}];links.push([hit,target]);}
 }
 let linked=0;for(const [node,target] of links){const frame=frames.get(target);if(frame){await node.setReactionsAsync([{trigger:{type:'ON_CLICK'},actions:[{type:'NODE',destinationId:frame.id,navigation:'NAVIGATE',transition:null}]}]);linked++;}}
 const components=figma.createFrame();page.appendChild(components);components.name='Guía · colores y componentes';components.x=7*470;components.y=0;components.resize(440,820);components.fills=[{type:'SOLID',color:rgb(data.colors.paper)}];
 let cy=24;for(const [name,hex] of Object.entries(data.colors)){const sw=figma.createComponent();components.appendChild(sw);sw.name='Color / '+name;sw.resize(392,65);sw.y=cy;sw.x=24;sw.fills=[{type:'SOLID',color:rgb(hex)}];const t=figma.createText();t.fontName={family:'Inter',style:'Regular'};t.fontSize=14;t.characters=name+' · '+hex;t.fills=[{type:'SOLID',color:rgb(name==='ink'?'#FFFFFF':data.colors.ink)}];sw.appendChild(t);t.x=12;t.y=20;cy+=85;}
 page.selection=[frames.get('inicio')];figma.viewport.scrollAndZoomIntoView([frames.get('inicio'),frames.get('eventos'),frames.get('equipos')]);
 figma.closePlugin(data.screens.length+' vistas editables creadas · '+linked+' enlaces de navegación de referencia.');
}
main().catch(error=>{figma.notify('No se completó la importación. Revisa la consola del plugin.',{error:true});console.error(error);figma.closePlugin();});
'''.replace('DATA',json.dumps(data,ensure_ascii=False))
(plugin/'code.js').write_text(code)

readme='''# AuditLab — vistas del Centro para Figma

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

'''+''.join(f'- {s.name} — `{s.source}`'+(' · superficie del sistema' if s.system else '')+'\n' for s in SCREENS)
(OUT/'LEEME.md').write_text(readme)
archive=ROOT/'docs/diseno/AuditLab-Centro-Figma-0.7.4.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(OUT.rglob('*')):
        if p.is_file():z.write(p,Path('AuditLab-Centro-Figma')/p.relative_to(OUT))
print(json.dumps({'screens':len(SCREENS),'archive':str(archive),'bytes':archive.stat().st_size,'preview':str(OUT/'vista-general.png')}))
