<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 04 — MissionControl (Switch): qué acepta y por qué hay que imitar un GameStick

> **Estado actual (2026-11-24):** este documento es **investigación histórica** sobre el
> contrato GameStick. La implementación final usa identidad **DS3 `054c:0268`** con
> formato de report DS3 (`MODE ds3`); **GameStick/Gembox están retirados** (bug de d-pad
> en MC y sin ventajas frente a DS3). Publicado como **experimental**: no probado contra una Switch real por el usuario.
>
> Fuente: `download/reference/MissionControl-master/` (rama master, nov-2026).
> Los extractos de código son citas textuales del proyecto (GPLv2).

## 1. Qué es MissionControl

Un **sysmodule** de Atmosphere que se interpone (`MITM`) en el servicio `btm` de la consola.
Traduce mandos Bluetooth "no oficiales" a un **mando de Switch emulado** por software, de forma que
el sistema cree que hay un Pro Controller conectado. No parchea el firmware de los mandos: escucha
los reports HID que el mando envía y los reescribe.

Consecuencia para nosotros: **no hablamos con la Switch "en crudo"**, hablamos con MissionControl.
Y MissionControl solo reconoce una lista fija de mandos.

## 2. Lista de mandos soportados (carpeta `controllers/`)

8BitDo, Amazon, Atari, AtGames, Betop, Bionik, DualSense, DualShock 3, DualShock 4, GameSir,
**GameStick**, Gembox, Hyperkin, iCade, iPega, Lanshen, Mad Catz, Mocute, Nvidia Shield, Ouya,
PowerA, Razer, SteelSeries, Switch (oficial), Wii, Xiaomi…

No hay soporte "HID genérico": un gamepad HID estándar que no esté en la lista se queda en
`ControllerType_Unknown` y **no se attacha**.

## 3. Cómo identifica un mando (`controller_management.cpp`)

```cpp
constexpr u8 DeviceClassMajorPeripheral = 0x05;
constexpr u8 DeviceClassMinorGamepad    = 0x08;
constexpr u8 DeviceClassMinorJoystick   = 0x04;
constexpr u8 DeviceClassMinorKeyboard   = 0x40;

bool IsAllowedDeviceClass(const bluetooth::DeviceClass *cod) {
    return ((cod->class_of_device[1] & 0x0f) == DeviceClassMajorPeripheral) &&
           (((cod->class_of_device[2] & 0x0f) == DeviceClassMinorGamepad) ||
            ((cod->class_of_device[2] & 0x0f) == DeviceClassMinorJoystick) ||
            ((cod->class_of_device[2] & 0x40) == DeviceClassMinorKeyboard));
}
```

Y luego, por VID/PID:

```cpp
for (auto hwId : GamestickController::hardware_ids) {
    if ((device->vid == hwId.vid) && (device->pid == hwId.pid))
        return ControllerType_Gamestick;
}
// ...
static constexpr const HardwareID hardware_ids[] = {
    {0x0f0d, 0x1011}          // GameStick
};
```

El `vid`/`pid` no salen del descriptor HID: salen de

```cpp
btdrvGetPairedDeviceInfo(address, &device_settings);   // vid/pid del registro SDP Device ID
```

Es decir: **el VID/PID que la consola guardó al emparejar**, que proviene del registro SDP
`Device ID` (clase `0x1200`) del dispositivo.

### Requisitos deducidos

| Requisito | Valor que publicaremos |
|---|---|
| CoD | `0x000508` (periférico `0x05`, gamepad `0x08`) |
| SDP Device ID (VID) | `0x0f0d` (GameStick) |
| SDP Device ID (PID) | `0x1011` |
| Perfil SDP | HID `0x1124` con L2CAP PSM `0x11` + `0x13` |
| Reports | formato GameStick (`0x03` y `0x01`) |

> Nota: `0x0f0d` es el Vendor ID de **Mad Catz** (dueño de GameStick). MissionControl no verifica
> que seamos ese fabricante; solo compara números. No se está suplantando ningún dispositivo real
> en la red: es un identificador en nuestro propio SDP.

## 4. Formato de reports del GameStick (`gamestick_controller.hpp/.cpp`)

```cpp
struct GamestickInputReport0x03 {
    DirectionalPadType<0, 0xF> dpad;     // 1 byte, nibble bajo = dirección
    AnalogStick<u8> left_stick;          // X, Y  (0..255, centro 128)
    AnalogStick<u8> right_stick;         // X, Y
    u8 _unk0[2];

    struct {
        u8 A : 1; u8 B : 1; u8 : 1; u8 X : 1; u8 Y : 1; u8 : 1;
        u8 L : 1; u8 R : 1;
        u8 : 3; u8 start : 1; u8 : 1; u8 lstick_press : 1; u8 rstick_press : 1; u8 : 1;
    } buttons;
} PACKED;                              // 10 bytes (id incluido: 11 con el report id)
```

```cpp
struct GamestickInputReport0x01 {
    u8 _unk0;
    struct { u8 : 4; u8 home : 1; u8 back : 1; u8 : 0; } buttons;
    u8 _unk1[6];
} PACKED;
```

Mapeo que hace MissionControl (¡ojo a los intercambios!):

| Report GameStick | Botón Switch |
|---|---|
| `buttons.B` | **A** |
| `buttons.A` | **B** |
| `buttons.Y` | **X** |
| `buttons.X` | **Y** |
| `buttons.L` / `buttons.R` | L / R |
| `buttons.start` | **Plus** |
| `input0x01.buttons.back` | **Minus** |
| `input0x01.buttons.home` | **Home** |
| `buttons.lstick_press` / `rstick_press` | Stick L / R |
| `dpad` (nibble bajo) | D-pad |
| — | **ZL / ZR se emulan**: si `Down` está pulsado **y** `L`/`R`, giran a `ZL`/`ZR` |

