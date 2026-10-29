# Plan — servidor local en el móvil para laminar desde la PC

Objetivo: con la app abierta en el teléfono, abrir `http://<ip-del-movil>:8080` en el navegador de la
PC, arrastrar STLs, ordenarlos, laminar con el motor nativo del teléfono y descargar el `.gcode` o el
`.gcode.3mf`. El motor **no se mueve**: sigue siendo `libslic3r` + `libvgcode` en el teléfono, y el
navegador solo lo maneja.

## 1. Alcance

**Dentro de la v1**

- Servidor HTTP en el móvil, apagado por defecto, con token.
- Subir piezas (arrastrar y soltar), listar, borrar.
- Vista previa de la cama como PNG.
- Organizar automáticamente.
- Elegir perfil de impresora y filamento; las cinco opciones del asistente (soportes, borde, falda,
  calidad, relleno).
- Laminar con progreso real, tiempo, gramos, capas y hash del archivo.
- Descargar `.gcode` y `.gcode.3mf`.
- Enviar a la impresora (reutilizando el mismo transporte que la app).

**Fuera de la v1, a propósito**

- Cama 3D interactiva en el navegador (arrastrar/girar/escalar en WebGL). Es reescribir en JS lo que ya
  funciona en C++/Java; en su lugar va una captura PNG y, si hace falta, los botones de escalar.
- TLS y usuarios. Vale en la LAN de casa, no fuera.
- Descubrimiento de impresoras y edición completa de perfiles desde la web.

## 2. Lo que ya existe y se reutiliza

| Necesidad | Ya está en | Nota |
| --- | --- | --- |
| Cargar un STL/3MF en la cama | `BedFragment.loadModel(File)` — `BedFragment.java:948` | La lista de placas se actualiza de forma síncrona (no depende del hilo GL) |
| Laminar | `Model.slice(cfg, gcode, SliceListener)` — `Model.java:239` | `SliceListener.onProgress(int, String)` da progreso real: `SliceListener.java:3` |
| Config del perfil activo | `SliceBeam.genCurrentConfig()` / `getCurrentConfigFile()` — `SliceBeam.java:225`, `:243` | |
| Cambios sin guardar | `SliceBeam.LIVE_DIFF_PRINT.values` (`Map<String,String>`) | Es donde el asistente ya escribe las respuestas |
| Ruta del G-code temporal | `BedFragment.getTempGCodePath()` — `BedFragment.java:146` | |
| Paquete `.gcode.3mf` | `GCode3mfPackager.create(source, dest, printer, result)` — `GCode3mfPackager.java:107` | y `verify(File)` — `:181` |
| Miniaturas / vista superior | `GCodeThumbnail.render(File, int)` — `GCodeThumbnail.java:100` | |
| Métricas del G-code | `GCodeStats.analyze(File)` | tiempo, gramos, capas, velocidad |
| Enviar a la impresora | `HostTransfer.send(bed, print, use3mf, callback)` — `HostTransfer.java:92` | Flashforge 8898 / consola 8899 / OctoPrint |
| Ajustes donde poner el interruptor | `fragment/SettingsFragment.java` | ahí vive ya el selector de idioma |
| Permiso de red | `AndroidManifest.xml:6` (`INTERNET`) | suficiente para escuchar en LAN |

Dependencia nueva: `org.nanohttpd:nanohttpd:2.3.1` (Maven Central ya está en `build.gradle:5`, BSD-3,
compatible con la AGPL del proyecto, ~110 KB). Escribir el servidor a mano serían ~250 líneas de
`ServerSocket` con más casos raros que resolver; no compensa.

## 3. Arquitectura

```
Navegador PC  ──HTTP──►  LocalHttpServer (NanoHTTPD, hilo propio)
                              │
                              ├─ JobQueue (ExecutorService de 1 hilo)  ← una sola laminación a la vez
                              │
                              └─ PlateBridge  (interfaz, sin Android)
                                     │
                                     └─ BedFragment (WeakReference) ── Model / GLRenderer / LIVE_DIFF
```

