# AuditLab — contenido para preparar el PPT

Actualizado: 29 de octubre de 2026, America/Lima.
Referencia: Agente 0.7.1-preview, Centro 0.7.3-preview y servidor 0.7. Material de presentación del producto
actual; no implica que todas las funciones estén verificadas en teléfonos físicos.

Formato sugerido: **14 diapositivas**, 16:9, poco texto y una imagen por idea.
Puedes combinar 2–3 y 13–14 si necesitas una presentación más corta.

## 1. Portada

**AuditLab**
**Monitoreo autorizado de dispositivos Android**

- Dos aplicaciones Android conectadas a una API central.
- Consentimiento, consulta remota y trazabilidad.
- Integrantes, organización y fecha de tu presentación.

Visual: capturas de las dos apps con datos personales ocultos.

## 2. Problema

**¿Qué necesidad aborda?**

- El estado y la actividad del teléfono están dispersos.
- Consultarlos desde otro dispositivo requiere una conexión organizada y segura.
- Se necesita registrar quién accede a la información.
- El propietario debe conocer y poder detener el monitoreo.

Visual: teléfono con información dispersa → consulta centralizada.

## 3. Propuesta de solución

**Capturar, centralizar y consultar con autorización**

- Agente: obtiene los datos permitidos del dispositivo A.
- API: autentica, valida y recibe la información.
- Centro: permite consultar desde el dispositivo B.
- Consentimiento, permisos y auditoría acompañan el recorrido.

Visual: arquitectura general de la diapositiva 6 en versión simplificada.

## 4. Meta y objetivos

**Meta:** supervisar actividad autorizada mediante dos apps Android y una API.

- Inscribir dispositivos con consentimiento.
- Registrar y sincronizar eventos permitidos.
- Consultar historial y generar reportes.
- Controlar accesos y revocar dispositivos.

Visual: cuatro objetivos alrededor del sistema.

## 5. Usuarios y roles

**Tres responsabilidades**

| Participante | Qué hace |
| --- | --- |
| Propietario del Agente | Acepta permisos, inicia las sesiones y puede detenerlas. |
| Administrador | Consulta, exporta, crea códigos y revoca dispositivos. |
| Auditor | Consulta información y exporta reportes según su rol. |

Visual: tres tarjetas; la API aplica las restricciones, además de la interfaz.

## 6. Arquitectura

**Dos clientes y un servidor central**

```text
Agente Android ── HTTPS ── API Ktor ── PostgreSQL
                              │
Centro Android ── HTTPS ───────┘
```

- Room mantiene la cola local de eventos del Agente.
- PostgreSQL conserva los datos persistentes del servidor.
- WhatsApp y pantalla utilizan memoria temporal para su contenido.
- Las apps pueden usar redes diferentes si alcanzan la misma API HTTPS.

Visual para insertar: [PNG](presentacion/arquitectura.png) o
[SVG](presentacion/arquitectura.svg). [Explicación técnica](ARQUITECTURA.md).

## 7. Tecnologías

**Stack implementado**

| Área | Tecnologías |
| --- | --- |
| Apps | Kotlin, Jetpack Compose, ViewModel y coroutines. |
| Persistencia/trabajo Android | Room/SQLite y WorkManager. |
| Comunicación | Cliente HTTP propio, JSON y HTTPS. |
| Backend | Ktor/Netty, JDBC/HikariCP y PostgreSQL. |
| Seguridad y operación | Keystore, JWT/refresh, Argon2id, Flyway, Docker y Tailscale según despliegue. |

Retrofit, Firebase y Spring Boot no corresponden a esta implementación.

## 8. Funciones del Agente

**Captura controlada en el teléfono supervisado**

- Consentimiento, inscripción e indicador visible.
- Estado de batería/conexión y eventos propios.
- SMS/llamadas con permisos; número y texto SMS opcionales.
- WhatsApp por notificaciones y pantalla compartida por sesión autorizada.

Visual: captura de la pantalla del Agente; destaca pausa y retirada de consentimiento.

## 9. Funciones del Centro

**Consulta y administración**

- Login con rol y lista de dispositivos.
- Resumen de estado y último contacto.
- Historial con filtros, detalle, páginas y exportación CSV.
- Consulta de sesiones opcionales, revocación y auditoría.

Visual: pantalla Inicio + Eventos, con nombres y números personales ocultos.

## 10. Cómo viaja un evento

**Del teléfono al historial**

1. El propietario autoriza la captura.
2. El Agente registra el evento en Room.
3. WorkManager envía un lote autenticado a la API.
4. La API valida y guarda en PostgreSQL.
5. El Centro consulta; ese acceso queda auditado.

Si falla la red, el evento pendiente puede reintentarse con el mismo ID.
El contenido temporal de WhatsApp/pantalla tiene un flujo diferente, sin cola en disco.

Visual: [flujo PNG](presentacion/flujo-eventos.png) o
[SVG](presentacion/flujo-eventos.svg). Las flechas representan etapas del recorrido;
el Centro consulta la API, nunca la base de datos directamente.

## 11. Datos y conservación

**Separar eventos de contenido temporal**

