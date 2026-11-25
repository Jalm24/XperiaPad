<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 07 — Descargas y herramientas portables

> Nada de esto se instala en el PC. Todo vive en `download/` y se ejecuta desde ahí o desde `/tmp`.
> Los binarios ARM se empujan a `/data/local/tmp` cuando hace falta y se borran al terminar.

## 1. Inventario

| Ruta | Tamaño | Para qué |
|---|---|---|
| `download/reference/bluez-4.69.tar.gz` (+ extraído) | 1 MB / 5 MB | Stack BlueZ 4.69 de referencia: `lib/sdp.c`, `tools/sdptool.c` (tiene el builder de HID) |
| `download/reference/aosp-bluez-gingerbread.tar.gz` (+ extraído) | 700 KB / 4 MB | BlueZ **tal como lo usa Android 2.3**: es la fuente de verdad de `SDP_UNIX_PATH = /dev/socket/bluetooth` |
| `download/reference/MissionControl.tar.gz` (+ extraído) | 120 KB / 1.5 MB | Sysmodule de Switch: lista de mandos, formatos de report, lógica de identificación |
| `download/reference/btstack.tar.gz` (+ extraído) | 64 MB / 225 MB | Implementación de referencia de HIDP device + SDP (para no reinventar el estado) |
| `download/reference/linux-2.6.32-net-bluetooth-l2cap.c` | 92 KB | Prueba del `capable(CAP_NET_BIND_SERVICE)` en `l2cap_sock_bind` |
| `download/kernel/Xperia-2011-Kernel-2.6.32.9-DooMLoRD.tar.gz` (+ extraído) | 84 MB / 448 MB | Kernel de referencia: driver del touchpad, `defconfig`, `keypad-zeus` |
| `download/tools/busybox-armv7l` | 1.1 MB | Busybox estático ARMv7; probado en el teléfono (`ls`, `grep`, etc.) |
| `download/tools/l2probe/` | 24 KB | Sonda L2CAP (`bind`/`listen`/`accept`) + fuente |
| `download/tools/btprobe/` | 28 KB | Sonda HCI + L2CAP: lee/escribe CoD, bindea `0x11` y `0x13` + fuente |
| `download/tools/btinfo/` | 28 KB | Lee/escribe la clase con las funciones de BlueZ (validado) + fuente |
| `download/tools/sdptool69/` | 712 KB | `sdptool` de BlueZ 4.69 compilado para ARMv7 con `SDP_UNIX_PATH` parcheado a `/dev/socket/bluetooth` + árbol `src-jni` |
| `download/logs/` | 68 KB | Evidencia: `getevent -p`, `getprop`, `init.rc`, `init.semc.rc`, keylayouts |

## 2. SHA-256

```
d474e35633c1582e8562acf305c37c4a53b6948cb0222d22857c2c68b5868b83  reference/aosp-bluez-gingerbread.tar.gz
b8d26e5023a61337e8d0c6484cd8479c1c97d954a32d4b3309b39ae139fbf120  reference/bluez-4.69.tar.gz
d04436969bd92626d35505c8bff42f72a2e5a306bc828adce53345d727d6feda  reference/btstack.tar.gz
6fdd074044abdb9a681e47ae84c0ffe184c0e378a9a942c3b0f275af085b4505  reference/MissionControl.tar.gz
9b6241c6251908596681bddd354a30fa1d8106d09fc75927c42cc00a7607fc44  reference/linux-2.6.32-net-bluetooth-l2cap.c
8fceda57d83418bb288de0aef3c6df5e9421344ed0515c90fa5206f15c50f7e4  tools/busybox-armv7l
7a2855c18e39541592d4dc343030779e976b2f7f6680bb76ac713703bb3d4352  tools/l2probe/l2probe.c
176cbb030d641f74675a44b05414a33f3d056e8f5668de2abbe6f7618e1a83be  tools/l2probe/l2probe-armeabi-v7a
3839798b99f057fee7946a918b925e408bf93b2f51543cabf131a29882dba9b0  tools/btprobe/btprobe.c
3e2e1ca72618c8bc529aee2b0af907ad65d8384dfbca4bd317abfe6247eba67f  tools/btprobe/btprobe-armeabi-v7a
459b4c5e1dea4ababf05a10f9bdd6b5b88546087e03996710b08b915afea2964  tools/btinfo/btinfo.c
505b6051b456b3327517d1e399128059600cc9feda7d4a0387696c6f24b5a32f  tools/btinfo/btinfo-armeabi-v7a
1e73f7e6297d6d5c6c7e535b2f9e670633a078b0efa95320e171c22a88ff0511  tools/sdptool69/sdptool69
5900b81d43663c9ba0475d62d790a85fa1c9600e96eaa189fb1635b951aa5364  kernel/Xperia-2011-Kernel-2.6.32.9-DooMLoRD.tar.gz
```

