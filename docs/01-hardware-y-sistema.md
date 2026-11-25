<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 01 — Hardware y sistema base

> Todo lo de este documento está **verificado en el dispositivo** (R800i conectado por adb) el 2026-11-15, salvo lo marcado como *(fuente)*.
> Evidencia cruda en `download/logs/` y `download/kernel/`.

## 1. Identificación

| Dato | Valor |
|---|---|
| Modelo | `R800i` — Xperia™ PLAY (GENERIC) |
| Plataforma | `zeus` (Sony Ericsson), SoC MSM8255 / familia `msm7x30` |
| Android | 2.3.4 Gingerbread, API 10 |
| Build | `4.0.2.A.0.79`, incremental `sP_v3w` |
| Fingerprint | `SEMC/R800i_1247-8563/R800i:2.3.4/4.0.2.A.0.79/sP_v3w:user/release-keys` |
| Kernel | `Linux 2.6.32.9-perf` — `SEMCUser@SEMCHost`, gcc 4.4.3, `#1 PREEMPT Mon Feb 27 12:53:42 2012` |
| CPU | ARMv7 rev1 (`v7l`), Cortex-A8 "Scorpion", `swp half thumb fastmult vfp edsp neon vfpv3` |
| RAM | 376 MB (`MemTotal: 376024 kB`) |
| MAC Bluetooth | `8C:64:22:9B:17:15` (desde `/proc/bt_mac_addr`) |

**Consecuencia práctica:** ABI `armeabi-v7a` con NEON disponible, `-mfloat-abi=softfp`.
El kernel es el **stock de Sony**, no un kernel de comunidad.

## 2. Root y permisos

| Dato | Valor |
|---|---|
| `su` | `/system/xbin/su`, setuid root, 79 460 bytes |
| `ro.secure` | `1` (adb shell **no** es root por defecto) |
| `ro.debuggable` | `0` |
| SELinux | **no existe** (llega en Android 4.3+) |
| `adb shell su -c id` | `uid=0(root) gid=0(root)` ✅ |

Root disponible de forma efectiva, sin SELinux ni `capset` restrictivo: un proceso lanzado con `su` obtiene
`CapEff: fffffffffffffeff` (todas las capacidades menos una).

## 3. Almacenamiento y particiones

| Partición | Montaje | FS | Tamaño | Libre |
|---|---|---|---|---|
| `/dev/block/mtdblock0` | `/system` | yaffs2 `ro` | 312 MB | 141 MB |
| `/dev/block/mtdblock3` | `/data` | yaffs2 `rw,nosuid,nodev` | 380 MB | **50 MB** |
| — | `/cache` | — | 101 MB | 100 MB |

**Consecuencia:** `/system` es de solo lectura por defecto (se puede remontar con root, pero **el diseño no lo
hace**: ver `05-arquitectura.md`). Los 50 MB libres de `/data` son suficientes para el APK + daemon (< 1 MB).

## 4. Nodos de dispositivo relevantes

| Nodo | Estado | Nota |
|---|---|---|
| `/dev/input/event0..10` | presentes | grupo `input`, modo `660` |
| `/dev/uinput` | **presente** (`10,223`, `660 bluetooth`) | permite inyectar eventos de teclado |
| `/dev/uhid` | **ausente** | llegó en Linux 3.5; no aplica |
| `/dev/ttyHS0` | `242,0`, `600 bluetooth` | UART del chip Bluetooth (BCM4329) |
| `/dev/socket/dbus` | `660 bluetooth` | bus de sistema de D-Bus, con `dbus-daemon` corriendo |
| `/dev/socket/bluetooth` | `660 bluetooth` | **socket de control del servidor SDP de `bluetoothd`** (`ANDROID_SOCKET_DIR "/bluetooth"`); permite añadir/borrar registros en caliente |

## 5. Herramientas ya presentes en el ROM

`bluetoothd`, `sdptool`, `hciattach`, `brcm_patchram_plus`, `getevent`, `iptables`, `dbus-daemon`.

También están las librerías que necesita un daemon sin dependencias extra:
`/system/lib/libbluetooth.so` (cliente SDP/HCI) y `/system/lib/libdbus.so` (cliente D-Bus).

> `sdptool` del ROM sirve para *consultar*, pero su comando `add HID` está sin implementar
> (`add == NULL` en la tabla de servicios). Por eso se compiló el `sdptool` de BlueZ 4.69
> (`download/tools/sdptool69`), que sí construye un registro HID. Ver `03-bluetooth-y-sdp.md` §5.

**No** están: `hciconfig`, `hcitool`, `grep`, `head`, `uname`, `busybox`, `nc`, `strace`.
El `toolbox` de Gingerbread es muy limitado.

**Solución:** `download/tools/busybox-armv7l` (busybox estático, probado en el teléfono). No se instala:
se empuja a `/data/local/tmp/` cuando hace falta y se borra al terminar.

## 6. Limitaciones del entorno que condicionan el proyecto

1. Sin `grep`/`head` en el ROM → cualquier diagnóstico se hace filtrando en el host (`adb shell cmd > archivo`).
2. `/system` en yaffs2 y `ro` → escribir ahí sería persistente y difícil de revertir (motivo para descartar la
   variante "plugin en `/system`", ver `03-bluetooth-y-sdp.md` §7).
3. Solo 50 MB libres en `/data` → el binario nativo debe ser pequeño y sin dependencias pesadas.
4. Android 2.3 no tiene SELinux ni `capset` por app: con root se tienen todas las capacidades.
5. Android solo es **descubrible 120 s** por defecto → la app necesita un botón de "modo emparejamiento" que
   rearme la ventana (APIs `SCAN_MODE_CONNECTABLE_DISCOVERABLE` + `EXTRA_DISCOVERABLE_DURATION`).
