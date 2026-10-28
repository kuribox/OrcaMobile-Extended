# Guía para principiantes — OrcaMobile Extended (Android)

Esta guía asume que la app ya está instalada y el perfil de impresora configurado. Si todavía no lo está, empieza por [Configurar la impresora](#14-configurar-la-impresora-en-2-minutos).

Todos los nombres de botones y ajustes son los que verás **en español**.

---

## 0. Modo asistente — todo en cinco pantallas (empieza por aquí)

Si es tu primera vez, no uses los menús sueltos: el **Asistente** te lleva de la mano.

**Cómo entrar**: pestaña **Vista** → sección **Archivo** → botón **Asistente** (es el primero, con un
destello). Si no lo ves, desliza la fila de botones.

**Paso 1 · Elegir el modelo.** Pulsa **Elegir archivo STL / 3MF** y escoge el archivo en el selector
de Android. Al cargarlo, la app lo orienta sola y lo deja apoyado en la cama.

**Paso 2 · Elegir la impresora.** Aparece la lista de perfiles que ya tienes configurados. Elige la
tuya; la app coloca la pieza otra vez sobre la cama de esa impresora. Si no aparece, sal del asistente
y créala en la pestaña **Impresora** (mira el apartado Configurar la impresora, al final).

**Paso 3 · Cuatro preguntas rápidas.** No hay que entender todos los ajustes; con esto sale bien la
mayoría de las piezas:

| Pregunta | Cuándo elegir qué |
| --- | --- |
| **¿Soportes?** | Figuras, brazos o zonas en el aire → **tipo árbol**. Piezas mecánicas con voladizos planos → **normales**. Si la pieza se aguanta sola → **no** |
| **¿Borde de adherencia?** | Déjalo en **Automático**. Piezas altas y estrechas que se despegan → **solo exterior** |
| **¿Falda?** | **Sí, una vuelta** para cebar la boquilla. **No** si quieres ahorrar material |
| **Calidad** | **Normal (0,2 mm)** casi siempre; **fina** para detalle; **rápida** para pruebas |
| **Relleno** | 10 % decoración, 25 % uso normal, 40 % resistente |

Estas respuestas **no modifican tus perfiles guardados**: se aplican como ajustes temporales de la
sesión (en la pestaña Impresión los verás marcados como cambios sin guardar, y el botón ↺ los
descarta).

**Paso 4 · Elegir el filamento.** Lista de perfiles de material. Define temperaturas y ventilación.

**Paso 5 · Laminar y actuar.** Pulsa **Laminar ahora**. Al terminar verás una **miniatura** de la
pieza y cuatro datos: **tiempo estimado**, **filamento**, **capas** y **velocidad media**. Debajo, los
botones:

- **Enviar a la impresora** — manda el archivo por wifi (deja el trabajo en la impresora).
- **Enviar e imprimir** — lo manda y empieza a imprimir.
- **Descargar al teléfono** — guarda el archivo donde tú elijas.
- **Compartir** — lo manda a otra app (WhatsApp, Drive, correo…).

En los cuatro casos la app pregunta antes el **formato**: *Solo G-code (.gcode)* o *Paquete de
impresión (.gcode.3mf)*. Si dudas, elige **.gcode.3mf**: lleva las miniaturas y es el que aceptan las
impresoras por wifi.

Al terminar, **Salir del asistente** te devuelve a la cama con el G-code ya cargado, donde tienes el
visor completo, **Info impresión** y **Capas**.

---

## 1. El mapa de la pantalla (léelo aunque tengas prisa)

Abajo, fija, está la **barra de navegación** de la app con cinco pestañas:

| Pestaña | Para qué sirve |
| --- | --- |
| **Vista** | La cama 3D: abrir, mover y laminar la pieza |
| **Impresión** | La calidad: altura de capa, relleno, soportes, falda… |
| **Filamento** | Temperaturas y tipo de material |
| **Impresora** | Perfil de la máquina y datos de conexión |
| **Ajustes** | Idioma, tema, etc. |

Dentro de **Vista**, en la parte baja, hay una **segunda barra** con cuatro secciones: **Archivo · Cámara · Barra de herramientas · Laminar**.

Cada sección muestra **una fila de botones que se desplaza en horizontal**. Casi nunca caben todos: si no encuentras un botón, **desliza esa fila con el dedo hacia la izquierda**. Es el motivo número uno de "no me aparece el botón", así que cuando algo no esté, desliza antes de pensar que no existe.

> **Truco**: la fila de botones y la fila de pestañas de ajustes funcionan igual. Si ves un borde cortado a la derecha, hay más.

---

## 2. Abrir un modelo (STL, 3MF, STEP u OBJ)

1. Pestaña **Vista**.
2. Sección **Archivo**.
3. Botón **Abrir modelo** (el primero, icono de carpeta con un `+`).
4. Elige el archivo en el selector de Android.

Consejos:

- **STL**: lo más común; viene sin colores ni unidades, la app lo coloca en la cama.
- **3MF**: puede traer varias piezas, colores y ajustes; si lo preparaste en el escritorio, aquí se abre tal cual.
- Si el archivo no aparece en el selector, comprueba que está en el almacenamiento del teléfono y no sólo en la nube.

---

## 3. Colocar la pieza en la cama

- **Toca la pieza** para seleccionarla (se resalta). Los botones de transformación actúan sobre la pieza seleccionada.
- Sección **Barra de herramientas**. El orden de los botones es: **Platos · Auto Orient. · Organizar modelos · Dividir · Altura de capa · Mover · Rotar · Escalar · Aplanar · Cortar · Grabar · Medir**. Los últimos cinco están **fuera de pantalla**: desliza la fila.
- **Mantén pulsado sobre la pieza** y aparece el menú **Acciones del modelo**, con: Duplicar, Eliminar, Rellenar cama, Centrar en la cama, Auto orientar, Restablecer rotación, Restablecer escala, Apoyar en cara, Seleccionar todo y Eliminar todo.

Rutina rápida para una pieza nueva: **Auto Orient.** → **Organizar modelos** → comprobar que está apoyada en la cama (si flota, **Aplanar** o **Mover** en Z).

---

## 4. Configurar la calidad

1. Pestaña **Impresión**.
2. Arriba, el **nombre del perfil** en un desplegable: tócalo para cambiar de perfil, **Clonar actual** (para no tocar el original) o **Eliminar actual**.
3. Debajo hay una **fila de pestañas de categorías** que también **se desliza**: *Altura de la capa, Ancho de línea, Costura, Paredes y superficies, Relleno, Velocidad, Aceleración, Soportes, Falda, Borde de adherencia…* Son unas cuarenta, así que hay que deslizar.
4. Ajustes que de verdad notas en la impresión:

| Pestaña | Ajuste | Valor típico |
| --- | --- | --- |
| **Altura de la capa** | *Altura de la capa* | 0,2 mm (0,1 detalle / 0,28 rápido) |
| **Relleno** | *Densidad de relleno de baja densidad* | 15 % decorativo, 25–40 % resistente |
| **Ancho de línea** | *Ancho de línea* | déjalo en automático salvo que sepas por qué |
| **Velocidad** | velocidades por tipo de pared | baja la de pared exterior si ves brillos |

1. Para aplicar: botón **Guardar**, abajo a la derecha. El **icono de refresco (↺)** que está a su lado descarta los cambios y vuelve al perfil guardado.

> **Ojo**: los cambios se guardan **en el perfil**. Si cambias de perfil sin pulsar Guardar, se pierden.

---

## 5. Activar soportes

1. Pestaña **Impresión** → pestaña **Soportes** (desliza la fila de categorías hasta encontrarla).
2. Interruptor **Habilitar los soportes**.
3. Ajustes que conviene tocar:
    - **Tipo**: **Árbol (auto)** para figuras y figuras con brazos; **Normal (auto)** para piezas mecánicas.
    - **Estilo**: *Orgánico* deja menos marca; *Árbol Fuerte* aguanta más.
    - **Pendiente máxima**: a partir de qué inclinación se genera soporte (30–45° es lo habitual).
4. **Guardar**.

Prueba de que funciona: al laminar, en **Info impresión** aparece la línea **Soporte** con su tiempo y gramos.

---

## 6. Poner o quitar la falda y el borde de adherencia

Son dos cosas distintas y mucha gente las confunde:

- **Falda**: un hilo suelto alrededor de la pieza, para cebar la boquilla antes de empezar.
- **Borde de adherencia (brim)**: una base ancha **pegada** a la pieza, para que no se despegue.

**Falda** → pestaña **Falda** (desliza):

- *Bucles de la falda*: **0 = sin falda**, 1–2 es lo normal.
- *Altura de falda*, *Protector contra corrientes de aire* (útil con ABS).

**Borde de adherencia** → pestaña **Borde de adherencia** (desliza):

- *Tipo de borde de adherencia*: **Sin borde de adherencia**, **Solo borde de adherencia exterior**, **Exterior e interior**, **Orejas de ratón**, **Automático**.
- *Anchura del borde de adherencia*: 3–5 mm suele bastar.

Regla práctica: pieza alta y estrecha → borde exterior; pieza ancha con buena base → sin borde.

Después de cambiarlo, **Guardar**.

---

## 7. Cambiar el filamento

1. Pestaña **Filamento**.
2. Arriba, el desplegable del **perfil de filamento** (suele haber uno por material de tu perfil de impresora). Cambiar de perfil cambia temperaturas y ventiladores en bloque.
3. Ajustes que se tocan a mano:
    - **Otras capas**: temperatura de la boquilla en el resto de capas.
    - Primera capa: un poco más caliente para que pegue.
    - **Cama**: temperatura de la cama.
    - Prueba de flujo: si salen huecos o sobre-extrusión, ajusta el flujo en lugar de la temperatura.
4. En la pantalla **Impresión** verás además la **barra de filamentos** (cuadros de color numerados) con las ranuras del material; el botón **+** añade una ranura.

> **Aviso honesto**: esta versión no incluye editor de pintura multicolor. Para imprimir varias piezas de distinto color, prepara el 3MF en el escritorio con los colores ya asignados y ábrelo aquí; la app respeta esas asignaciones.

---

## 8. Laminar

1. Pestaña **Vista** → sección **Laminar** (la cuarta; en versiones anteriores al cambio de nombre aparecía como "Cortar").
2. Aparece una hoja con el **progreso**. Al terminar, la pantalla cambia al **visor del G-code** con la pieza en colores.
3. Si no hay nada en la cama, avisa: *"No hay objetos visibles"*.

Laminar no imprime: sólo calcula. Puedes volver a **Archivo** y cambiar cosas, y laminar otra vez.

---

## 9. Ver la información de impresión

Con el laminado hecho, la sección **Laminar** muestra botones nuevos. La fila **se desliza**:

- **Info impresión**: el panel con el desglose. Arriba la **leyenda de color** (mínimo – media – máximo del esquema activo) y debajo la lista de tipos de línea (perímetro, pared exterior, relleno, soportes, falda…) con su **color, tiempo y gramos**. El panel se desplaza en vertical; el botón **Esquema** cambia la vista del modelo a Velocidad, Flujo, Altura de capa, Temperatura, Tiempo de capa, Filamento… y la leyenda se actualiza con los valores de ese esquema.
- **Capas**: navegador para ver capa a capa, con control de rango.
- **Rendimiento**: modo rápido del visor, útil en móviles modestos.

Toca una fila del desglose para cambiar de esquema directamente desde ahí.

---

## 10. Exportar el archivo o compartirlo

En la sección **Laminar**:

- **Exportar** → primero elige **formato**: *Solo G-code (.gcode)* o *Paquete de impresión (.gcode.3mf)*; después eliges la carpeta del teléfono.
- **Compartir** → mismo diálogo de formato y luego el menú de apps (WhatsApp, Drive, Telegram…).

Cuál elegir:

- **.gcode**: cualquier impresora lo lee si lo metes por USB o SD.
- **.gcode.3mf**: el paquete completo (G-code + miniaturas + datos de la placa). Es el que aceptan Flashforge, Bambu Lab y Qidi por wifi, y el que muestra miniatura en la pantalla de la impresora.

---

## 11. Enviar a la impresora

1. Sección **Laminar** → **desliza la fila hasta el final**: **Enviar** y **Enviar e imprimir** son los dos últimos botones.
2. Antes hace falta que el perfil de impresora tenga la **IP** (pestaña **Impresora** → **Print host**, o el botón **Buscar impresoras Flashforge** que la rellena sola).
3. Qué manda según lo que tengas configurado:
    - Con **número de serie + API key**: sube el **`.gcode.3mf`** por HTTP (puerto 8898).
    - Sin código de verificación: sube el **G-code** por el puerto serie TCP 8899, que no pide credenciales (igual que el OrcaSlicer de escritorio).
4. **Enviar e imprimir** lanza la impresión al terminar la subida; **Enviar** sólo deja el archivo en la impresora.

Si no ves estos botones, es que falta la IP en el perfil o que no hay G-code laminado.

---

## 12. Problemas frecuentes

| Síntoma | Qué hacer |
| --- | --- |
| "No me aparece un botón" | Desliza la fila de botones o la de pestañas: casi siempre está más a la derecha |
| No salen **Enviar** / **Enviar e imprimir** | Falta la IP en **Impresora → Print host** |
| "Falta el código de verificación" | Escríbelo en **API key**, o deja el campo vacío y la app usará el puerto 8899 |
| La impresora dice "error de archivo" | Necesitas la versión con el arreglo del empaquetado `.gcode.3mf` |
| Cambios que no se aplican | Falta pulsar **Guardar** en el perfil |
| La pieza flota o atraviesa la cama | **Aplanar** o **Mover** en Z desde **Barra de herramientas** |
| El laminado tarda mucho | Baja la densidad de relleno, sube la altura de capa o activa **Rendimiento** |
| No sé si el soporte se generó | **Info impresión** debe listar la línea **Soporte** con tiempo y gramos |

---

## 13. Glosario rápido

- **Laminar / slice**: convertir el modelo en instrucciones (G-code) que la impresora entiende.
- **Altura de capa**: grosor de cada pasada. Menos altura = más detalle y más tiempo.
- **Relleno (infill)**: estructura interior. No hace falta macizo.
- **Falda (skirt)**: hilo de cebado alrededor, no se pega a la pieza.
- **Borde de adherencia (brim)**: base extra pegada a la pieza para que no se suelte.
- **Soporte**: estructura temporal bajo voladizos; se rompe al acabar.
- **G-code**: el archivo que imprime la máquina.
- **`.gcode.3mf`**: ese mismo G-code dentro de un paquete con miniaturas y datos de la placa.
- **Perfil**: conjunto guardado de ajustes (impresora, filamento, impresión).

---

## 14. Configurar la impresora (en 2 minutos)

1. Pestaña **Impresora**.
2. En **Print Host upload**, elige **Tipo** = `Flashforge` (o el que corresponda).
3. Pulsa **Buscar impresoras Flashforge**: aparece la lista de impresoras de la red; elige la tuya y se rellenan la IP y el número de serie.
4. Si tu impresora muestra un **código de verificación**, escríbelo en **API key**. Si no lo muestra, déjalo vacío: la app usará el puerto 8899, que no lo necesita.
5. Pulsa **Probar conexión**. El subtítulo del botón te dice qué ruta va a usar: `HTTP 8898` o `serie TCP 8899`.
6. **Guardar**.
