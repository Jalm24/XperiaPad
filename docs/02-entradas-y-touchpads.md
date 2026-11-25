<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 02 — Entradas: teclas, gamepad y touchpads

> Todo verificado en el dispositivo el 2026-11-15. Evidencia cruda: `download/logs/getevent-p.txt`,
> `download/logs/keylayout/*.kl`, `download/kernel/.../drivers/input/joystick/synaptics_touchpad.c`.
> Lo único pendiente está marcado como **PENDIENTE (medir)**.

## 1. Mapa completo de `/dev/input`

`getevent -p` enumeró 12 dispositivos (event0…event11). Los que importan al proyecto:

| Nodo | Nombre | Tipo | Relevancia |
|---|---|---|---|
| `event4` | `keypad-zeus` | teclado | **Gamepad físico**: D-pad, ○ ✕ □ △, L1/R1, PS, Home/Menu/Back/Search |
| `event1` | `keypad-game-zeus` | teclado | **START, SELECT**, volumen |
| `event2` | `synaptics_touchpad` | táctil multitouch | **Los dos touchpads** (una sola tira de 966×360) |
| `event3` | `cy8ctma300_touch` | táctil pantalla | Pantalla 479×853 (no se usa como mando) |
| `event0` | `msm_pmic_pwr_key` | teclado | Encendido/volumen; no se toca |
| `event5` | `bma150` | acelerómetro | No se usa |
| `event6` | `gp2ap002a00f` | sensor de luz/proximidad | No se usa |
| `event7` | `simple_remote` | teclado | Auricular BT (si algún día se soporta) |
| `event8` | `simple_remote_appkey` | teclado | Ídem |
| `event9` | `compass` | brújula | No se usa |
| `event10` | `atdaemon` | teclado virtual | Inyección de teclado del sistema |
| `event11` | `AVRCP` | teclado | Controles AVRCP del BT |

## 2. Botones físicos (`event4`, `keypad-zeus`)

Códigos evdev tal como los reporta el kernel, y a qué los traduce Android
(`/system/usr/keylayout/keypad-zeus.kl`):

| Botón físico | evdev | `.kl` → Android keycode | Nota |
|---|---|---|---|
| D-pad arriba | 103 | `DPAD_UP` | |
| D-pad izquierda | 105 | `DPAD_LEFT` | |
| D-pad derecha | 106 | `DPAD_RIGHT` | |
| D-pad abajo | 108 | `DPAD_DOWN` | |
| ✕ (cruz) | 304 | `DPAD_CENTER` | **No** es `BUTTON_A` |
| ○ (círculo) | 305 | `BACK` (`ALT`) | Mapeado a BACK por el ROM |
| □ (cuadrado) | 307 | `BUTTON_X` | |
| △ (triángulo) | 308 | `BUTTON_Y` | |
| L1 | 310 | `BUTTON_L1` | |
| R1 | 311 | `BUTTON_R1` | |
| PS | 226 | `MENU` | |
| Home | 102 | `HOME` (`WAKE_DROPPED`) | |
| Menu | 139 | `MENU` | |
| Back | 158 | `BACK` | |
| Search | 217 | `SEARCH` | |
| Tecla "vendor" | 360 | `KEY_VENDOR` | Sin `.kl` explícito; llega como `KEYCODE_UNKNOWN` + scan 360 |

`event4` también emite `SW (0005): 0000` (lid/switch) y `MSC (0004): 0003`
(`MSC_SCAN`), útil para distinguir pulsaciones repetidas.

## 3. START / SELECT y volumen (`event1`, `keypad-game-zeus`)

| Botón | evdev | `.kl` → Android |
|---|---|---|
| START | 28 | `BUTTON_START` |
| SELECT | 314 | `BUTTON_SELECT` |
| Volumen + | 115 | `VOLUME_UP` |
| Volumen − | 114 | `VOLUME_DOWN` |

El `.kl` del ROM comenta que START se dejó como "select" para navegar con el D-pad,
pero el código evdev real es 28 (`KEY_ENTER`).

## 4. Touchpads: hardware y driver

- Chip **Synaptics TM145** (driver `kernel/drivers/input/joystick/synaptics_touchpad.c`,
  `CONFIG_JOYSTICK_SYNAPTICS=y`, `CONFIG_JOYSTICK_SYNAPTICS_FLASH=y`).
- El driver valida el nombre del producto leyendo 17 bytes de `F01_RMI/QUERY` y comparando
  `buf+11 == "TM145"`; si no coincide, `-ENODEV`.
- Los dos pads son **una sola superficie continua**, no dos dispositivos:
  `ABS_MT_POSITION_X` 0…**966**, `ABS_MT_POSITION_Y` 0…**360**.
- No hay `ABS_MT_SLOT` (el kernel es 2.6.32: **protocolo A**), ni `ABS_MT_PRESSURE`.
  El driver reporta: `ABS_MT_TRACKING_ID` (= `finger+1`), `X`, `Y`, `TOUCH_MAJOR`, `TOUCH_MINOR`,
  `ORIENTATION` y un `input_mt_sync()` **por dedo**; un `input_sync()` al final del frame.
