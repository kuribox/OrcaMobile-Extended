# Release 0.5.0 — primer release del fork

Slicer de OrcaSlicer para Android con soporte de impresoras Flashforge, interfaz en español y
exportación real de `.gcode.3mf`.

## Qué incluye

**Impresoras Flashforge**
- Tipo de host real en el perfil de impresora (OctoPrint, Moonraker, Flashforge, Duet, PrusaLink…).
- API HTTP nativa en el puerto 8898 para Adventurer 5M / 5M Pro / AD5X: *Enviar* y *Enviar e imprimir*.
- Campos **Número de serie** y **Código de acceso**, y botón **Probar conexión**.

**`.gcode.3mf`**
- Exportación y envío en paquete 3MF real (`Metadata/plate_1.gcode`, `slice_info.config`,
  `project_settings.config`, `model_settings.config`, miniatura de la placa), no un G-code renombrado.
- Tanto *Exportar* como *Compartir* preguntan el formato.

**Español**
- Interfaz traducida y textos del motor desde `OrcaSlicer_es.po`.
- Selector de idioma en **App → Idioma** (Sistema / English / Español / Русский).

**Visor de información**
- Panel con scroll que ya no tapa la pieza.
- Leyenda de color con la paleta exacta del visor y los valores medidos (mínimo, media, máximo).
- Lista de tipos de línea con su color y sus tiempos/pesos.

## Instalación

APK firmado con la clave de depuración: se instala encima de la versión de depuración sin
desinstalar. Android 5.0+ (arm64-v8a).

    9e3013aeb303274ab5e4754c391b5821419f71b8fdaa1764b1bd8b7eff3e8b79  OrcaSlicerMobile_0.5.0.apk   (compilado desde el commit 00d8ab7)

## Límites conocidos

- La subida y la aceptación del `.gcode.3mf` no se han validado contra firmware real de 5M / 5M Pro / AD5X.
- Build alpha: revisa siempre la previsualización del G-code antes de imprimir.
- El motor nativo empaquetado procede del APK oficial 0.4.6 (vía prebuilt); el APK de este release no
  recompila `libslic3r`.
