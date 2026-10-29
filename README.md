# OrcaSlicer Mobile — Flashforge Edition

**Fork of [CodeMasterCody3D/OrcaSlicer-Mobile](https://github.com/CodeMasterCody3D/OrcaSlicer-Mobile) with Flashforge printer support, Spanish translation, and `.gcode.3mf` export.**

Please consider donating to the upstream maintainer:

[Donate to OrcaSlicer Mobile for Android Via PayPal](https://www.paypal.com/donate/?business=E37Z2QEXT7AQW&no_recurring=0&item_name=I+have+built+orcaslicer+mobile+for+Android.+I+need+your+help+to+keep+paying+for+my+ai+tools+to+keep+providing+bug+fixes.+Thanks&currency_code=USD)

[Donate to OrcaSlicer Mobile for Android Via Patreon](https://www.patreon.com/cw/CodeMasterCody3D)

[Donate to OrcaSlicer Mobile for Android Via Cash app](https://cash.app/$codyddixon)

---

A 3D printing slicer for Android, powered by the real **OrcaSlicer** engine.

OrcaSlicer Mobile takes the excellent Android slicer shell from [Slice Beam](https://github.com/utkabobr/SliceBeam) and replaces its slicing core with a full port of [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer)'s `libslic3r` — so the G-code you slice on your phone comes from the same engine you use on the desktop.

## Features (added in this fork)

- **Flashforge printer support** — Native HTTP API (port 8898) for Adventurer 5M / 5M Pro / AD5X. Configure *Serial Number* and *Access Code* in the printer profile, then use "Send to printer" / "Send and print" from the slice menu.
- **Spanish translation** — Full UI in Spanish (es), based on OrcaSlicer's official `OrcaSlicer_es.po` and translated app strings. Selectable in-app from **App → Language** (System / English / Español / Русский).
- **Print info panel with real values** — Scrollable, height-capped panel that lists each extrusion role and shows the numeric range (min – max · average) of the active color scheme, computed from the G-code.
- **`.gcode.3mf` export** — Export sliced output as a standard 3MF package containing G-code, metadata, and preview thumbnails. Compatible with Flashforge, Bambu Lab, Qidi, and other printers that accept `.gcode.3mf`.
- **Extended host types** — Printer profile `host_type` enum now exposes all types defined by OrcaSlicer (OctoPrint, Moonraker, Flashforge, Duet, PrusaLink, etc.).

## Base features (from upstream)

- **Full OrcaSlicer slicing engine** running natively on-device: Arachne wall generator, tree supports, seam control, adaptive layers, and the rest of the Orca feature set
- **OrcaSlicer profile import** — load `.orca_printer` / `.orca_filament` config bundles, including profile inheritance resolution and automatic download of vendor base profiles
- **Multi-color painting** — paint models with brush, bucket-fill, and height-range tools from a palette of up to 16 filaments; sliced output gets proper tool changes, a prime tower, and per-filament flush volumes
- **G-code preview** with feature-type and filament/tool color views
- **Profile editor** with OrcaSlicer setting names and categories
- **3MF / STL / STEP / OBJ model import**, transform tools, auto-arrange, cut tool, auto-orient
- **Fully offline** — no account, no cloud, no telemetry

## Install

Guía en español para principiantes: [docs/GUIA_PRINCIPIANTE_ES.md](docs/GUIA_PRINCIPIANTE_ES.md).

Download the latest APK from the [Releases](../../releases) page and sideload it (you may need to allow
"install from unknown sources").

Every release is published as a **pre-release (alpha)**: the slicing engine and the printer upload work,
but the output is not production quality — check the G-code preview before starting a print. GitHub hides
pre-releases from the "Latest" slot on the repository page, so grab the APK from the Releases tab.

Requirements: Android 5.0+, 64-bit ARM device (arm64-v8a).

## Build from source

```bash
git clone https://github.com/kuribox/OrcaMobile-Extended.git
cd OrcaMobile-Extended
git submodule update --init --recursive
```

- Android SDK 35, NDK `23.1.7779620`
- The native engine (libslic3r + dependencies) builds via CMake on the first build — expect it to take a while
- Prebuilt native dependencies not stored in git (Boost, oneTBB, OCCT, GMP/MPFR) are expected under `app/src/main/jniImports/` and `app/src/main/occt/`
  - Use `scripts/build_all_deps_android_fixed.sh` to compile them (several hours), or copy prebuilt libraries from a compatible build
- The output APK lands in `app/build/outputs/apk/debug/`

### Fast path: build the Java layer against the upstream engine

All changes in this fork live in Java (`SliceMenu`, `ProfileListFragment`, `GCode3mfPackager`, resources).
You can therefore assemble an APK without rebuilding the C++ engine, by reusing the native libraries
published in the upstream `0.4.6` APK:

```bash
# 1. Fetch an upstream release APK (same versionName as this tree)
mkdir -p vendor/upstream-apk
curl -L -o vendor/upstream-apk/OrcaSlicerMobile_0.4.6.apk \
  https://github.com/CodeMasterCody3D/OrcaSlicer-Mobile/releases/download/0.4.6/OrcaSlicerMobile_6fc2e14b9a.apk

# 2. Extract the arm64 libraries where the prebuilt path expects them
rm -rf app/src/prebuiltNative/jniLibs
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
out = Path("app/src/prebuiltNative/jniLibs/arm64-v8a"); out.mkdir(parents=True, exist_ok=True)
with ZipFile("vendor/upstream-apk/OrcaSlicerMobile_0.4.6.apk") as z:
    for n in z.namelist():
        if n.startswith("lib/arm64-v8a/") and n.endswith(".so"):
            (out / Path(n).name).write_bytes(z.read(n))
PY

# 3. Assemble
./gradlew assembleDebug -PusePrebuiltNative=true
```

`-PusePrebuiltNative=true` skips CMake and packages `src/prebuiltNative/jniLibs` exclusively
(the Gradle config swaps the `jniLibs.srcDirs` instead of adding to it, to avoid duplicate-entry failures).

Verify the JNI contract holds for the reused `.so` before trusting the result:
every `static native` in `slic3r/Native.java` must exist as a `Java_ru_ytkab0bp_slicebeam_slic3r_Native_*`
symbol in `libslic3r.so` (117/117 at the time of writing).

Note: the Java package path remains `ru.ytkab0bp.slicebeam` for now because native JNI method names are tied to it; the `applicationId` is `com.codemastercody3d.orcaslicermobile`.

## Flashforge setup

The flow mirrors the desktop printer dialog, including its discovery step — you never type a serial
number by hand.

1. Open the printer profile → **Physical Printer** tab.
2. Tap **Find Flashforge printers**. The app broadcasts the same USR-WIFI232 discovery payload to UDP
   `48899` that desktop OrcaSlicer sends and listens on UDP `18007`.
3. Pick your printer from the list. That fills **Print host** (its IP) and **Serial number** (read from
   the discovery reply at the same offset the desktop uses).
4. Type the printer's check code into **API key** — that is the field desktop OrcaSlicer stores it in
   (`printhost_apikey`). The same key names are used (`flashforge_serial_number`, `printhost_apikey`),
   so a printer profile exported from the desktop works unchanged.
5. Tap **Test connection** (POST `/detail` on port 8898 with `{serialNumber, checkCode}`).
6. After slicing, use **Send** or **Send and print** from the slice menu: multipart `POST /uploadGcode`
   on port 8898 with the `serialNumber`, `checkCode`, `fileSize`, `printNow`, `levelingBeforePrint`,
   `flowCalibration`, `firstLayerInspection`, `timeLapseVideo`, `useMatlStation`, `gcodeToolCnt` and
   `materialMappings` headers, and a `code`/`err` check on the JSON reply.

The app uses Flashforge's local HTTP API (port 8898) — no internet required. The sliced file is uploaded as `.gcode.3mf` (metadata + G-code + embedded thumbnails).

**Test connection** works for every host type: OctoPrint/Klipper (`/api/version`), Moonraker (`:7125/server/info`) and Flashforge (`:8898/detail`).

## Spanish translation

The app detects the system language. If the device is set to Spanish, all menus, settings, and slicer strings appear in Spanish. The translation combines:
- OrcaSlicer's official `localization/i18n/es/OrcaSlicer_es.po` (slicer engine strings)
- `res/values-es/strings.xml` (app-specific UI strings)

### Changing the language inside the app

**App → Language** offers *System / English / Español / Русский*. The choice is stored in the app
preferences (`app_language`), applies to both layers at once — Android resources via a per-locale
`Context` in `attachBaseContext`, and the OrcaSlicer `.po` strings via the default `Locale` — and the
current screen is recreated so everything re-resolves without restarting the app.

## Guided assistant

**View → File → Assistant** opens a guided mode aimed at someone who has never sliced before. It is
not a separate screen: it is a panel over the real View tab, so the plate you drag, the auto-arrange,
the gestures and the sliced preview are the ones the app always uses. That also means leaving at any
point keeps the work: the parts stay on the plate, the answers stay in the active profile as unsaved
edits (`SliceBeam.LIVE_DIFF_PRINT`) and a finished slice stays in the viewer.

| Step | What it does |
| --- | --- |
| 1 · Parts | Opens the document picker with `EXTRA_ALLOW_MULTIPLE`, so every STL/3MF can be chosen at once. Each part is loaded, auto-oriented and arranged; the list allows removing one with the ✕ button. |
| 2 · Plate | The real 3D plate is live under the panel: drag to move, two fingers to rotate, pinch to zoom. The panel adds *Arrange* (`Bed3D.arrange`) and scaling of the selected part. |
| 3 · Printer | Lists the printer profiles and switches the active preset. |
| 4 · Quality | Lists the **print profiles**: quality is the profile itself (layer height, speeds, process temperatures), not a bare number. |
| 5 · Options | Supports (none / tree / normal), adhesion brim (none / automatic / outer), skirt and infill (10 / 25 / 40 %), written **into the chosen print profile** and saved, so changing the quality later does not discard them. Requires a profile: without one the step only explains how to get it. |
| 6 · Filament | Lists the filament profiles; *Finish without slicing* exits here with everything applied. |
| 7 · Slice | Slicing starts on entering the step (no button), with a spinner, a 3-minute timeout and `Throwable` handling so an out-of-memory is an error message, not a frozen panel. The result loads through `BedFragment.loadGCode`, so the preview is the View tab's own, followed by send / send and print / download / share on their own row. |

Scaling applies to the **selected part** (tap one first) and has two units (**%** of the original and
**mm**), three `NumberPicker` carousels (X / Y / Z, wrap-around, keyboard blocked) and a padlock: closed keeps the
proportions — in mm the other axes follow the same ratio, they are not forced to the same millimetre
value — open lets one dimension change. Nothing is applied until *Apply*, and the delta is computed against
what the panel already applied — the engine caches object boxes, so re-reading them made every Apply
multiply again or jump back to the original. After each change the meshes are invalidated
(`BedFragment.invalidateModel`) so the plate repaints instead of waiting for the next gesture. Going back from the slice step drops the result, so changing
anything and continuing slices again.

Implementation notes worth knowing before touching it: the panel is added to `BedFragment.overlayLayout`
and only its bottom 46 % consumes touches, the rest falls through to the plate; `WizardOverlay` keeps the
step state, and `MainActivity` routes the picker result back to it (`REQUEST_CODE_WIZARD_FILE`).

## Print info panel

After slicing, the **Info** entry in the bed menu lists every extrusion role with its time, filament
length and weight, and lets you toggle each one. The panel is scrollable and is capped to part of the
screen so the model stays visible while you switch color scheme.

Above the role list, a **color legend** explains what each color means. For the value-based schemes
(speed, flow, temperature, …) it draws the exact 11-color palette libvgcode renders and labels the ends
with the measured minimum and maximum plus the average, so a color can be read back as a value; the
layer-time-logarithmic scheme is marked as such. For line type and filament the colors are categorical,
so the legend names them instead: each row of the list below carries its color swatch.

The *Values* block shows the real numbers behind the active color scheme
(min – max · average): layer height (mm), line width (mm), speed (mm/s), volumetric flow (mm³/s),
temperature (°C), fan speed (%) and layer time (s). `ViewLegend` is a Java port of libvgcode's
`ColorRange` (palette + linear/logarithmic interpolation) verified against the C++ implementation. They come from `GCodeStats`, a single pass over the
sliced G-code that derives speed from the `F` values, flow and effective line width from the extruded
volume against segment length, and layer heights from the `Z` steps. Nothing is invented: if the file
carries no measurable moves for a metric the row reads *No values*.

## Exporting and sharing

After slicing, the slice menu has two actions — **Export** and **Share**. Both ask which format to produce:

- **G-code** (`.gcode`) — plain sliced output.
- **3MF package** (`.gcode.3mf`) — what Flashforge, Bambu Lab and Qidi printers accept.

The package follows OrcaSlicer's own exporter (`src/libslic3r/Format/bbs_3mf.cpp`) part by part instead
of being a renamed zip:

| Part | Contents |
| --- | --- |
| `[Content_Types].xml` | the same four defaults Orca writes (`rels`, `model`, `png`, `gcode`) |
| `_rels/.rels` | `rel-1` model, `rel-2`/`rel-4` plate thumbnail, `rel-5` small thumbnail |
| `3D/3dmodel.model` | real geometry, exported from the loaded model by the engine |
| `Metadata/plate_1.gcode` (+ `.md5`) | the sliced G-code |
| `Metadata/plate_1.png` / `plate_1_small.png` | plate preview (300×300) and its 128×128 companion |
| `Metadata/slice_info.config` | prediction, weight, nozzle diameter, filaments and objects |
| `Metadata/model_settings.config` | plate and object metadata |
| `Metadata/project_settings.config` | flat JSON of every setting |
| `Metadata/_rels/model_settings.config.rels`, `cut_information.xml`, `filament_sequence.json`, `plate_1.json` | the remaining parts the engine emits |

Two details that used to be wrong and made printers show no preview or reject the file:

- The native exporter writes G-code with a null thumbnail callback, so sliced files carry no
  `; thumbnail` blocks. When none is found, `GCodeThumbnail` rasterises a top view of the toolpaths at
  300×300 instead of shipping a 1×1 placeholder.
- Values interpolated into the XML are escaped (`&`, `<`, `>`, quotes); object names read back from the
  model are unescaped first so nothing is escaped twice.

## Changelog

All notable changes of this fork. Dates are local (Europe/Madrid).

### 0.5.1 — guided assistant (unreleased)

- **Asistente guiado**: six-step panel over the real View tab for first-time users — multi-file
  picking, plate with arrange and per-part scaling (% or mm, X/Y/Z carousels, proportion padlock),
  printer and filament selection, four beginner questions, and slicing on entering the last step with
  the same 3D preview and information as the manual flow. Leaving at any point keeps the parts, the
  unsaved settings and the sliced result.
- Slicing from the assistant fails after 3 minutes instead of spinning forever, and reports
  `OutOfMemoryError` instead of hanging.

### 0.5.0 — 2026-10-24 (first fork release)

**Flashforge printers**
- `host_type` is no longer hardcoded to OctoPrint: the printer profile lists every type OrcaSlicer
  defines (OctoPrint, Moonraker, Flashforge, Duet, PrusaLink, …), translated from the engine's own
  enum labels.
- Native Flashforge HTTP API on port **8898** for Adventurer 5M / 5M Pro / AD5X: *Send* and
  *Send and print*, with `serialNumber`, `checkCode`, `fileSize`, `printNow`, `levelingBeforePrint`,
  `gcodeToolCnt` and `materialMappings` headers.
- Printer profile gained a *Physical printer* section (host type, host, API key, serial number) and a
  **Test connection** button.
- **LAN discovery** (UDP 48899 → 18007, same payload and offsets as the desktop) fills the host and the
  serial number, so the serial is never typed by hand — matching how the desktop dialog behaves.
- `PrintHostTester` probes each protocol properly: `POST /detail` on Flashforge, `GET /api/version`
  with `X-Api-Key` on OctoPrint/Klipper, `GET :7125/server/info` on Moonraker.

**`.gcode.3mf` export**
- `GCode3mfPackager` builds a real OPC/3MF package — `Metadata/plate_1.gcode`,
  `slice_info.config`, `project_settings.config`, `model_settings.config`, `plate_1.json`, plate
  thumbnail taken from the G-code and `3D/3dmodel.model` — instead of renaming the raw G-code.
- **Export** and **Share** both ask which format to produce (G-code or 3MF package), like desktop
  OrcaSlicer.
- Fixed `slice_info.config` units: weight in grams, prediction in seconds (was grams × 1000).

**Spanish**
- Full Spanish UI: `res/values-es/` plus OrcaSlicer's official `OrcaSlicer_es.po` for engine
  strings.
- In-app language picker: **App → Language** (System / English / Español / Русский), applied to both
  resource and `.po` layers without restarting.
- Bed menu labels use `AppCompatTextView` auto-sizing so long translations are not clipped.

**Print info panel**
- Scrollable and height-capped, so the model stays visible while switching color scheme.
- Color legend: the exact 11-color palette libvgcode renders, with the measured minimum, average and
  maximum for speed, flow, layer height, line width, temperature, fan and layer time; log scale and
  estimated (`≈`) schemes are marked. `ViewLegend` is a Java port of `ColorRange.cpp`, cross-checked
  against the C++ implementation.
- `GCodeStats` derives those numbers in a single pass over the sliced file.
- Each extrusion role row now shows its color swatch, making the line-type view self-explanatory.

**Build**
- New `-PusePrebuiltNative=true` path: build only the Java layer against the engine `.so` files of the
  upstream APK, which turns a multi-hour native build into a two-minute build.
- `scripts/build_all_deps_android_fixed.sh` builds Boost, oneTBB, OCCT and GMP/MPFR for arm64-v8a with
  the headers CMAKE actually expects.
- Fixed the `Duplicate resources` failure when both `jniLibs` trees are present.

**Known limits**
- Upload and `.gcode.3mf` acceptance were not validated against real 5M / 5M Pro / AD5X firmware.
- Sliced output is alpha; always inspect the preview before printing.

## Credits

This fork builds on the work of:

- [Slice Beam](https://github.com/utkabobr/SliceBeam) by utkabobr — the Android shell, JNI bridge, and OpenGL preview
- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) by SoftFever — the slicing engine and Spanish translations
- [PrusaSlicer](https://github.com/prusa3d/PrusaSlicer) / Slic3r and [Bambu Studio](https://github.com/bambulab/BambuStudio), which OrcaSlicer is built upon
- [CodeMasterCody3D](https://github.com/CodeMasterCody3D) — OrcaSlicer Mobile upstream

## Status

Experimental / alpha. Sliced output should always be sanity-checked in the G-code preview before printing. Issues and feedback are welcome.

## License

[AGPL-3.0](LICENSE), same as the projects it derives from. Source availability and attribution must be maintained for distributed APKs.