- Sin dedos: el driver emite un `input_mt_sync()` vacío + `input_sync()` (frame "sin contacto").
- `ABS_MT_TRACKING_ID` va de 1 a `n_fingers` (los dedos se numeran por índice de registro, no por orden
  de llegada). **No** se puede asumir que 1 = pad izquierdo.

### 4.1 Parseo correcto (protocolo A)

```
frame = []
on ABS_MT_TRACKING_ID(x):  current = nuevo dedo con id x
on ABS_MT_POSITION_X(x):   current.x = x
on ABS_MT_POSITION_Y(y):   current.y = y
on SYN_MT_REPORT:          frame.append(current); current = None
on SYN_REPORT:             commit(frame); frame.clear()
```

Reglas prácticas:
- Un frame con **un solo** `SYN_MT_REPORT` y sin `TRACKING_ID` = "no hay dedos".
- Dos dedos solo aparecen si ambos se mueven en el mismo frame; si un dedo se queda quieto,
  el chip puede no reenviarlo. **Hay que mantener estado por `TRACKING_ID`**, no reconstruir por frame.
- La zona de cada pad es fija por hardware; el eje X es lo que discrimina.

### 4.2 Zonas de los pads — **PENDIENTE (medir)**

El corte exacto entre pad izquierdo y derecho no está en el driver ni en el `.kl`.
Procedimiento (2 minutos, requiere el teléfono en la mano):

```bash
# en el PC
adb shell getevent -lt /dev/input/event2
# tocar SOLO el pad izquierdo (arrastrar de arriba a abajo) y luego SOLO el derecho
# apuntar los valores X mínimos y máximos de cada pad
```

Con esos dos rangos se fija `ZONE_LEFT = [x0, x1]`, `ZONE_RIGHT = [x2, x3]` y el centro de cada uno.

**Medido (2026-11-16, `getevent -t` con el dedo quieto sobre cada indicador; superficie 966×360):**

| Indicador | Centro | Rango observado |
|---|---|---|
| Izquierdo | **(172, 148)** | x 158–196, y 131–156 |
| Derecho | **(806, 160)** | x 802–834, y 156–177 |

Estos centros son la base de las zonas por defecto de los sticks (`sticks_v2`): izq. `x=12,y=0,w=320,h=320` (centro 172,160); der. `x=646,y=0,w=320,h=320` (centro 806,160).

Mientras no se mida, el modo por defecto puede usar **el pad completo como stick izquierdo**
y el eje Y invertido como stick derecho (mapeo "un dedo = un stick"), que funciona sin calibrar.

## 5. Captura híbrida (decisión tomada)

Dos adaptadores intercambiables tras una misma interfaz `InputSource`:

### 5.1 Modo normal (Java, sin root para el input)

- `Activity.dispatchKeyEvent()` recibe `KEYCODE_DPAD_*`, `BUTTON_A/B/X/Y/L1/R1`, `BUTTON_START/SELECT`.
- Touchpads: llegan como `MotionEvent` con `getSource() == SOURCE_TOUCHPAD`
  (constante añadida en API 9; Gingerbread la soporta). `getX()/getY()` ya vienen en píxeles de pantalla,
  así que **hay que usar `getRawX/getRawY` o normalizar con `getXPrecision()`** para recuperar 0…966 / 0…360.
  Alternativa fiable: leer el rango con `InputDevice.getMotionRange(AXIS_X)`.
- Requiere pantalla encendida y la app en primer plano (o un `Service` en foreground, que en 2.3 no existe:
  hay que usar `Notification` + `startForeground` no disponible → mantener la Activity visible).
- Ventaja: cero riesgo de romper el teléfono, cero permisos especiales de input.

### 5.2 Modo avanzado (evdev, requiere root)

- Abrir `event4` + `event1` + `event2` con `O_RDONLY|O_NONBLOCK`, `EVIOCGRAB` para quedarse con ellos.
- Leer `struct input_event` (16 bytes en ARMv7: `timeval` 8 + `type` 2 + `code` 2 + `value` 4).
- Inyectar de vuelta las teclas que **no** son del gamepad (HOME, volumen, back) por `/dev/uinput`
  (`UI_SET_EVBIT`, `UI_SET_KEYBIT`, `UI_DEV_CREATE`), porque `EVIOCGRAB` las secuestra.
- Funciona con la pantalla apagada; latencia mínima.
- Riesgo: si el daemon muere con el grab puesto, el kernel lo libera al cerrar el fd (no hay teclado "colgado"),
  pero durante el cierre hay una ventana de ~ms sin input.

### 5.3 Selección

Ajuste persistente de la app (por defecto **normal**). El modo avanzado se activa en Ajustes y muestra
un aviso explícito de que las teclas Home/volumen se reenvían por `uinput`.

## 6. Qué NO se puede hacer (comprobado)

- No hay `/dev/uhid` (llegó en Linux 3.5) → no se puede crear un HID de kernel; el HID va por Bluetooth (L2CAP).
- `getevent` existe en el ROM pero **no** `sendevent` con permisos de app → la inyección requiere root.
- Las teclas del gamepad **no** generan `MotionEvent`: son `KeyEvent`, no un joystick analógico.
  Los sticks son los touchpads.
