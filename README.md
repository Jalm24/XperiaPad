<!-- Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later
Additional GPLv3 §7(b): preserve this attribution in modified copies. -->

# XperiaPad

Convierte un **Sony Xperia Play R800i** en un gamepad HID Bluetooth para PC. La app Android mapea botones, gestiona perfiles y controla el daemon nativo.

La app **no exige una ROM, un kernel ni una versión de Android concretas**: verifica el stack que realmente consume y avisa si difiere de la referencia, pero nunca bloquea.

**Estado:** PC verificado (`/dev/input/js0`, 16 botones). Switch vía MissionControl: **experimental**.

## Empezar

1. [Inicio rápido (ES)](docs/00-inicio-rapido.md) · [Quick start (EN)](docs/00-quickstart-en.md)
2. [Índice de documentación](docs/README.md)
3. [Changelog](CHANGELOG.md)

## Compilar

```bash
bash src/build.sh          # debug
bash src/build-release.sh  # release firmado (requiere clave fuera del repo)
```

La clave de firma se configura con `XPD_KEYSTORE`, `XPD_STOREPASS`, `XPD_KEYPASS` y `XPD_KEYALIAS`, o en `~/.config/XperiaPad/release.env`. No se versiona.

## Requisitos reales (stack, no versión)

- **Root** (`su`): capabilities HCI/L2CAP y bind en PSM < 0x1000.
- **Stack Bluetooth:** D-Bus en `/dev/socket/dbus` (`org.bluez`), SDP en `/dev/socket/bluetooth`, y plugin `input` desactivable mediante `DisablePlugins = input` en `/etc/bluetooth/main.conf` (**BT-Patch**).
- **L2CAP libre** en PSM `0x11` / `0x13` (HIDP control/interrupt).
- **Entrada Android:** eventos con `SOURCE_TOUCHPAD` (`0x100008`) y los keycodes del mando.
- **ABI:** binario `armeabi-v7a` (`libxpd.so`).

La configuración de referencia es RetroActive `4.0.2.A.0.62 FULL` (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). Si algo difiere, la app lo detecta y avisa **“puede fallar horriblemente”**, pero **no bloquea**: puedes intentarlo igual. Root sigue siendo necesario para aplicar el parche y arrancar el daemon.

## Límites

- Sin permiso `INTERNET`. El daemon se apaga solo tras 120 s sin cliente real.
- El control del daemon está protegido por permiso Android `signature`.
- Las identidades son `0001:0001` (PC) y `054c:0268` (Switch/DS3, experimental).

## Verificar el APK

Certificado de firma release — SHA-256:
`e5a260aa31d71d96af5bacd2ab00974e8820b708bca0b150a8e7e5ff5c0b29e3`

```bash
$HOME/Android/Sdk/build-tools/25.0.3/apksigner verify --print-certs XperiaPad-release.apk
```

## Licencia

GPLv3. Material BlueZ conserva sus cabeceras GPLv2-or-later originales; ver [LICENSE](LICENSE), [NOTICE](NOTICE), [CREDITS.md](CREDITS.md) y [CREDITS-en.md](CREDITS-en.md).
