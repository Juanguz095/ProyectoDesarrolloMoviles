# Estado actual — 4 de noviembre de 2026

Documento reconstruido, no recuperado del workspace anterior.

GitHub se consultó directamente: `main` y `work` estaban en
`0b1aa198e65e16764fb77ac4e474146ef328576e`. El checkout estaba limpio y no contenía
los seis archivos manuales ni los cuatro documentos locales indicados por el
responsable. No se usaron reset, clean ni sustitución del checkout.

## Versiones y evidencia

| Componente | Última distribución registrada | Fuente posterior |
| --- | --- | --- |
| Agente | 0.7.5-preview, código 14 | 0.8.0 código 15; reconstrucción 0.8.1 código 16 |
| Centro Android | 0.7.4-preview, código 13 | 0.8.0 código 14; reconstrucción 0.8.1 código 15 |
| Servidor Windows | 0.7, hasta V9 | Candidato 0.8/V10 sin publicación confirmada; V11 en desarrollo |

Las evidencias 0.8 son históricas y no validan 0.8.1. Consulte los resultados
nuevos en [AVANCE](AVANCE.md). No se anuncia una entrega final ni APK de
publicación validada. No se publica un ZIP nuevo: Defender debe analizar sus bytes
exactos. El estado de seguridad 0.7 no cubre otro archivo.

## Trabajo nuevo

[Texto manual 0.8.1](COMPARTIR_TEXTO_MANUAL_0.8.1.md): reconstrucción de cliente,
pantalla Android, recepción de ACTION_SEND, API/V11, filtros/detalle del Centro y
pruebas propias. Sin origen WhatsApp atribuido ni lectura automática de portapapeles.
La ubicación puntual, diagnósticos propios y estado ampliado siguen propuestos;
no hay seguimiento privado de otras apps ni grabación de llamadas SIM.

## Entorno y límites

No se recuperaron `.tooling`, AVD, bases, certificados o claves anteriores.
Se prepararon herramientas fijadas y fixtures nuevos; no son datos recuperados.
La instancia tiene disco total de 7,9 GB, sin `/dev/kvm`. La ejecución nativa
requiere capacidad adicional para imágenes/AVD y sus datos; conservar esta
limitación separada de las pruebas JVM/Robolectric/PostgreSQL.