Reglas que hacen esto mantenible:

1. **`LocalHttpServer` no importa ni una clase de `android.view`.** Habla con `PlateBridge`, una
   interfaz con métodos como `listParts()`, `addPart(File)`, `removePart(int)`, `arrange()`,
   `slice(Listener)`, `preview()`, `sendToPrinter(boolean, boolean)`. Así el servidor se puede probar
   en la JVM (ver §9) y el código de vistas queda fuera del camino crítico.
2. **Nada de tocar la UI desde el hilo del servidor**: `ViewUtils.postOnMainThread(...)` para lo que
   toque vistas y `glView.queueEvent(...)` para lo que toque el renderer.
3. **Una cola de un solo hilo** para las operaciones que usan el `Model`: subir, borrar, organizar y
   laminar se serializan; dos peticiones simultáneas no pueden corromper la cama. Si ya hay una
   laminación, la segunda recibe `409` con el estado del trabajo en curso.
4. **El servidor no vive sin la app**: si el `BedFragment` ya no existe, todo responde `409
   {"error":"app_closed"}` con un mensaje claro. No se guarda ningún `Activity` en el servicio (solo
   `WeakReference`).

### Ciclo de vida

- `LocalServerService` (servicio en primer plano, tipo `dataSync`) es el dueño del servidor. Se arranca
  desde **Ajustes → Servidor local** y se para desde el mismo sitio o desde la notificación.
- Notificación permanente con la URL, el número de piezas y el botón *Detener*.
- Se apaga solo tras 30 minutos sin peticiones.
- `WifiLock` de tipo `WIFI_MODE_FULL_HIGH_PERF` mientras haya un trabajo en curso (si no, el WiFi se
  duerme a mitad de la subida).

## 4. API HTTP

Token obligatorio en todas las rutas salvo `/`: cabecera `X-Orca-Token` o `?t=`. Si falta o no coincide,
`401` y se cuenta el fallo (a los 10 fallos seguidos el servidor se apaga solo).

| Método y ruta | Cuerpo / parámetros | Respuesta |
| --- | --- | --- |
| `GET /` | — | `index.html` (sin token: la página lo pide y lo guarda) |
| `GET /api/status` | — | `{version, parts:[{index,name,sizeMm}], printer, filament, job}` |
| `GET /api/preview.png` | `?w=640` | PNG de la cama (o miniatura del último laminado) |
| `PUT /api/files/{name}` | cuerpo binario + `Content-Length` | `{index, name, size}` tras cargar y organizar |
| `DELETE /api/files/{index}` | — | `{ok:true, parts:n}` |
| `POST /api/arrange` | — | `{ok:true}` |
| `POST /api/options` | JSON con `printer`, `filament`, `supports`, `brim`, `skirt`, `quality`, `infill` | `{ok:true}` (escribe en `LIVE_DIFF_PRINT`) |
| `POST /api/slice` | — | `202 {job:"slicing"}` o `409 {error:"busy"}` |
| `GET /api/job` | — | `{state, progress, message, stats:{seconds,grams,layers,speed}}` |
| `GET /api/gcode` | — | `.gcode` del último laminado |
| `GET /api/package` | — | `.gcode.3mf` (generado al vuelo con `GCode3mfPackager`) |
| `POST /api/send` | `?print=0|1&format=gcode|3mf` | `{ok, message}` de `HostTransfer` |

Detalles que evitan bugs clásicos:

- **Subida sin multipart**: el navegador hace `fetch(url, {method:'PUT', body: file})`; el servidor copia
  `session.getInputStream()` a un archivo en `getCacheDir()/server/` en bloques de 64 KB. Nada se
  acumula en memoria; tope de 500 MB por archivo y 60 s de `setSoTimeout`.
- **Nombre saneado**: solo `[A-Za-z0-9._-]`, se rechaza `..` y las rutas absolutas.
- **Progreso real**: `SliceListener.onProgress(progress, text)` del motor; el navegador hace polling de
  `GET /api/job` cada 700 ms (más simple que SSE y funciona detrás de cualquier proxy).