| Información | Almacenamiento |
| --- | --- |
| Usuarios, dispositivos y consentimientos | PostgreSQL. |
| Eventos y SMS con texto autorizado | Room del Agente y PostgreSQL; pueden formar parte del CSV. |
| Auditoría | PostgreSQL; conserva acciones y consultas. |
| Texto WhatsApp e imágenes de pantalla | Memoria temporal; fuera de Room, SQL y CSV. |

Retención predeterminada: eventos servidor 30 días; auditoría 90 días.
WhatsApp: últimas 20 vistas previas, máximo 5 minutos; pantalla: último cuadro temporal.
[Modelo completo](base-datos.md).

## 12. Seguridad y límites

**El propietario conserva el control**

- Consentimiento y permisos Android; indicador visible y detención local.
- HTTPS, roles, credencial del Agente y sesión JWT/refresh del Centro.
- Keystore para credenciales Android; contraseñas del servidor con hash Argon2id.
- Revocación y registro de accesos.

Límites que conviene colocar en una franja al pie:

> WhatsApp depende de notificaciones: no ofrece historial completo ni todos los
> envíos desde el chat. Pantalla no es cámara remota ni video grabado. La
> revocación remota se conoce cuando el Agente vuelve a contactar al servidor.

## 13. Estado y validación

**Qué existe y qué está comprobado**

- Implementados: dos apps, API, persistencia, roles y sesiones opcionales.
- Apps 0.7.1: compilación, lint, auditoría estática y firma de actualización correctos.
- Pendiente: recepción real de WhatsApp y recorrido completo actual en teléfonos.
- Próxima entrega: validar los casos reales, corregir hallazgos y preparar firma de distribución/piloto.

No colocar “todo funciona sin errores”. No usar el total histórico de 197 pruebas
de 0.4 como prueba de 0.7.1. El análisis Defender del ZIP Windows 0.7 es una revisión
antimalware separada; no confirma su funcionamiento ni un falso positivo del proveedor.
[Estado y evidencias](AVANCE.md).

## 14. Cierre

**Aporte de AuditLab**

- Centraliza información autorizada del dispositivo.
- Separa captura, almacenamiento y consulta.
- Añade control de acceso, consentimiento y trazabilidad.
- Permite ampliar funciones manteniendo permisos y límites explícitos.

Visual: vuelve al diagrama de arquitectura y destaca los tres componentes.

## Material para una demostración

Una demostración real debe prepararse antes de presentar:

1. API/BD disponibles y ambos teléfonos con acceso a la misma URL HTTPS.
2. Login en Centro, consentimiento e inscripción en Agente.
3. Activar captura y mostrar una lectura reciente de batería/conexión.
4. Con autorización de texto, enviar/recibir un SMS nuevo entre teléfonos propios.
5. Mostrar filtros, detalle o CSV con datos de demostración no sensibles.
6. Mostrar WhatsApp o pantalla solo si ya se confirmó ese recorrido en los teléfonos.
7. Mostrar pausa o revocación explicando el efecto de un dispositivo sin red.

No mostrar contraseñas, tokens, códigos de inscripción vigentes, contactos privados
ni códigos SMS de verificación. Si una función falla, explicar su estado pendiente;
no presentar una captura simulada como recepción real.

## Respuestas breves para preguntas frecuentes

| Pregunta | Respuesta |
| --- | --- |
| ¿Necesita un servidor? | Sí: las apps usan una API central; puede estar en una PC o servidor accesible. |
| ¿Necesitan la misma WiFi? | No; ambas deben alcanzar la misma API HTTPS. |
| ¿Tailscale es obligatorio? | Es la opción del despliegue privado actual; puede sustituirse por una infraestructura HTTPS adecuada. |
| ¿Lee todo WhatsApp? | No; trabaja con notificaciones nuevas que Android exponga durante una sesión autorizada. |
| ¿Se puede ver la cámara? | No hay cámara remota implementada; se comparte la pantalla con permiso MediaProjection. |
| ¿Funciona sin Internet? | Los eventos pueden quedar pendientes; la consulta remota y el contenido temporal requieren conexión. |
| ¿Continúa con la app cerrada? | Los servicios visibles pueden seguir mientras Android lo permita. Forzar detención, reiniciar o retirar permisos puede detenerlos; las sesiones opcionales requieren nueva autorización. |
| ¿Se necesita Kali Linux? | No. Para desarrollar se utilizan Android Studio/SDK, JDK y herramientas del backend. |
| ¿Está terminado? | Hay una entrega de desarrollo; quedan validaciones reales y requisitos de distribución/piloto. |

## Instrucción opcional para una herramienta de presentaciones

> Crea una presentación 16:9 en español sobre AuditLab siguiendo las 14 diapositivas
> de este documento. Usa títulos claros, 3–4 ideas por diapositiva, fondo beige,
> texto oscuro, paneles rectangulares y diagramas sencillos. Inserta las imágenes
> de arquitectura y flujo proporcionadas. Distingue implementación y validación.
> Conserva los límites de WhatsApp, pantalla y datos autorizados. No inventes
> capturas, pruebas, cifras de usuarios, métricas de rendimiento ni funciones.
