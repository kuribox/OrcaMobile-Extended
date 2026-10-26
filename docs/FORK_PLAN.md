# Plan del fork: Flashforge + español + .gcode.3mf

Base: CodeMasterCody3D/OrcaSlicer-Mobile (master). Fork: kuribox/OrcaMobile-Extended.
Impresoras objetivo: Flashforge Adventurer 5M / 5M Pro / AD5X (API HTTP local, puerto 8898).

## Fase 0 — Entorno y build base (bloqueante)

Estado medido: faltan 61 de 64 librerías nativas ARM64 (Boost 1.85, oneTBB, OCCT, GMP/MPFR) en
`app/src/main/jniImports/` y `app/src/main/occt/`; falta el NDK `23.1.7779620` (hay 25.1, 26.1, 26.3, 27.0, 27.1).

- Instalar NDK 23.1.7779620 con sdkmanager (o fijar otro NDK y verificar que compila).
- Obtener las librerías: `scripts/build_all_deps_android.sh` (compila desde fuente, horas; tiene rutas
  `/home/cody/...` a corregir) o reutilizar los .a ya compilados de OrkanSlicer/Releases si existen.
- Criterio de salida: `python3 scripts/check-native-prebuilts.py arm64-v8a` sin faltantes y
  `./gradlew assembleDebug` genera un APK que arranca y corta un cubo.
- Las fases 1-3 son casi todo Java/recursos, pero no se pueden probar en el móvil sin un APK compilado.

## Fase 1 — Traducción al español

- `app/src/main/assets/localization/` solo tiene `en.po` y `ru.po`; se cargan por
  `Locale.getDefault().getLanguage()` en `Slic3rLocalization.java`.
- Descargar `localization/i18n/es/OrcaSlicer_es.po` de OrcaSlicer, normalizarlo al formato que lee el
  parser de la app (msgid/msgstr multilínea, sin plurales) y guardarlo como `localization/es.po`.
- Ajustar el parser si falla con entradas `msgctxt`/plurales.
- Cadenas propias de la app: crear `res/values-es/strings.xml` a partir de `values/strings.xml`
  (usar los textos de Orca donde coincidan, traducir el resto) y revisar textos fijos en código Java
  (p. ej. "Color scheme" / "Line type" en `SliceMenu.java`).
- Criterio de salida: con el móvil en español, ajustes de perfil y menús salen en español; sin claves vacías.

## Fase 2 — Host type Flashforge

Hoy: `SliceMenu.java:80` `SUPPORTED_SEND = ["octoprint"]` y `ProfileListFragment.java:809` fuerza la
lista de `host_type` a `OctoPrint`. El motor C++ ya tiene `htFlashforge` (`PrintConfig.cpp:149`).

- `ProfileListFragment`: mostrar los tipos de `def.enumValues` (octoprint, flashforge, y los que se
  implementen) en vez de la lista fija.
- Campos de perfil: serial number y check code (Orca los guarda como `printhost_user` / `printhost_password`
  en Flashforge; confirmar en `Flashforge.cpp` ctor antes de implementar).
- `SliceMenu.upload()`: nuevo caso `flashforge` (cliente en Java con la HTTP lib ya usada):
  - `POST http://<ip>:8898/detail` JSON `{serialNumber, checkCode}` para validar.
  - `POST http://<ip>:8898/uploadGcode` multipart `gcodeFile` con cabeceras `serialNumber`, `checkCode`,
    `fileSize`, `printNow`, `levelingBeforePrint`, `flowCalibration`, `firstLayerInspection`,
    `timeLapseVideo`, `useMatlStation`, `gcodeToolCnt`, `materialMappings` (base64 de JSON).
  - Éxito = JSON con `code == 0` (o `err == 0`); cualquier otro código es error visible.
- Criterio de salida: `/detail` real contra tu impresora, subida sin imprimir, subida con imprimir.

## Fase 3 — Salida .gcode.3mf

Hoy: el slice produce G-code plano (`BedFragment.getTempGCodePath()`). Orca sube `.3mf` a Flashforge
cuando `use_3mf` está activo (`Flashforge.cpp`, extensión `.3mf`).

- Primero medir qué acepta tu firmware: subir a mano un `.gcode.3mf` generado por OrcaSlicer de escritorio.
- Implementar el empaquetado: preferido, exponer por JNI el exportador existente de
  `Format/bbs_3mf.cpp` (`store_bbs_3mf`, soporta gcode.3mf); si el JNI es demasiado invasivo,
  empaquetar en Java (zip con `Metadata/plate_1.gcode`, `plate_1.json`, `slice_info.config`,
  `model_settings.config`, miniatura `plate_1.png`).
- Botón "Exportar" y subida usan el `.gcode.3mf` cuando el host es Flashforge (ajuste por impresora).
- Criterio de salida: el archivo abre en OrcaSlicer de escritorio con miniatura y metadatos, y la impresora
  lo acepta e imprime.

## Fase 4 — Cierre

- Actualizar `README.md`: nombre del fork, Flashforge, idioma español, `.gcode.3mf`, requisitos de build
  reales (NDK/SDK de `app/build.gradle`), créditos al upstream y a OrcaSlicer (AGPL-3.0).
- Subir a `kuribox/OrcaMobile-Extended` en ramas por fase.

## Estado actual (fase 0 cerrada por vía prebuilt)

- `scripts/build_all_deps_android_fixed.sh` compiló Boost 1.85, oneTBB y OCCT para arm64-v8a y los dejó en
  `app/src/main/jniImports/` y `app/src/main/occt/jniLibs/`.
- El build **completo desde fuente** de `libslic3r` aún no termina: exige copiar los headers (Boost completo y
  OCCT) y apuntar el include de CMake a `occt/include/<abi>/opencascade`. Ya corregido en `app/CMakeLists.txt`,
  pero no re-verificado de punta a punta.
- **APK generado** por la vía prebuilt: `.so` del APK oficial 0.4.6 dentro de `app/src/prebuiltNative/jniLibs/`
  y `./gradlew assembleDebug -PusePrebuiltNative=true`.
  Artefacto: `app/build/outputs/apk/debug/OrcaSlicerMobile_<commit>.apk` (58 MB, firmado con debug keystore).
  Contrato JNI verificado: 117/117 símbolos de `Native.java` presentes en `libslic3r.so`; las 21 librerías de
  `OCCTLoader` también están en el APK.
- Pendiente de prueba real en dispositivo (no hay `adb devices` conectado).

## Riesgos

- Fase 0 puede llevar horas y es lo único que impide probar cualquier cosa.
- Sin impresora accesible desde el móvil/PC, fases 2 y 3 solo se validan hasta el nivel de unit test.
- Push: la clave SSH de esta máquina autentica como otra cuenta de GitHub (no `kuribox`).