## 3. Origen de cada descarga

| Recurso | URL |
|---|---|
| BlueZ 4.69 | `https://mirrors.edge.kernel.org/pub/linux/bluetooth/bluez-4.69.tar.gz` |
| AOSP BlueZ gingerbread | `https://android.googlesource.com/platform/external/bluetooth/bluez/+archive/refs/heads/gingerbread.tar.gz` |
| MissionControl | `https://codeload.github.com/ndeadly/MissionControl/tar.gz/refs/heads/master` |
| BTstack | `https://codeload.github.com/bluekitchen/btstack/tar.gz/refs/heads/master` |
| Kernel DooMLoRD | `https://codeload.github.com/DooMLoRD/Xperia-2011-Kernel-2.6.32.9/tar.gz/refs/heads/master` |
| Linux 2.6.32 `l2cap.c` | `https://raw.githubusercontent.com/torvalds/linux/v2.6.32/net/bluetooth/l2cap.c` |
| BusyBox 1.21.1 ARMv7 | `https://busybox.net/downloads/binaries/1.21.1/busybox-armv7l` |

## 4. Cómo se recompilan los binarios ARM

Toolchain usada: **NDK r16b** (`/home/jalm/Android/Sdk/ndk/16.1.4479499`).
El truco para Android 2.3 (API 10): `APP_PLATFORM := android-14` + **`APP_PIE := false`**.
El NDK r16 no trae sysroots de API ≤ 13, pero un ejecutable no-PIE de API 14 corre sin problema
en 2.3.4 (enlaza contra `libc.so` del sistema por `NEEDED`).

```bash
NDK=/home/jalm/Android/Sdk/ndk/16.1.4479499

# sonda L2CAP
$NDK/ndk-build NDK_PROJECT_PATH=/tmp/opencode/probe \
  APP_BUILD_SCRIPT=/tmp/opencode/probe/jni/Android.mk \
  NDK_APPLICATION_MK=/tmp/opencode/probe/jni/Application.mk

# sdptool con el socket SDP de Android
# (en el árbol: sed -i 's|/var/run/sdp|/dev/socket/bluetooth|' lib/sdp.h)
$NDK/ndk-build NDK_PROJECT_PATH=/tmp/opencode/sdptool69 \
  APP_BUILD_SCRIPT=/tmp/opencode/sdptool69/jni/Android.mk \
  NDK_APPLICATION_MK=/tmp/opencode/sdptool69/jni/Application.mk
```

`download/tools/sdptool69/src-jni/` contiene todos los fuentes, cabeceras y `Android.mk`/`Application.mk`
necesarios: se puede reconstruir sin volver a descargar nada.

Para comprobar que un binario es compatible:

```bash
file binario            # debe decir: ELF 32-bit LSB executable, ARM, EABI5
readelf -d binario | grep NEEDED   # libc.so, libdl.so, libm.so
```

## 5. Uso en el teléfono (temporal, se borra)

```bash
A=/home/jalm/Android/Sdk/platform-tools/adb
$A push download/tools/sdptool69/sdptool69 /data/local/tmp/sdptool69
$A shell chmod 755 /data/local/tmp/sdptool69
$A shell 'su -c "/data/local/tmp/sdptool69 browse local"'
```

Limpieza al terminar:

```bash
$A shell 'su -c "rm -f /data/local/tmp/sdptool69 /data/local/tmp/btprobe /data/local/tmp/btinfo /data/local/tmp/l2probe /data/local/tmp/busybox"'
```

## 6. Lo que NO se descargó (y por qué)

| Cosa | Motivo |
|---|---|
| NDK r10e (1.1 GB) | innecesario: API 14 no-PIE funciona |
| `bluez-deprecated-tools` (Arch `extra`) | **instalar está prohibido**; se compiló `sdptool` a mano |
| SDK completo / Gradle | el proyecto se construye con `aapt` + `javac` + `dx` + `jarsigner`, ya presentes |
| Emulador / imagen de Switch | no aplica |
| Cualquier APK de terceros | no hace falta |