- **Timeout de laminado de 3 minutos** y `catch (Throwable)` (mismo criterio que el asistente): un
  `OutOfMemoryError` se convierte en `{"state":"error"}` en vez de dejar la petición colgada.

## 5. Interfaz web

`app/src/main/assets/web/` — tres archivos, **sin build, sin npm, sin framework**:
`index.html`, `app.js`, `style.css`. Sirve para abrir y funcionar; se versiona con el APK.

Pantalla única, tres bloques:

1. **Piezas**: zona de arrastrar y soltar, lista con nombre y tamaño, ✕ para borrar, botón *Organizar*.
   La vista previa (`/api/preview.png`) se refresca tras cada cambio.
2. **Configuración**: desplegables de impresora y filamento (de `/api/status`), y las cinco opciones del
   asistente con los mismos textos de la app.
3. **Laminar**: botón, barra de progreso con el texto del motor, y al terminar tarjeta con tiempo,
   gramos, capas, velocidad media y `sha256`, más *Descargar G-code*, *Descargar 3MF*, *Enviar* y
   *Enviar e imprimir*.

La página guarda el token en `localStorage`; si el servidor responde `401`, lo pide otra vez.

## 6. Fases, con criterio de salida

| Fase | Qué entra | LOC aprox. | Criterio de salida (comprobable) |
| --- | --- | --- | --- |
| **F1 · Esqueleto** | NanoHTTPD, token, `LocalServerService` mínimo, interruptor en Ajustes, `/api/status`, `/` placeholder | ~350 | La PC abre la URL y ve versión y `parts:0`; sin token → `401`; apagar desde Ajustes cierra el puerto (`ss -ltn` en la PC deja de verlo) |
| **F2 · Subir y ver** | `PUT /api/files`, `DELETE`, `/api/arrange`, captura PNG de la cama, HTML de arrastrar y soltar + lista | ~450 | Subes `aniołek.stl` (23 MB) y `Cuerpo_Caja_Core2.stl` (5,6 MB) desde la PC: aparecen en la cama del teléfono y en la lista del navegador; `sha256` del archivo en el móvil igual al del PC; 0 bytes perdidos |
| **F3 · Laminar y descargar** | Cola de trabajos, progreso, timeout, `/api/job`, `/api/gcode`, `/api/package`, tarjeta de resultado con hash | ~400 | Laminado desde el navegador: `prediction` y `weight` idénticos al laminado hecho en el teléfono; `GCode3mfPackager.verify` = true; la impresora acepta el 3MF descargado |
| **F4 · Configuración y envío** | `/api/options` sobre `LIVE_DIFF_PRINT`, `/api/send` con `HostTransfer`, escalar por ejes | ~300 | Cambias calidad a 0.16 desde la web y el G-code sale con `GCodeStats.height.avg ≈ 0.16`; *Enviar e imprimir* desde la web arranca la impresión igual que desde la app |
| **F5 · Servicio y robustez** | Servicio en primer plano (`dataSync`), `POST_NOTIFICATIONS` en Android 13+, `WifiLock`, apagado por inactividad, contador de fallos de token, `WeakReference` | ~250 | 10 minutos con la pantalla apagada: subir y laminar desde la PC sigue funcionando; batería medida y anotada en el README; matar la app desde Ajustes → `409 app_closed` en vez de colgarse |

Total ≈ **1.750 líneas nuevas**, 6–8 sesiones de trabajo. Nada de esto toca el motor nativo ni el
empaquetado de 3MF: son capas ya probadas.

## 7. Riesgos

| Riesgo | Impacto | Mitigación |
| --- | --- | --- |
| Android congela el proceso en segundo plano | subidas cortadas | servicio en primer plano + `WifiLock`; probarlo en F5 con pantalla apagada |
| Subida grande sin `Content-Length` | memoria o archivo truncado | exigir `Content-Length`, copiar por streaming, tope 500 MB, comparar `sha256` al terminar |
| Dos laminaciones a la vez | crash del motor | cola de un hilo + `409 busy` |
| Captura GL con la superficie pausada | PNG negro | `glReadPixels` dentro de `glView.queueEvent`, y si falla se devuelve la miniatura del último laminado |
| Token filtrado en logs | acceso a la cama en la LAN | no loguear el token, rotarlo desde Ajustes, apagado tras 10 fallos |
| El servidor queda encendido sin querer | superficie de ataque | apagado por defecto, auto-apagado a los 30 min, notificación visible siempre |