El último punto es un truco del propio MissionControl: no hay bits ZL/ZR en el report.

## 5. Consecuencia de diseño

**Clonar el contrato GameStick**, no inventar uno propio:

- CoD `0x000508`, VID/PID `0x0f0d:0x1011`.
- Report `0x03` de 10 bytes para sticks/botones, report `0x01` para Home/Minus.
- Los sticks se envían como `u8` 0..255 y MissionControl invierte Y (`GetYInverted()`).
- No hace falta report `0x02` ni LEDs.

### El descriptor HID no lo lee MissionControl

`grep -ri descriptor` sobre `mc_mitm/source` solo encuentra `FillGenericInputReport`, que rellena
**su propio** report de Switch. MissionControl nunca parsea nuestro descriptor HID: le bastan los
bytes. Esto permite **un solo descriptor** que sirva a la vez para:

- **Switch/MissionControl**: solo importan los bytes de los reports `0x01` y `0x03`.
- **PC (Windows/Linux/macOS)**: importa el descriptor, que declararemos con exactamente esos campos
  (gamepad de 10 bytes + un report de sistema para Home/Minus). Windows lo verá como
  "HID-compliant game controller" y los ejes/botones funcionarán sin driver.

## 6. Plan B si MissionControl no acepta

1. **Parchear MissionControl** y compilarlo (requiere devkitPro; se compila fuera de este PC,
   que no admite instalaciones). El parche es pequeño:
   - añadir `{0x0f0d, 0x1011}` ya existe; para un VID/PID propio, añadir una entrada nueva
     `{0xXXXX, 0xYYYY}` en `hardware_ids` de un controlador existente (GameStick) o crear
     `XperiaPadController` que herede de `GamestickController`.
   - no hay que tocar el resto del sysmodule.
2. **Abrir un issue/PR en `ndeadly/MissionControl`** con el CoD, VID/PID y el formato exacto de
   reports de este proyecto. Es la vía limpia si el proyecto se publica.
3. **PC como objetivo primario**: en PC no hay ninguna lista blanca; cualquier HID gamepad con
   descriptor válido funciona. La Switch es un extra.

## 7. Preguntas abiertas (necesitan consola)

| Pregunta | Cómo se resuelve |
|---|---|
| ¿La consola lee el Device ID de nuestro registro HID o del registro `0x1200` del sistema? | probar con la Switch; si falla, borrar `0x10000` y publicar el nuestro (ya probado que se puede, ver `03-bluetooth-y-sdp.md` §5) |
| ¿Exige cifrado del enlace? | `btmon` en el PC no sirve; hace falta ver el log de la consola (MissionControl tiene logs) |
| ¿Acepta `0x01`/`0x03` sin `0x02`? | el código de GameStick solo usa `0x01` y `0x03`; debería |
| ¿Cuánto tarda en aparecer como mando? | el `btm` de la consola reintenta; medir |

## 8. Implementación (E27, 0.63-dev)

**Contrato byte-exact confirmado desde la referencia local** (`gamestick_controller.hpp/.cpp`, `switch_directional_pad.hpp`, `switch_analog_stick.hpp`):

- **Report `0x03`** (10 bytes de data en el wire: 9 del struct + 1 pad; MC ignora el tamaño):
  - `[0]` d-pad nibble: **0=Up, 1=NE, 2=East, 3=SE, 4=South, 5=SW, 6=West, 7=NW, 0xF=idle** (enum de `DirectionalPad<0, 0xF>`).
  - `[1..2]` stick izq X/Y, `[3..4]` stick der X/Y (u8, centro 128). **MC invierte Y** (`GetYInverted` = `~y`) → el app envía `~r3[2]`/`~r3[4]` (nuestro web usa up=0; Switch espera up=alto). A validar en consola.
  - `[5..6]` sin uso (0).
  - `[7]` bits (LSB→): 0=A, 1=B, 3=X, 4=Y, 6=L, 7=R. `[8]` bit3=start, bit5=lstick_press, bit6=rstick_press.
- **Report `0x01`** (8 bytes de data): `[1]` bit4=home, bit5=back (Minus). El resto sin uso.
- **ZL/ZR**: no existen en el report; MC los emula con **Down+L / Down+R** (y limpia Down). El app genera el combo automáticamente con LT/RT físicos (`buildGamestickReport` fuerza d-pad=South al pulsar LT/RT).

**Implementado:**

- **Daemon** (`xpd.c`, `sdp_register.c`): comando UDS **`MODE generic|ds3`** que cambia la identidad del registro SDP Device ID (`0001:0001` ↔ `054c:0268`) y re-registra SDP+CoD/EIR al vuelo. El host guarda el VID/PID **al emparejar**: si el modo cambia después del bonding, hay que borrar el host en la consola y re-emparejar. (Antes `gamestick`; ver nota de estado arriba.)
- **App** (`MainActivity`): protocolo global `active_protocol` (`generic`/`ds3`), **independiente** de la base de mapeo del perfil; al conectar manda `MODE`; `sendReportState()` emite el formato según el protocolo; **pre-compensación** de los intercambios de MC (física A→bit B, B→A, X→Y, Y→X: el usuario siente A=A en la Switch); Guide→home, Select→back vía report `0x01` (`r1`).

**Pendiente de consola real:** polaridad Y de sticks (verificar arriba=arriba), aparición del mando tras Change Grip/Order, y las preguntas abiertas del §7.