## 8. Orden de trabajo recomendado

1. F1 y F2 seguidas: sin subir archivos no hay nada que probar, y F1 sola no demuestra nada útil.
2. F3 en cuanto F2 funcione: es el corazón del pedido.
3. F4 y F5 al final, porque son comodidad y robustez sobre algo que ya funciona.

## 9. Cómo se prueba sin impresora y sin depender de la pantalla del móvil

- **JVM, antes de tocar el APK**: `LocalHttpServer` + `PlateBridge` con un doble de pruebas
  (`tools/server-harness/`) y `curl` contra los endpoints reales: `PUT`, `GET`, `401`, `busy`,
  nombres con `..`, archivo de 0 bytes. Eso valida rutas, tokens, límites y el HTML sin Android.
- **En el APK**: `adb logcat` con un log por petición (método, ruta, bytes, ms, código) para ver
  exactamente qué pasó en cada subida.
- **En la PC**: `curl -T archivo.stl -H "X-Orca-Token: …" http://ip:8080/api/files/archivo.stl` y
  comparación de `sha256` en los dos extremos. Es la prueba que distingue "responde 200" de
  "el archivo llegó completo".

## Estado de implementación

Implementado en el árbol (F1–F5 salvo lo marcado):

- `server/PlateBridge.java` — interfaz sin Android.
- `server/LocalHttpServer.java` — todas las rutas, token, streaming, cola de un hilo, watchdog de 3 min.
- `server/SliceJob.java` — estado y estadísticas del trabajo.
- `server/BedPlateBridge.java` — implementación sobre la cama real (`loadModel`, `deleteObject`,
  `Bed3D.arrange`, `LIVE_DIFF_PRINT`, `Model.slice`, `GCodeStats`, `GCode3mfPackager`, `HostTransfer`).
- `server/LocalServerService.java` — servicio en primer plano `dataSync`, notificación con la URL y
  acción de parada, `WifiLock` + `WakeLock`, apagado a los 30 min sin peticiones, `WeakReference` a la
  cama (nunca a la actividad).
- `app/src/main/assets/web/` — `index.html`, `app.js`, `style.css` (sin build).
- Ajustes → *Servidor local*: arrancar/parar, copiar dirección, cambiar token, estado en la fila.
- `render/GLRenderer.captureFrame()` + `BedFragment.capturePlatePng()` — vista previa PNG de la cama.
- `tools/server-harness/run.sh` — 12 comprobaciones sobre JVM y `curl` (token, subida de 3 MB, partes en
  disco, `202`, `409 busy`, job `done`, descargas, borrado, `411` sin `Content-Length`): **todas OK**.

Pendiente de verificación en dispositivo (no hay ninguno conectado): que el navegador de la PC vea la
página, que la captura GL salga bien con la superficie activa, y el consumo de batería con la pantalla
apagada durante 10 minutos.

Decisiones tomadas por defecto, cambiables: puerto **8080**, token **fijo visible** en Ajustes con botón
de rotación, envío a la impresora **incluido**, escalado por ejes **fuera de la v1**.

## 10. Decisiones que necesito de ti antes de empezar

1. **Puerto**: 8080 por defecto, ¿o prefieres otro?
2. **Enviar a imprimir desde la web**: ¿lo quieres en la v1 (F4) o prefieres laminar y descargar primero,
   y el envío se queda solo en el teléfono?
3. **Token**: fijo y visible en Ajustes (cómodo) o rotatorio al arrancar cada vez (más seguro, hay que
   copiarlo en cada sesión)?
4. **Escalar desde la web**: ¿entra en la v1 o se queda fuera hasta que la cama 3D web exista?
