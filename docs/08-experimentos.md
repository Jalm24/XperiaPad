<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 08 — Cuaderno de experimentos (evidencia cruda)

> Fecha: 2026-11-15. Dispositivo: Xperia PLAY R800i (`8C:64:22:9B:17:15`), Android 2.3.4,
> kernel 2.6.32.9 stock, root por `su`.
> Host: PC con `hci0` (`78:2B:46:4E:0A:24`), BlueZ 5.87.
> Cada experimento dice **qué se quería saber**, **qué se hizo** y **qué se concluye**.
> Todo lo que se añadió al teléfono vive en `/data/local/tmp` y se puede borrar.

---

## E1 — ¿Se puede servir un PSM de HID sin root?

**Hipótesis:** el kernel exige `CAP_NET_BIND_SERVICE` para PSMs < `0x1001`.

Herramienta: `download/tools/l2probe` (compilada con NDK r16, `APP_PLATFORM=android-14`, `APP_PIE=false`).

```bash
adb push l2probe /data/local/tmp/l2probe && adb shell chmod 755 /data/local/tmp/l2probe
adb shell /data/local/tmp/l2probe 0x11
```

Salida:

```
psm=0x0011
Uid:    2000    2000    2000    2000
Gid:    2000    2000    2000    2000
CapPrm: 0000000000000000
CapEff: 0000000000000000
socket: ok
bind: FAIL errno=13 (Permission denied)
```

**Conclusión:** sin root, `EACCES` en `0x11`.

---

## E2 — Control: un PSM alto sí se puede sin root

```bash
adb shell /data/local/tmp/l2probe 0x1001
```

```
psm=0x1001
Uid: 2000 ... CapEff: 0000000000000000
socket: ok
bind: ok
listen: ok -> esperando conexion 20s...
```

**Conclusión:** no es un problema de permisos del dispositivo `/dev/input` ni del socket en general:
es la comprobación de PSM del kernel (`l2cap_sock_bind`, línea 873 de `l2cap.c` 2.6.32).

---

## E3 — Con root, `0x11` funciona

```bash
adb shell "su -c '/data/local/tmp/l2probe 0x11'"
```

```
psm=0x0011
Uid: 0 0 0 0
CapPrm: fffffffffffffeff
CapEff: fffffffffffffeff
socket: ok
bind: ok
listen: ok -> esperando conexion 20s...
```

---

## E4 — El PC se conecta al PSM `0x11` del teléfono

Con la sonda de E3 escuchando, en el PC:

```python
import socket
s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_SEQPACKET, socket.BTPROTO_L2CAP)
s.settimeout(6)
s.connect(("8C:64:22:9B:17:15", 0x11))
print("PC -> PSM 0x11: CONECTADO")
```

```
PC -> PSM 0x11: CONECTADO
```

**Conclusión (E1-E4):** el kernel hace de servidor L2CAP de userspace, el PC puede alcanzarlo, y
el único obstáculo es el `capable()`. Root obligatorio, pero suficiente.

---

## E5 — `sdptool add HID` del ROM: no hace nada

```bash
adb shell 'su -c "sdptool add --channel=17 --handle=0x20000 HID"'
adb shell 'su -c "sdptool browse local"'   # sin registros nuevos
```

Causa, en el fuente de AOSP gingerbread (`tools/sdptool.c`):

```c
{ "HID", HID_SVCLASS_ID, NULL },     /* add == NULL → add_service() devuelve -1 sin hacer nada */
```

**Conclusión:** la vía "usar la herramienta del sistema" no existe: hay que construir el registro y
registrarlo nosotros. (Esto es lo que había hecho descartar la Variante D.)

---

## E6 — ¿Acepta `bluetoothd` registros SDP nuevos en caliente?

Se compiló el `sdptool` de AOSP gingerbread para ARMv7 (define
`SDP_UNIX_PATH = ANDROID_SOCKET_DIR "/bluetooth"`), y se registró un servicio SP:

```bash
adb shell 'su -c "/data/local/tmp/sdptool add --channel=9 SP"'
```

```
Serial Port service registered
rc=0
```

```bash
adb shell 'su -c "/data/local/tmp/sdptool browse local"' | grep -A8 'Serial Port'
```

```
Service Name: Serial Port
Service Record Handle: 0x10007
Service Class ID List: "Serial Port" (0x1101)
Protocol Descriptor List: L2CAP (0x0100), RFCOMM (0x0003) Channel: 9
```

**Conclusión:** **sí.** El servidor SDP de `bluetoothd` acepta `SVC_ADD_RECORD` por
`/dev/socket/bluetooth`. Es el hallazgo que habilita la Variante F.

---

## E7 — Registro HID completo (perfil `0x1124`, PSM 17)

Se compiló el `sdptool` de **BlueZ 4.69** (que sí tiene `add_hid_keyb`) con `lib/sdp.h` parcheado:

```bash
sed -i 's|#define SDP_UNIX_PATH "/var/run/sdp"|#define SDP_UNIX_PATH "/dev/socket/bluetooth"|' lib/sdp.h
```

```bash
adb shell 'su -c "/data/local/tmp/sdptool69 add --handle=0x20000 --channel=17 KEYB"'
```

```
HID keyboard service registered
rc=0
```

```bash
adb shell 'su -c "/data/local/tmp/sdptool69 browse local"'
```

```
Service Name: HID Keyboard
Service RecHandle: 0x20000
Service Class ID List:
  "Human Interface Device" (0x1124)
Protocol Descriptor List:
  "L2CAP" (0x0100)
    PSM: 17
  "HIDP" (0x0011)
Profile Descriptor List:
  "Human Interface Device" (0x1124)
    Version: 0x0100
```

**Conclusión:** se puede publicar un registro HID con PSM `0x11` sin tocar `/system` ni reiniciar nada.

---

## E8 — ¿Se puede borrar el registro PnP del sistema?

```bash
adb shell 'su -c "/data/local/tmp/sdptool69 del 0x10000"'
```

```
Service Record deleted.
rc=0
```

```bash
adb shell 'su -c "/data/local/tmp/sdptool69 browse local"' | grep -c 'PnP Information'
0
```

**Conclusión:** sí. Útil si la Switch lee el Device ID equivocado (el de Android en vez del nuestro).

---

## E9 — ¿Los registros añadidos persisten?

Comprobación 1: el fichero de estado del adaptador no cambia.

```bash
adb shell 'su -c "ls -l /data/misc/bluetoothd/8C:64:22:9B:17:15/sdp"'
→ -rw-r--r-- bluetooth bluetooth 8540 2026-10-23 23:24 sdp
```

Ese fichero es una **caché de registros de dispositivos remotos** (contiene líneas
`78:2B:46:4E:0A:24#00010001 <hex>`, es decir, los servicios del PC), no los locales.
Copia en `download/logs/experimentos/bluetoothd-sdp-cache.bin`.

Comprobación 2: reinicio del servicio.

```bash
adb shell 'su -c "stop bluetoothd; sleep 2; start bluetoothd"'
adb shell 'su -c "/data/local/tmp/sdptool69 browse local"' | grep -c '^Service Name'   # 0 al principio
# a los pocos segundos, los plugins del sistema republican:
# 0x10000 PnP, 0x10001 Audio Source, 0x10002 AVRCP TG, 0x10003/4 Voice Gateway, 0x10005/6 OBEX
```

Nuestros `0x20000` (HID) y `0x10007` (SP) **desaparecieron**; el PnP del sistema volvió a `0x10000`.

**Conclusión:** los registros son estado en RAM del proceso `bluetoothd`. No hay persistencia, no hay
archivos nuevos, y un reinicio del stack deja el teléfono exactamente como estaba.

---

## E10 — Clase de dispositivo por HCI crudo

Herramienta: `download/tools/btprobe` (socket `AF_BLUETOOTH/SOCK_RAW`, `HCI_FILTER` con todas las
máscaras, `Write_Class_of_Device` OGF `0x03` OCF `0x0024`).

```
hci0 raw: ok
-- Read_BD_ADDR (ogf 0x04 ocf 0x0009)
  raw(13): 04 0e 0a 01 09 10 00 15 17 9b 22 64 8c
   bdaddr = 64:22:9b:17:15:00        ← 8C:64:22:9B:17:15 (little-endian)
-- Read_Class_of_Device
  raw(8): 04 0e 05 01 09 0c 00 00    ← respuesta truncada a 2 bytes de clase
-- Write_Class_of_Device 0x000508
  raw(7): 04 0e 04 01 24 0c 00       ← status 0x00 = aceptado
-- L2CAP binds
   psm 0x11 bind: ok
   psm 0x13 bind: ok
```

Nota: la lectura por comando crudo devolvía el paquete truncado (8 bytes en vez de 9), por eso se
verificó con la librería de BlueZ (E11), que hace el parseo correcto.

**Conclusión:** se puede cambiar el CoD con el stack vivo, y los PSMs `0x11` **y** `0x13` están libres.

---

## E11 — Verificación del CoD con las funciones de BlueZ

Herramienta: `download/tools/btinfo` (usa `lib/hci.c` de BlueZ 4.69 compilado dentro).

```bash
adb shell 'su -c "/data/local/tmp/btinfo set"'
```

```
hci0 name=hci0 bdaddr=15:17:9B:22:64:8C flags=0x0000000d
class actual=0x58020c
write class 0x000508: CC ok
controller class=0x000508        ← releído del controlador
```

Después, sin argumentos:

```
class actual=0x58020c            ← restaurado correctamente
```

**Conclusión:** `Write_Class_of_Device` funciona y es reversible. La clase del teléfono quedó como estaba.

---

## E12 — ¿Hay agente de emparejamiento disponible?

Comprobaciones (sin modificar nada):

```bash
adb shell 'su -c "ls /system/lib/libdbus.so /system/lib/libbluetooth.so"'
→ ambos presentes
adb shell 'su -c "cat /system/etc/dbus.conf"' | head
→ <policy context="default"> <allow send_interface="*"/> ... <allow own="*"/> ...
adb shell 'su -c "ls -l /dev/socket/dbus"'
→ 660 bluetooth bluetooth
```

**Conclusión parcial:** un daemon root puede hablar D-Bus y registrar un `org.bluez.Agent`.
**Pendiente:** compilar un cliente D-Bus mínimo y probar `RegisterAgent` + auto-aceptar.

---

## E13 — Toolchain: ¿corre un binario del NDK r16 en Android 2.3?

Las primeras compilaciones fallaron por sysroots inexistentes (`platforms/android-14` sin
`usr/include`) y por PIE. La combinación que funciona:

```
APP_ABI := armeabi-v7a
APP_PLATFORM := android-14
APP_PIE := false
```

Resultado:

```
ELF 32-bit LSB executable, ARM, EABI5 version 1 (SYSV), dynamically linked,
interpreter /system/bin/linker, for Android 14, built by NDK r16b
```

Ejecutado en el teléfono sin errores. **Conclusión:** no hace falta el NDK r10e (1.1 GB).

---

## E14 — ¿Persiste `bluetoothd` el CoD, y lo reimpone? (hallazgo H1)

**Hipótesis:** el CoD no vive solo en el controlador; `bluetoothd` lo guarda en disco y lo vuelve a
imponer al arrancar o al cambiar de estado.

Comprobación 1 — observada en el dispositivo (solo lectura):

```bash
adb shell 'su -c "cat /data/misc/bluetoothd/8C:64:22:9B:17:15/config"'
```

```
name Xperia PLAY
class 0x58020c
discovto 120
onmode discoverable
mode discoverable
```

Comprobación 2 — fuente (`download/reference/aosp-bluez-gingerbread`):

| Línea | Qué hace |
|---|---|
| `src/storage.c:246` | `write_local_class()` escribe `class 0x……` en `<mac>/config` |
| `src/adapter.c:2204-2215` | al arrancar: si hay `wanted_cod` en memoria lo usa; **si no**, `read_local_class()` lee el fichero |
| `src/adapter.c:872` | `adapter_set_class_complete()` → `write_local_class()` en **cada** cambio de clase aceptado |
| `src/adapter.c:209` | `adapter_set_service_classes()` → `wanted_cod = (wanted_cod & 0x00ffff) \| (svc << 16)` y lo escribe |
| `src/adapter.c:2722` | `adapter_update()` llama a `adapter_set_service_classes()` |

**Conclusión:** el CoD que escribimos por HCI **se persiste en disco** y el sistema puede
**reimponerlo**. El `config` del teléfono tiene además `mode discoverable` + `onmode discoverable`, así
que al arrancar `bluetoothd` vuelve a ponerse visible → `adapter_set_service_classes()` → CoD del
sistema. La reafirmación periódica **deja de ser opcional**: es obligatoria.

---

## E15 — ¿Se puede escribir `Class` por D-Bus? (hallazgo H2)

**Hipótesis:** existe un atajo `org.bluez.Adapter.SetProperty("Class", …)` que evitaría el HCI crudo.

Fuente: `src/adapter.c:1378` `set_property()` solo compara contra `Name`, `Powered`,
`Discoverable`, `DiscoverableTimeout`, `Pairable` y `PairableTimeout`. Cualquier otra propiedad cae en
`invalid_args(msg)`. `Class` solo se **emite** (`src/adapter.c:877`,
`emit_property_changed(..., "Class", ...)`), nunca se acepta como entrada.

**Conclusión:** no hay atajo. La única vía para fijar la clase es HCI
(`Write_Class_of_Device`, E10/E11).

---

## E16 — ¿Publica el sistema su propio Device ID? (hallazgo H3)

**Hipótesis:** Android publica un registro PnP/DI y un AD de EIR con VID/PID propios, y no los de Sony.

Evidencia en el dispositivo:

```bash
adb shell 'su -c "grep DeviceID /etc/bluetooth/main.conf"'
→ DeviceID = android:generic:1.5
```

Evidencia en el fuente:

| Línea | Qué hace |
|---|---|
| `src/hcid.h:68` | `char deviceid[15];` → 14 caracteres útiles |
| `src/main.c:188` | `strncpy(..., sizeof-1)` → `"android:generic:1.5"` (19) se trunca a `"android:generi"` |
| `src/main.c:497` | `start_sdp_server(mtu, main_opts.deviceid, SDP_SERVER_COMPAT)` |
| `src/sdpd-server.c:255-267` | `vid = strtol("android:generi", NULL, 16)` = **`0x000a`** (la `n` corta el hex); `pid = strtol("generi", NULL, 16)` = **`0x0000`**; sin segundo `:` → `ver = 0x0000`; `register_device_id(vid,pid,ver)` |
| `src/sdpd-service.c:342` | `register_device_id()` crea el registro `PNP_INFO_SVCLASS_ID` (`0x1200`) con attrs `0x0200`-`0x0205` → es el `0x10000` de E8 |
| `src/sdpd-service.c:208-219` | `create_ext_inquiry_response()` añade, si `did_vendor != 0`, un AD tipo **`0x10`** (Device ID) con `source 0x0002`, `vid`, `pid`, `ver` |
| `src/adapter.c:821` | `update_ext_inquiry_response()`, llamado desde `880`, `918`, `947` y `2733`: el EIR se **regenera** al cambiar nombre, clase, servicios o modo SSP. Solo escribe si `dev->ssp_mode > 0`; si no, escribe un EIR **vacío** |

**Observación en el PC (confirmación):** consultando la información que BlueZ del PC tiene cacheada
del teléfono emparejado:

```bash
bluetoothctl info 8C:64:22:9B:17:15
→ UUID: PnP Information (00001200-0000-1000-8000-00805f9b34fb)
→ Modalias: usb:v000Ap0000d0000
→ Class: 0x0058020c (5767692)
```

`Modalias: usb:v000Ap0000d0000` corresponde a **VID `0x000a`, PID `0x0000`**, exactamente los valores
predichos. (Intento de captura `btmon`/`Extended Inquiry Result` en vivo: no concluyente por problemas
de sesión de escaneo en el PC; la caché de BlueZ ya contenía el dato.)

**Conclusión (confirmada):** el sistema publica `VID 0x000a / PID 0x0000` vía PnP Information (SDP/EIR).
Por eso el Device ID propio no basta: hay que (1) borrar `0x10000` y (2) **reescribir el EIR** con
`Write_Extended_Inquiry_Response` (OGF `0x03`, OCF `0x0052`, ya presente en `lib/hci.c:2175`).

---

## E17 — Spike ruta B (traza en vivo HID) y reconocimiento ruta A (bloqueo de autorización HID)

**Contexto:** con `xpd` activo (pid 31829, `libxpd.so`, log `/data/data/com.jalm.xperiapad/files/xpd.log`), BT encendido, `xpd` en modo activo (SDP `0x20000` HID 0x1124 + `0x20001` DI `0x0f0d:0x1011`, escuchando L2CAP PSM `0x11/0x13`) y el PC (`78:2B:46:4E:0A:24`, BlueZ 5.87) enlazado/bonded al dispositivo (`8C:64:22:9B:17:15`), se forzó desde el PC la conexión del perfil HID para observar el recorrido.

**Prueba (PC, BlueZ 5.87):**

```bash
busctl call org.bluez /org/bluez/hci0/dev_8C_64_22_9B_17_15 \
  org.bluez.Device1 ConnectProfile s "00001124-0000-1000-8000-00805f9b34fb"
→ RC=0 (desde el lado del PC)
```

**Logcat del dispositivo (correlado, ~17:54:54):**

```
I/BluetoothEventLoop.cpp: agent_event_filter: Received method org.bluez.Agent:Authorize
I/BluetoothEventLoop:     Rejecting incoming 00001124-0000-1000-8000-00805f9b34fb connection from 78:2B:46:4E:0A:24
D/BluetoothEventLoop:     onAgentAuthorize(/org/bluez/31756/hci0/dev_78_2B_46_4E_0A_24, 00001124-...) = false
```

- `xpd.log`: **sin cambios** durante la ventana (sin `accept`/tráfico HIDP en PSM `0x11/0x13`). `sdptool browse local` seguía mostrando `0x20000/0x20001`.
- Efecto colateral observado (estado, no archivos): en el dispositivo cayeron Headset/A2DP/AVRCP en ese momento (Headset `STATE_DISCONNECTED`) aunque el **ACL y el bond se mantuvieron**; en el **PC**, BlueZ quedó mostrando el dispositivo como `Paired: no` / `Connected: no` (emparejamiento local perdido en el PC). No se modificaron archivos del sistema/dispositivo.

**Cadena causal (análisis):**

1. `bluetoothd` (BlueZ 4.69, pid 31756) tiene cargado el plugin **`/system/lib/bluez-plugin/input.so`**. En BlueZ 4.69 el servidor de entrada atiende HID en PSM 0x11/0x13 y, al recibir la conexión, solicita autorización vía `btd_request_authorization(..., HID_UUID, ...)` (`input/server.c`, `confirm_event_cb`), que llega al agente Java (`org.bluez.Agent:Authorize`).
2. `android.server.BluetoothEventLoop.onAgentAuthorize` (framework, AOSP gingerbread) guarda `nativeData` y **solo autoriza UUIDs de audio locales** (AudioSource/AvrcpTarget/AdvAudioDist); para el resto (HID 00001124) rechaza y responde negativamente (flujo A2DP/`setAuthorizationNative` con `false`), **sin ruta de aceptación para HID**.
3. Resultado: el gate de autorización del sistema rechaza HID **antes de entregar** la conexión; `xpd` no recibe `accept` en sus listeners L2CAP. (Propiedad exacta de listeners L2CAP no observable: no hay `/proc/net/seqpacket|l2cap` en este kernel 2.6.32; la evidencia funcional es concluyente.)

**Reconocimiento ruta A (solo lectura, sin cambios) — viabilidad de aceptar HID en memoria:**

- `system_server` (pid 247, Dalvik, `ro.debuggable=0`, sin `TracerPid`) con framework ODEX `/data/dalvik-cache/system@framework@framework.jar@classes.dex` (`dey 036`, ~7.7 MB, dex embebido) mapeado **`r--p`** en `2d7fb000-2dfa8000`. `libandroid_runtime.so` (mapeada) exporta `setAuthorizationNative` (JNI; la respuesta positiva válida requiere el `nativeData` de la petición).
- Herramientas en dispositivo: `busybox`, `btinfo/sdptool/l2probe/btprobe`; **no hay** `gdbserver`, `strace`, `dbus-send` ni utilidades de inyección/depuración.
- **Conclusión de viabilidad (con medios actuales):** no es viable de forma controlada (1) parchear la lógica en el ODEX mapeado por ser `r--p` y no haber mecanismo para cambiar permisos/escribir dentro del proceso (sin ptrace/inyección), (2) invocar `setAuthorizationNative` desde fuera de la VM/proceso, ni (3) alterar con seguridad estructuras internas de Dalvik quickened. Cualquier intento a ciegas implica alto riesgo de caída de `system_server`/Bluetooth.
- **Habilitador identificado para reintentar A (sin tocar `/system`):** NDK r16 incluye `gdbserver` ARM precompilado (`/home/jalm/Android/Sdk/ndk/16.1.4479499/prebuilt/android-arm/gdbserver/gdbserver`, ELF ARM EABI5, estático, ~583 KB, `GNU gdbserver 7.11`). Copiado temporalmente a `/data/local/tmp`, probado y **eliminado**: ejecuta, pero en este kernel (2.6.32) falla `Accept failed: Function not implemented` en modo TCP (`:port`/`--multi`) y el modo stdio no estableció sesión útil (adjuntar pausó/trazó brevemente `system_server`, recuperado a `S, TracerPid: 0`). **No utilizable para A** en este entorno.
- **Candidato alternativo evaluado (temporal, eliminado):** NDK r10e `prebuilt/android-arm/gdbserver` (`.../NecroReader/third_party/ndk-r10e/.../android-arm/gdbserver/gdbserver`, ELF ARM EABI5 estático, ~401 KB, 2015, `GNU gdbserver 7.7 arm-eabi-linux`). En dispositivo: `--version` OK y, en `--multi :port` (sin `--attach`), **acepta conexiones TCP** (`Remote debugging from host...`) sin el fallo de `accept()` observado en r16. No se probó `--attach` a `system_server` (riesgo de pausa/inestabilidad). **Viabilidad para ruta A:** más prometedor que r16 como herramienta de depuración compatible; cualquier uso debe ser temporal, controlado y con plan de recuperación.

**Impacto en F:** el flujo entrante de HID (00001124) queda bloqueado por el agente/framework del sistema en esta ROM; `xpd` no recibe la conexión aunque SDP/escucha sean correctos. Desbloquearlo dentro de las restricciones actuales requiere forzar la autorización HID en memoria (ruta A, riesgosa y dependiente de inyección/depuración compatible) o un cambio de enfoque fuera de F.

---

## E18 — Intento ruta A (A2, en memoria): parche amplio de autorización HID y resultado

**Corrección de contexto (usuario):** antes de ejecutar `xpd`, el Xperia PLAY ya estaba emparejado y **conectado** y la PC lo veía como celular conectado; tras iniciar `xpd` la situación no cambió (se mantuvo igual). El reinicio del dispositivo ocurrió **al desconectarlo desde la PC** (observado en más de una ocasión durante estas pruebas).

**Análisis offline (framework, `onAgentAuthorize`):**
- `android.server.BluetoothEventLoop.onAgentAuthorize` (direct #21160) guarda `nativeData` en `mAuthorizationAgentRequestData` y decide aceptar/rechazar. Para UUIDs no-audio (p. ej. HID 00001124) toma el camino de rechazo genérico (`"Rejecting incoming <uuid> connection from <addr>"`, insns ~0204-0238 en dex plano) que salta al tail de log (0123) y, si el flag autorizado es falso (0165 `if-nez`), retorna **sin** respuesta positiva.
- La **única** ruta que envía autorización nativa positiva es `android.server.BluetoothA2dpService.allowIncomingConnect(BluetoothDevice,boolean)` → `BluetoothService.setAuthorizationNative(address, authorized, nativeData)` (método 21347). `onAgentAuthorize` la invoca solo en el flujo de aceptación A2DP/AVRCP. En quickened ODEX el flujo es equivalente (mismos offsets lógicos de instrucción, opcodes quick).

**Intervención A2 (hack amplio, en memoria, sin disco/`/system`):**
- Herramienta: `gdbserver` r10e ARM (`GNU gdbserver 7.7`, NDK r10e) en `/data/local/tmp` (temporal, eliminado), modo `--multi` TCP, attach/detach a `system_server`.
- Sesión 1 (inspección): attach/detach OK a `system_server` pid 247; ODEX `r--p` en `0x2d7fb000-0x2dfa8000`. Sin parche.
- Sesión 2 (parche A2, pid 247): en el método quickened, insns 204-205 (mem `0x2da97384/0x2da97386`) reemplazadas de `051a 10f8` (`const-string`) a `1212 28ae` (`const/4 v2,1` + `goto -82 → 0123`) para forzar autorizado=true y saltar al tail que invoca `allowIncomingConnect`/`setAuthorizationNative`. Escritura verificada; detach OK. **Efecto:** el sistema se reinició antes de completar una prueba concluyente con ese parche (posible inestabilidad; no concluyente).
- Tras reinicio, `system_server` pasó a pid 243. **Sesión 3 (reaplicación A2, pid 243):** mismo parche aplicado y verificado en las mismas direcciones; PC reconectado y con servicios refrescados (veía HID 00001124 / `Modalias usb:v0F0Dp1011d0100`). Prueba: `busctl ... ConnectProfile 00001124` desde PC → **RC=1, `br-connection-create-socket`**.
  - Logcat (~21:24:54.875): `Agent:Authorize` recibido; **no** se observó `Rejecting incoming 00001124 ... = false` ni traza posterior de 00001124/resultado de autorización en la ventana capturada (a diferencia de E17). No hubo aceptación/actividad HIDP en `xpd.log`.
  - `system_server`/`bluetoothd`/`xpd` permanecieron activos (sin caída inmediata), pero la conexión HID no se completó ni llegó a `xpd`.
- **Reversión:** parche restaurado en memoria a bytes originales `051a 10f8`; `system_server` estable. Todos los binarios temporales/forwards/logs eliminados.

**Interpretación:** A2 altera el camino (omite el rechazo genérico), pero canaliza la autorización por la vía **A2DP** (`allowIncomingConnect` → `setAuthorizationNative`) que no es semánticamente válida para una petición HID 00001124 en este stack; no produjo una conexión HID utilizable hacia `xpd`. La respuesta correcta requeriría emitir `setAuthorizationNative(address,true,nativeData)` apropiada para HID en el punto/contexto adecuado, lo cual es más complejo y riesgoso en bytecode quickened en vivo.

**Reinicios observados:** (1) durante/tras la secuencia de prueba con parche en memoria; (2) al desconectar PC↔dispositivo desde la PC (con `xpd` activo en ese escenario), el Xperia se reinició. Indican inestabilidad de la interacción BT/xpd/stack en estas condiciones (no atribuible con certeza a un único factor).

**Conclusión E18:** ruta A (A2) **no desbloquea** el gate HID en la práctica. El bloqueo E17 persiste en esta ROM; con F (coexistencia, sin kernel/`/system`) no hay, por ahora, un mecanismo demostrado para entregar la conexión HID entrante a `xpd`.

---

## Cierre de F (coexistencia) — bloqueante

- **Decisión:** cerrar la **variante F** (coexistir con `bluetoothd`, sin modificar kernel ni `/system`, sin archivos persistentes del sistema) como **bloqueada** en la ROM evaluada.
- **Motivo:** el gate de autorización HID entrante (00001124) en `BluetoothEventLoop`/agente rechaza la conexión antes de `xpd` (E17). El intento de forzar autorización en memoria (ruta A/A2, gdbserver r10e, parche quickened revertido) no entregó HIDP a `xpd` y se observaron reinicios/inestabilidad (E18). No existe mitigación demostrada dentro de las restricciones de F.
- **Línea base de todo lo anterior:** Xperia PLAY R800i, **ROM stock** Android 2.3.4 (build `4.0.2.A.0.79`), kernel `2.6.32.9-perf`, BCM4329, BlueZ 4.69.
- **Siguiente enfoque (documentado, pendiente de discusión; sin tocar el dispositivo):** considerar **modificación del sistema/framework** para desbloquear la autorización/entrega de HID, partiendo de la ROM stock. Idea a evaluar: empaquetar hallazgos/parches como **actualización offline** para Xperia Play, midiendo compatibilidad con distintas custom ROMs (empezando por stock). Requiere definir objetivos concretos, riesgos, reversibilidad y compatibilidades antes de cualquier cambio.

## Inventario de artefactos ROM stock (solo lectura, 2026-11-15)

> Base: `SEMC/R800i_1247-8563/R800i:2.3.4/4.0.2.A.0.79/sP_v3w:user/release-keys` (modelo R800i). `/system` montado **ro** (yaffs2), `/data` rw. Sin modificaciones realizadas.

### Framework / runtime
| Artefacto | Ruta | Tamaño | md5 | Notas |
|---|---|---:|---|---|
| framework.jar | `/system/framework/framework.jar` | 3,081,167 | `a76f53e4848e17bba006cb6122d8a094` | sin `.odex` en `/system/framework` |
| framework classes.dex (odex/cache) | `/data/dalvik-cache/system@framework@framework.jar@classes.dex` | 8,047,872 | `c7275bde8888cd389ff7246dd42c7d32` | `dey\n036\0` (ODEX); copia local `/tmp/opencode/rea/framework-cache.dex` idéntica |
| framework dex (extraído/análisis) | `/tmp/opencode/rea/framework.dex` (local) | 7,275,720 | `45a9f68b2c888813298466622dfac788` | dex embebido/lógico analizado (E17/E18) |
| services.jar | `/system/framework/services.jar` | 650,841 | `186fc3d5e3dc532c12f7bbb7db0a3845` | |
| services classes.dex (odex/cache) | `/data/dalvik-cache/system@framework@services.jar@classes.dex` | 1,565,904 | `c7759bf88fc5f8d3eb68683a2854b920` | |
| dexopt | `/system/bin/dexopt` | 9,796 | — | `dalvik.vm.dexopt-flags = m=y` |
| libandroid_runtime | `/system/lib/libandroid_runtime.so` | — | `1de81efcbcc5d7732e8f68d2f1d7e0bb` | exporta `setAuthorizationNative` (JNI) |

### Bluetooth (BlueZ 4.69, capa sistema)
| Artefacto | Ruta | md5 |
|---|---|---|
| bluetoothd | `/system/bin/bluetoothd` | `a1965c2e04887c8626f9e195a7b260c4` |
| libbluetoothd.so | `/system/lib/libbluetoothd.so` | `9cea5c0d281c0857393b7c65340b3ab6` |
| libbluetooth.so | `/system/lib/libbluetooth.so` | `df5a591bda08257216c24b9b3e3bcbf2` |
| input plugin | `/system/lib/bluez-plugin/input.so` | `c80dd35f88f3c3cd75a52502b2c06a31` |
| audio plugin | `/system/lib/bluez-plugin/audio.so` | `92d37edf6de4cae45c7a948f7858beb0` |

### Observaciones relevantes al plan
- No hay ODEX de framework/services en `/system/framework`; la optimización activa de framework está en `/data/dalvik-cache` (`system@framework@...@classes.dex`, header `dey 036`).
- El punto de rechazo HID está en `BluetoothEventLoop.onAgentAuthorize` (método 21160, `framework`); la respuesta nativa positiva la emite `BluetoothService.setAuthorizationNative` (invocada hoy solo vía `BluetoothA2dpService.allowIncomingConnect`).
- Punto 2 (híbrido) solo si 1 no basta: `input.so`/`bluetoothd` median la conexión HID y la solicitud de autorización al agente.

## Plan detallado — Objetivo técnico 1 (Punto 1 primario, híbrido)

> **No ejecutar en el dispositivo sin acuerdo explícito.** Todo lo siguiente es diseño/preparación offline.

### P1 — Intervención en framework (HID-correcta)
1. **Cambio lógico requerido:** en `onAgentAuthorize`, si el UUID es HID `00001124-0000-1000-8000-00805f9b34fb`, autorizar y enviar `setAuthorizationNative(address, true, nativeData)` usando el `nativeData` real de la petición (almacenado/asociado al request), **sin** usar `BluetoothA2dpService.allowIncomingConnect` (ruta A2DP, E18).
2. **Opciones de implementación (bytecode):**
   - **P1a (preferida, si viable):** añadir un camino HID que obtenga `nativeData` (p. ej. desde `mBluetoothService.getAuthorizationAgentRequestData(address)` / estructura equivalente en esta build) y llame `mBluetoothService.setAuthorizationNative(address, true, nativeData)` y registre/retorne como autorizado. Requiere espacio/instrucciones suficientes o reorganización segura del método.
   - **P1b:** factorizar a través de un método de servicio que encapsule la respuesta positiva genérica (si existe o puede exponerse coherentemente) — depende de firmas disponibles.
   - **P1c (último recurso):** parche mínimo de flujo que fuerce respuesta positiva; **solo** si se demuestra que emite `setAuthorizationNative` correcto (A2 demostró que el flujo A2DP no es válido para HID).
3. **Reto:** el método es quickened en ODEX y de tamaño fijo; insertar lógica es no trivial. Estrategias: (i) editar dex “plano” y regenerar ODEX coherente con `dexopt`/herramientas compatibles Gingerbread; (ii) parche quirúrgico en ODEX quickened con mapeo exacto (alto riesgo). Requiere validar viabilidad de herramientas de smali/dex para `dey 036`/Gingerbread.

### P1 — Artefactos a modificar y coherencia
- **Origen de verdad:** `/system/framework/framework.jar` (classes.dex) + ODEX/cache correspondiente (`/data/dalvik-cache/system@framework@framework.jar@classes.dex`).
- **Estrategia recomendada (coherente):** producir `framework.jar` parcheado (dex correcto) y dejar que el sistema regenere la ODEX en dalvik-cache al arrancar (eliminando la entrada de cache y permitiendo `dexopt`), o bien generar una ODEX compatible. Evitar mezclar jar nuevo con ODEX vieja.
- **Backups obligatorios (antes de cualquier cambio):** copias bit-a-bit de `framework.jar` original y de `system@framework@framework.jar@classes.dex` original, con hashes registrados.

### P1 — Reversibilidad y aplicación (cuando se acuerde)
1. Copiar backups fuera de `/system` y `/data/dalvik-cache` (p. ej. `/sdcard`/PC).
2. Remontar `/system` rw solo para reemplazar el artefacto acordado; restaurar permisos/owner originales.
3. Eliminar/reemplazar la entrada de dalvik-cache de framework según estrategia elegida.
4. Reiniciar y verificar arranque de `system_server`/BT; si falla, restaurar originales y limpiar cache afectada.
5. **Rollback:** restaurar `framework.jar` (+ ODEX/cache) originales y reiniciar.

### P1 — Prueba de éxito (criterio para no pasar a Punto 2)
- Con `xpd` activo (SDP/CoD/EIR/PSM 11/13) y PC emparejado: intento `ConnectProfile 00001124` produce autorización positiva (sin `Rejecting... = false`), sin error de establecimiento tipo `br-connection-create-socket` atribuible a auth, y **`xpd` registra `accept`/tráfico HIDP** en control/interrupt. Si se cumple → **Punto 2 descartado**.

### Punto 2 — BlueZ/`input.so` (solo si P1 no basta)
- **Disparador:** P1 verificado correcto (respuesta nativa positiva para HID) pero la conexión no llega a `xpd` o falla en capa BlueZ/input.
- **Acción acotada:** analizar/ajustar ` /system/lib/bluez-plugin/input.so` (y, solo si necesario, `libbluetoothd.so`/`bluetoothd`) para completar el flujo HID entrante con los PSM/SDP expuestos por F.
- **Riesgo/reversión:** mismos principios (backup bit-a-bit, restauración, prueba aislada). Mayor acoplamiento a la build.

### Evaluación P1a (viabilidad, offline) y parche lógico P1 (preparado)
- **P1a viable (mecanismo):** en esta ROM stock no hay `framework.odex` en `/system/framework`; la optimización activa está en `/data/dalvik-cache/system@framework@framework.jar@classes.dex` (`dey 036`). Estrategia coherente: reemplazar `classes.dex` dentro de `/system/framework/framework.jar` por un DEX 035 válido y eliminar la entrada de dalvik-cache de framework para que `dexopt` (`/system/bin/dexopt`, `m=y`) la regenere en arranque. Evita mezclar jar nuevo con ODEX vieja.
- **Artefactos preparados (offline, sin aplicar):** `classes.dex` original extraído de `framework.jar` (md5 `45a9...c788`), parche aplicado → `classes_patched.dex` (md5 `cee7...d15`, dex 035, checksum/signature recalculados) y `framework_patched.jar` (md5 `64d9...5c8`) en `/tmp/opencode/stock-fw/`. Backups/originales conservados.
- **Parche lógico P1 (concreto):** `onAgentAuthorize` (code_off `0x29c1b4`), reemplazo del camino de rechazo genérico (insns 204-211, file `0x29c35c`) que antes hacía log `"Rejecting incoming..."` y `goto 0123`, por:
  - `8554 3e83` — `iget-object v5,v8,mBluetoothService`
  - `6112` — `const/4 v6,1` (authorized)
  - `406e 5363 0015 00b6` — `invoke-virtual {v5(v8.svc), v1(address), v6(true), v11(nativeData)} BluetoothService.setAuthorizationNative`
  - `000e` — `return-void` (resto 212-238 a `nop`)
  - **Efecto:** emite `setAuthorizationNative(address,true,nativeData)` directamente (HID-correcto, sin vía A2DP). **Alcance amplio:** autoriza cualquier solicitud que alcance el rechazo genérico (no solo 00001124) — aceptable para el objetivo, pero documentado.
- **Riesgos específicos:** DEX inconsistente/erróneo ⇒ fallo de `system_server`/bootloop; pérdida de BT/sistema si no hay rollback. Cambios en `/system` y dalvik-cache alteran estado persistente del sistema.

### Plan completo de implementación (Punto 1) — SOLO con confirmación explícita
1. **Precondición:** dispositivo accesible por ADB+root, batería suficiente, backups verificados en PC (`framework.jar` original, `system@framework@framework.jar@classes.dex` original, hashes).
2. **Aplicación:** push `framework_patched.jar` a ubicación temporal en dispositivo; `su` remount `/system` rw; copiar `framework_patched.jar` sobre `/system/framework/framework.jar` preservando permisos/owner (`rw-r--r-- root:root`) y sello coherente; eliminar `/data/dalvik-cache/system@framework@framework.jar@classes.dex` (y solo esa entrada) para forzar re-dexopt; remontar `/system` ro; sincronizar.
3. **Reinicio y verificación:** reboot; confirmar arranque, `system_server`/BT operativos; iniciar `xpd` (START) y probar `ConnectProfile 00001124` desde PC → criterio de éxito: autorización positiva y **HIDP en `xpd`** (sin `br-connection-create-socket` por auth). Si 1 basta, **no tocar BlueZ** (Punto 2 descartado).
4. **Rollback (si falla arranque/BT o resultado inválido):** restaurar `/system/framework/framework.jar` original (remount rw), restaurar o eliminar para regenerar `system@framework@framework.jar@classes.dex` según estado, reiniciar. Mantener copia offline para recuperación.
5. **Huella:** registrar hashes finales, diff lógico y resultado de prueba (éxito/fallo) para “actualización offline” y análisis de compatibilidad custom ROMs.

**No se ejecuta ninguna acción sobre el dispositivo sin tu confirmación explícita.**

---

## Pendiente de probar (no bloquea la documentación)

| # | Prueba | Por qué importa |
|---|---|---|
| P1 | Registro SDP **HID gamepad** con descriptor propio (no el de teclado) | valida los atributos `0x0206` reales |
| P2 | Registro **Device ID** `0x1200` con `0x0f0d/0x1011` + borrado del PnP del sistema (`0x10000`) | ya no es opcional: es la ruta principal (`05` §11.3) |
| P3 | Handshake HIDP completo contra el PC | valida la máquina de estados (bloqueado hoy por el gate de autorización HID, E17; intento A2/E18 no lo desbloqueó) |
| P4 | Agente D-Bus propio | emparejamiento sin diálogo |
| P5 | Switch + MissionControl | **aplazado por el usuario**: no hace falta ahora |
| P6 | Medir zonas de los touchpads | mapeo de sticks |
| P7 | ~~**Volcado crudo** del registro `0x10000` (attrs `0x0201`/`0x0202`)~~ | ✅ resuelto en E16: `bluetoothctl info` muestra `Modalias: usb:v000Ap0000d0000` (VID `0x000a`, PID `0x0000`) |
| P8 | Escribir el **EIR** con `Write_Extended_Inquiry_Response` (OCF `0x0052`) y comprobar cuánto sobrevive | tarea nueva derivada de H3 (E16) |
| P9 | Medir **cuándo** reescribe el sistema el CoD (tras un `Discoverable` on/off) | dimensionar la reafirmación (E14) |
| P10 | Desbloquear autorización HID entrante (ruta A, en memoria) para que la conexión llegue a `xpd` | prerequisito para P3; r10e gdbserver viable para attach; A2 (hack amplio, E18) probado → **insuficiente** (no entrega HIDP a `xpd`, riesgo/inestabilidad). Requiere respuesta nativa HID-correcta (`setAuthorizationNative` con `nativeData` adecuado) o enfoque alternativo |

> **P7 antiguo (¿`bluetoothd` reescribe el CoD al cambiar visibilidad?) → resuelto en E14: sí, y además
> lo persiste en `<mac>/config`. Se sustituye por P9, que mide la frecuencia exacta.**

## P1 — implementación, fallo, rollback y parche seguro (A+B, 2026-11-15/16)

### Resultado de la implementación P1 (binaria/manual) — **fallida (bootloop)**
- **Aplicado (con backup PC):** se reemplazó `/system/framework/framework.jar` por `framework_patched.jar` (md5 `64d9493891eca27957a5b1728db625c8`, dex parcheado md5 `cee793ea076383a8c3fb00b1b78aed15`) y se eliminó `/data/dalvik-cache/system@framework@framework.jar@classes.dex` para forzar re-`dexopt`; reboot.
- **Síntoma:** bucle de arranque (sin `sys.boot_completed` estable, `system_server`/BT inestables/ausentes, ADB cayendo, splash ~<10 s). P1 no llegó a validarse (HIDP no probado).
- **Rollback:** intentos de restaurar `framework.jar` original (`a76f53e4848e17bba006cb6122d8a094`) y ODEX/cache original (`c7275bde8888cd389ff7246dd42c7d32`) y de limpiar dalvik-cache bajo ventanas ADB inestables; persistencia en `/system` no confirmada en todas las lecturas (apareció hash parcheado en algunas). **Dispositivo reflasheado a stock con Flashtool** (sistema limpio, datos perdidos; sin root tras flash). Backups conservados en PC.

### A) Análisis offline del parche fallido (verificado)
Comparación `classes.dex` original (`45a9f68b2c888813298466622dfac788`, 7,275,720 B) vs parcheado (`cee793ea076383a8c3fb00b1b78aed15`, mismo tamaño):
- **Únicos cambios:** cabecera DEX SHA-1/Adler32 + insns 204–238 de `android.server.BluetoothEventLoop.onAgentAuthorize` (code_off `0x29c1b4`, regs 12/ins 4/outs 3/**tries 0**, insns 239). Original en 00cc: rechazo genérico + `goto 007b`. Parche: `iget-object mBluetoothService; const/4 v6,1; invoke-virtual setAuthorizationNative(v1,v6,v11); return-void` y resto `nop` (bytes `8554 3e83 6112 406e 5363 0015 00b6 000e`).
- **Problemas:** (1) **no validado por verificador/dexopt** (edición binaria cruda, sin roundtrip smali/verificador) — causa probable del bootloop (rechazo de verificación/carga ⇒ caída de `system_server`/zygote); (2) **alcance amplio**: autoriza cualquier petición que llegue a 00cc (no solo HID 00001124) y omite el flujo/log original; (3) debug/positions no actualizados, nops residuales; (4) se forzó re-`dexopt` sin validación previa. `services.jar`/BlueZ/kernel no modificados.

### B) Parche seguro P1 (smali, portable, **no aplicado a dispositivo**)
- **Herramientas portables (sin instalación, todo local/descargado a `/tmp/opencode/smali-tools/`):** `baksmali`/`dexlib2`/`util` 2.5.2 (jars existentes en `~/Documentos/Projects/XVC/android_sdk/.../org/smali/` y deps de `cmdline-tools 2.0` ya presentes), `smali-2.5.2.jar` + deps `antlr-runtime-3.5.2.jar`, `stringtemplate-3.2.1.jar`, `commons-cli-1.4.jar` (descargados a `/tmp/opencode/smali-tools/`). Desensamblado/reensamblado OK (DEX 035).
- **Cambio (acotado a HID):** en `onAgentAuthorize` rama genérica 00cc, si `p2.equalsIgnoreCase("00001124-0000-1000-8000-00805f9b34fb")` → `mBluetoothService.setAuthorizationNative(address=v1, true, nativeData=p3/v11)`, `v2=1`, log `onAgentAuthorize(...) = true` y `return-void` (**sin** `BluetoothA2dp.allowIncomingConnect`, evitando el fallo A2DP de E18). Si no es HID → **bloque de rechazo original intacto** y `goto` a cola común 007b. Resto del método sin cambios.
- **Verificación (dexdump del dex generado):** en 00cc compara UUID HID (`string@` HID presente), en match llama `setAuthorizationNative(String;ZI)Z` y retorna; camino 0106 conserva “Rejecting incoming …” y salta a 007b. Sin `allowIncomingConnect` en la rama HID.
- **Artefactos:** `/tmp/opencode/p1-safe-build/` → `framework_patched_safe.jar` (md5 `c71e81ba42bd26b446153a01048fafa2`, sha256 `12b9ab9bb4ea0971173d9df7af549552beaa5eae6e338ba3fafa24eaee614346`), `classes_patched_safe.dex` (md5 `66c43957064cfdbaf370e8614b389905`), más copias stock (`framework_stock.jar` `a76f...`, `classes_stock.dex` `45a9...`). (Tamaño dex reensamblado 7,285,680 B por normalización/constantes añadidas; esperado.)

### Requisitos antes de aplicar al dispositivo (NO ejecutar sin esto; **no forzar reinicio**)
1. Dispositivo reflasheado/estable con **root + ADB estable** y batería suficiente; backups verificados en PC y, si es posible, copia en `/sdcard` (`framework.jar` original y cache/odex original de framework).
2. Push de `framework_patched_safe.jar` a `/data/local/tmp`, remount `/system` rw, reemplazo de `/system/framework/framework.jar` (644 root:root), borrar solo `/data/dalvik-cache/system@framework@framework.jar@classes.dex`, sync. **No reiniciar automáticamente**: dejar listo y reiniciar solo cuando el usuario lo indique.
3. Validación post-boot: `system_server`/BT operativos, `xpd` activo, `ConnectProfile 00001124` → autorización positiva y **HIDP en `xpd`** (sin `br-connection-create-socket` por auth). Si falla arranque/BT: rollback inmediato a jar original + restaurar/limpiar cache de framework. Criterio éxito: P1 basta → no tocar BlueZ (Punto 2).

---

## Logros en RetroActive 0.62 y evolución de estabilidad/rendimiento (HIDP operativo)

> Contexto: tras la ROM RetroActive `4.0.2.A.0.62 FULL` (Android 2.3.4, BlueZ 4.69, kernel 2.6.32.9-perf, BCM4329) se aplicó P1 (framework+cache dexopt) y P2, habilitando un flujo HIDP funcional hacia PC. Esta sección documenta **qué se logró, cómo y con qué trucos**, incluyendo la depuración de latencia/congelamientos hasta el **mejor estado alcanzado**.

## E19 — P1+P2 en 0.62: autorización HID y entrega HIDP a `xpd`

**Objetivo:** desbloquear autorización/entrega de HID entrante (00001124) en 0.62 y validar HIDP end-to-end con el daemon propio.

**Cambios aplicados:**
- **P1 (framework):** `/system/framework/framework.jar` parcheado para autorizar HID con `setAuthorizationNative(..., true, nativeData)` (HID-correcto). Se eliminó `/data/dalvik-cache/system@framework@framework.jar@classes.dex` para regeneración/dexopt coherente en boot. Backups: `/data/local/tmp/framework.jar.stock062.bak`, `/data/local/tmp/framework_cache.stock062.bak`, artefactos en `/tmp/opencode/p1-safe-build-0.62/`.
- **P2 (BlueZ):** `DisablePlugins = input` en `/etc/bluetooth/main.conf` y `/system/etc/bluetooth/main.conf`, reinicio de `bluetoothd` (evita que el plugin `input` del sistema atienda/compita por HID). Backups: `/data/local/tmp/p2-backup/*`, `/tmp/opencode/p2-backup/*`.

**Evidencia/resultado:** HID `00001124` autorizado; `xpd` (auto-start, `/data/local/tmp/xpd`) registra SDP HID `0x20000` y DI `0x20001`, escucha PSM 0x11/0x13, CoD `0x000508`, y recibe conexiones **control + interrupt** (HIDP) desde el host (`8C:64:22:9B:17:15` ↔ PC). El host enumera gamepad (`/dev/input/js0`/event) y `navigator.getGamepads()` lo reconoce.

**Conclusión:** P1+P2 en 0.62 habilitan el flujo F/P1 práctico (framework autoriza, P2 evita interferencia de `input`, daemon recibe HIDP). Requiere backups/rollback claros (framework+cache, `main.conf` permisos `bluetooth:bluetooth` 640).

**Trucos operativos:** no forzar reboot salvo necesario; tras toggle BT reiniciar `xpd`; tras reiniciar `xpd`/re-enumerar reconectar HIDP desde host; evitar “START daemon” de la app si reinicia HIDP/SDP en medio de sesión.

## E20 — Descriptor HID con hat switch y alineación evdev para `mapping:"standard"`

**Problema 1 — D-pad:** un d-pad modelado como botones no ofrecía la semántica esperada por todos los consumidores.  
**Cambio:** report `0x03` (10 bytes) con 4 ejes (X,Y,Rx,Ry, 0..255, centro 128), **Hat switch** (logical 0..7, null 8) en nibble bajo del byte 5, y 16 botones (descriptor en `src/daemon/jni/sdp_register.c`). Codificación hat: `0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW,8=centro`.

**Problema 2 — Permutación en navegador:** aunque `js0`/kernel veían botones, `navigator.getGamepads()` (`mapping:"standard"`, p. ej. verificado en hardwaretester) entregaba índices intercambiados (L1/R1/Start/Select/□/caras) respecto a lo esperado.  
**Causa:** los slots debían alinearse a la semántica **evdev Linux** (orden `BTN_SOUTH/EAST/WEST/NORTH`, etc.) que los navegadores/mapeos asumen sobre dispositivos HID/gamepad.  
**Cambio:** `MainActivity` traduce teclas físicas a slots HID alineados a evdev (p. ej. 1,2,4,5,7–15 / extras 3,6,16 según mapeo implementado) y emite d-pad como hat coherente.  
**Resultado:** web “funciona perfecto” con índices estándar correctos; d-pad confirmado como hat.

**Problema 3 — Identidad cacheada por el host:** VID/PID específicos podían disparar mapeos/perfiles cacheados.  
**Cambio:** DI genérico `0001:0001` en SDP (EIR/identidad coherente según implementación). Puede requerir quitar y re-emparejar el dispositivo en el host.

**Lección:** para compatibilidad web importa tanto el descriptor (hat/ejes/botones) como el **orden semántico de botones**; la identidad anunciada afecta caches/mapeos del host.

## E21 — Parseo UDS por líneas: fin de la pérdida intermitente “no detecta y retoma”

**Síntoma:** cortes intermitentes donde no se detectaban botones y luego retomaba perdiendo pulsaciones previas; a veces con entrega app→daemon aparentemente correcta pero sin efecto estable.

**Evidencia:** en `xpd.log` aparecían comandos corruptos/partidos (líneas `R3` sin prefijo, `cmd:` vacío o con payload desplazado) al llegar múltiples comandos en un mismo `read` o fragmentados, junto a ráfagas normales. `HIDP` podía seguir conectado y armado.

**Causa raíz:** `xpd` leía el socket UDS y trataba el buffer leído como un único comando (asumía un mensaje por `read`), por lo que el coalescing/fragmentación TCP/stream corrompía el parseo y descartaba transiciones.

**Fix (implementado):** buffer por cliente (`client_buf`, `client_buf_len`), acumulación y procesamiento por delimitadores `\n/\r` (comandos completos), trim, y resync por descarte ante overflow. (`src/daemon/jni/xpd.c`, `xpd.h`).

**Resultado:** desaparición de la corrupción de parseo; comandos `R3` consistentes durante ráfagas. Episodios posteriores “sin efecto” se aislaron a otras capas (envío HIDP/host/daemon freeze), no al parseo.

**Lección:** UDS/stream requiere framing por líneas/mensajes; nunca asumir 1 `read` = 1 comando.

## E22 — Evolución de envío: de cola/latencia a correcto, y de bloqueos a estable

Objetivo: eliminar retraso acumulado en ráfagas sin perder transiciones ni congelar el daemon.

**Iteración A — Deduplicación:** solo enviar report 0x03/0x01 si el contenido cambió (evita reenviar 0x01 neutro y estados idénticos). Redujo tráfico pero la **latencia acumulada** persistía bajo ráfagas (el enlace/host no drenaba al ritmo).

**Iteración B — Coalescing por lote + flush periódico (regresión):** diferir envío y emitir el estado final del lote/intervalo eliminó la cola pero **colapsó transiciones intermedias** (press/release perdidos) → “congelado/estado no coincide con pulsaciones”. Revertido en favor de correctitud.

**Iteración C — Envío por transición + dedup + reintento pendiente (base correcta):** `R3/R1` actualizan estado y disparan envío inmediato de cambios; `sent_*` solo avanza en éxito y el tick reintenta si `last != sent`. Conserva transiciones, pero expuso problemas de flujo/bloqueo bajo carga.

**Iteración D — Hilos de bloqueo (cuelgues del daemon, `xpdctl PING` timeout):**
- `write` a L2CAP **interrupt** bloqueante podía trabar el bucle único bajo backpressure → socket interrupt **no bloqueante** y reintento sin perder estado.
- `write/read` en canal **control** HIDP bloqueantes → ctrl no bloqueante y respuestas *best-effort*.
- Respuestas UDS `OK R3/…` con `write(client_fd)` bloqueante por comando → `client_fd` no bloqueante y respuestas *best-effort* (evita congelar el daemon si la app no drena).
- **HCI:** reafirmación periódica CoD/EIR en el tick deshabilitada para evitar posibles bloqueos HCI en hilo principal.

**Iteración E — Busy-loop por ENOMEM/ENOBUFS (regresión crítica):** con sockets no bloqueantes, `write` devolvía `ENOMEM` bajo presión de buffers; al no tratarse como transitorio se logueaba y reintentaba en caliente → ~800k líneas de log, CPU en bucle, daemon inutilizable. **Fix:** tratar `EAGAIN/EWOULDBLOCK/ENOMEM/ENOBUFS` como backpressure transitorio (sin cerrar, sin spam, sin avanzar `sent_*`, reintentar) y reservar cierre/log para errores fatales (EPIPE/ECONNRESET/ENOTCONN/ESHUTDOWN/EBADF).

**Iteración F — Cap fijo 100 Hz (10 ms):** intentó acotar cola pero **empeoró** la latencia percibida/coalescing → revertido.

**Estado alcanzado (mejor):** sin pegado del daemon, transiciones preservadas, dedup y reintentos seguros; latencia acumulada **mínima** en uso normal. Bajo ráfagas **muy rápidas y constantes** (ritmo/FNF) persiste acumulación leve por límite de enlace/host, con experiencia **jugable** pero no perfecta.

**Trucos/lecciones:**
1. Correctitud primero: nunca descartar transiciones (edges) para “bajar latencia”.
2. En bucle único, cualquier I/O bloqueante (BT o UDS) puede congelar todo; usar no-bloqueo + best-effort.
3. `ENOMEM/ENOBUFS` en L2CAP = backpressure (como `EAGAIN`), no error fatal.
4. Dedup reduce tráfico sin alterar semántica; el cap fijo no resolvió el cuello real.

## E23 — Resumen de archivos, comandos y verificación del estado operativo

**Implementación clave:**
- `src/daemon/jni/xpd.c` — bucle select/ticks, UDS line-buffered, ARM/DISARM/NEUTRAL, `send_reports_if_armed` (dedup + reintento pendiente), neutro/keepalive, sin tick HCI periódico.
- `src/daemon/jni/hidp_server.c` — L2CAP/HIDP ctrl/intr no bloqueante, `hidp_send_report` con manejo EAGAIN/EWOULDBLOCK/ENOMEM/ENOBUFS vs fatales.
- `src/daemon/jni/sdp_register.c` — HID/DI, descriptor (hat+16 botones+4 ejes), identidad.
- `src/daemon/jni/uds.c` — `@xperiapad`, `client_fd` no bloqueante.
- `src/daemon/include/xpd.h` — estado (buffers, reports, fds).
- `src/app/java/com/jalm/xperiapad/MainActivity.java` — captura, ARM por foco, UDS, mapeo evdev/hat, presets/remap persistente.
- `src/build.sh`, `meta/android/AndroidManifest.xml` — build/paquete.

**Build/deploy/prueba (referencia):**
```bash
bash src/build.sh
adb push build/native/libs/armeabi-v7a/xpd /data/local/tmp/xpd
adb shell "su -c 'chmod 755 /data/local/tmp/xpd; pkill -9 -f /data/local/tmp/xpd; rm -f /data/local/tmp/xpd.log'"
adb shell "su -c '/data/local/tmp/xpd --log /data/local/tmp/xpd.log'"
adb shell "su -c '/data/local/tmp/xpdctl PING'"
# tras reinicio/re-enum: reconectar HIDP desde host
bluetoothctl disconnect 8C:64:22:9B:17:15 && bluetoothctl connect 8C:64:22:9B:17:15
```
Validación: `jstest --event /dev/input/js0` y navegador (`mapping:"standard"`, hardwaretester). Diagnóstico: `tail /data/local/tmp/xpd.log`, `xpdctl PING`, estado HIDP/BT, `dmesg` en host.

**Conclusión:** el sistema alcanza un estado HID funcional y estable en 0.62 con la combinación P1+P2, descriptor/mapeo correcto y daemon endurecido frente a bloqueos/backpressure/parseo; el límite remanente es de capacidad bajo ráfaga extrema, no de congelamiento ni de semántica de entrada. Referencia consolidada: `docs/09-estado-actual-y-logros.md`.

## E24 — Verificación end-to-end del layout de ejes/botones en el PC

**Objetivo:** confirmar que el report `0x03` (10 bytes, sin report id; el daemon antepone `0x03`) llega al host con el orden de ejes y botones esperado.

**Método:** daemon `xpd` en el dispositivo; en el PC, `bluetoothctl disconnect/connect 8C:64:22:9B:17:15` para (re)crear `/dev/input/js0` (nombre “Xperia PLAY”, handlers `event22/js0`). Envío de secuencias con un cliente que **mantiene la sesión UDS abierta** (`xpdctl -i` con stdin por pipe), porque el daemon **desarma al desconectarse el cliente** (por diseño). Lectura en el PC con un lector propio de `/dev/input/js0` (struct `IhBB` de 8 bytes), ya que no hay `jstest`/`evtest`/`evdev` disponibles.

**Hallazgos:**
- **Ejes en el PC:** `LX, LY, RX, RY` en **js #0–#3** (orden estándar). La prueba inicial con `03` como byte de botones estaba mal: el daemon ya antepone el report id, el payload debe ser 10 bytes sin id.
- **Hat:** `HAT0X/HAT0Y` en **js #4/#5**; el navegador lo traduce a los botones de d-pad (12–15).
- **Botones:** 16, mapeados por el kernel a `BTN_SOUTH…BTN_THUMBR` (códigos 304–319).
- **Capacidades kernel:** `/sys/class/input/input165/capabilities/abs` = `0x3001b` (ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_HAT0X, ABS_HAT0Y).

**Conclusión:** el layout es el canónico de gamepad estándar; el navegador lo expone con `mapping:"standard"` correcto.

## E25 — Sticks sobre touchpad: centros de los indicadores, motor y editor (Fase 1a/1b)

**Objetivo:** mapear los touchpads a sticks (LX/LY, RX/RY) con zonas configurables y editor visual, sin tocar el kernel.

**Método:**
- Captura cruda con `getevent -t /dev/input/event2` (dispositivo `synaptics_touchpad`, ABS_MT_POSITION_X 0–966, ABS_MT_POSITION_Y 0–360, ABS_MT_TRACKING_ID), mientras el usuario sostenía el dedo en cada indicador; parseo de paquetes (x,y) y moda/media de la zona quieta.
- La app habilita los eventos de clase POSITION del touchpad por reflexión sobre `ViewRoot` (`mProcessPositionEvents`), porque la ROM los descarta por defecto.

**Hallazgos:**
- **Centros medidos:** izquierdo **(172, 148)** (x 158–196, y 131–156); derecho **(806, 160)** (x 802–834, y 156–177).
- **Rango funcional:** sticks izq. `b1=4..255`, der. `b3=1..253` (eje completo recorrido desde el centro).
- **Editor:** render verificado por captura de framebuffer (`/dev/graphics/fb0`, 480×854, 32bpp RGBA) y conteo/bbox de píxeles por color objetivo: fondo, superficie, borde, rects seleccionado/no, círculo de zona muerta, cruz central y handle de redimensión presentes y con geometría correcta (ambas zonas 320×320 en x=12 y x=646, y=0).
- **Persistencia:** clave `sticks_v2` en `/data/data/com.jalm.xperiapad/shared_prefs/xperiapad.xml` (se inicializa con los defaults centrados en los indicadores).

**Conclusión:** Fase 1a (motor) y 1b (editor) implementadas y verificadas (render + validación interactiva del usuario: mover/redimensionar/zona muerta/sensibilidad persisten en `sticks_v2` y el motor los aplica). Queda a futuro: zonas sobre touchscreen, más de una zona por superficie y L3/R3 por toque (aplazado).

## E26 — La cascada del apply de P1 (SIGBUS), rescate completo a stock y lección de kernel

**Contexto:** sistema en stock (jar `32300b1d…`, P2 fuera, dalvik-cache re-dexopteada, boot estable confirmado). Se reintentó el Apply desde Setup con la secuencia reordenada (backup → P2 → **P1 jar swap con enlace guardián** → sync → reboot integrado) y el APK 0.63-dev que la implementa. Reloj del teléfono inútil (1981-02-20/21); horas según logcat del dispositivo.

**Secuencia del fallo (logcat correlado):**

1. `03:04:56.988` — `su: cp '/data/data/com.jalm.xperiapad/files/patch.jar' /system/framework/framework.jar` (el guardián `ln framework.jar → framework.jar.keep.<ts>` y el `rm` ya ejecutados; el `cp` crea un **inode nuevo** en la ruta).
2. `03:04:57.438` — dos procesos dalvik (`11202`, `11212`) que arrancaban en esa ventana intentaron cargar el jar **durante la copia**: `Zip: EOCD not found, /system/framework/framework.jar is not zip`. (La ventana rm→cp no es atómica: cualquier proceso nuevo puede leer el archivo a medias.)
3. `03:04:57.808` — `cp` termina. `03:04:57.818` — `DexOpt: source file CRC mismatch (a4c7097f vs 8721e538)`: el dexopt de un proceso nuevo detecta el jar cambiado y **borra el odex compartido** `/data/dalvik-cache/system@framework@framework.jar@classes.dex`.
4. **SIGBUS en cascada**: `pid 10791 (app), tid 11168: signal 7 (SIGBUS), code 2 (BUS_ADRERR), fault addr 2dacdc94` — el guardián protegía el **inode del jar**, pero el **odex no tenía guardián** y al perder su último enlace el kernel liberó sus páginas mientras todas las VMs vivas lo tenían mapeado. También murieron `com.android.phone` y otros; los workers de dexopt (`11202/11208/11212`) re-dexopteaban la cadena de dependencias (`DexOpt: mismatch dep name: android.policy… vs framework.jar@classes.dex`, re-dexopt de `services.jar`, `core-junit.jar`…), el zygote reinició → bootanimation → "traba".

**Intentos de rescate (todos por adb, con `kill-server`/`start-server` tras cada reboot):**

- **R1:** borrar solo el odex de framework + reboot → la cadena de dexopt del boot murió a mitad (workers con `JNIHelp: Native registration unable to find class 'android/debug/JNITest'`, `init: untracked pid … exited`) → boot con **todas** las apps en crashloop (`ClassNotFoundException: com.sonyericsson.home.HomeApplication`, `com.android.phone.PhoneApp`).
- **R2:** `rm -f /data/dalvik-cache/*` completo + reboot → boot largo (~8 min), `sys.boot_completed=1`, cache con **162 odexes reconstruidos**, odex de framework fresco — pero Home/SemcPhone/`suquashi`/`appextensions`/`proxy_1_impl` seguían en ClassNotFound.
- **R3 (verificación de integridad):** `Home.apk`/`SemcPhone.apk` zips válidos (EOCD RSA-PK al final); jars Sony presentes; sus odexes presentes en el cache.
- **R4 (experimento en vivo):** borrar el odex de Home + `am start` → `DexOpt: not all deps represented` / `ODEX file is stale or bad; removing and retrying (am.jar)` → re-dexopt de dependencias → `system_server` en bucle de reinicio (`Waiting for service phone…` porque SemcPhone crasheaba sin registrar el servicio).
- **Rescate definitivo:** remount rw → `cp /sdcard/XperiaPad/backup/framework.jar.stock /system/framework/framework.jar` (SHA verificado `32300b1db6a59a06fb7142083a3fca3a1e8440e8b9985ec81ab4dc0c8e0057d0`) → `cp /sdcard/XperiaPad/backup/main.conf.stock /etc/bluetooth/main.conf` (P2 fuera, `grep DisablePlugins = 0`) → `rm -f /data/dalvik-cache/*` (wipe completo) → `rm -f /system/framework/framework.jar.keep.*` (guardianes fuera) → `sync` → reboot. **Boot de re-dexopt completo (~10-20 min) y sistema estable** (confirmado por el usuario; home operativo).

**Causa raíz y lección de kernel (2.6.32.9-perf, yaffs2):**

- Un archivo mapeado (`mmap`) que pierde su **último enlace del filesystem** libera sus páginas al instante: todo proceso que toque esas páginas después muere con `SIGBUS BUS_ADRERR`. El enlace guardián (hard link) mantiene el inode vivo y es la protección correcta — pero se aplicó al jar y **no a todos los odexes mapeados** del `/data/dalvik-cache`.
- El sistema **por sí solo** borra el odex de un jar cambiado (validación CRC de dalvik en cualquier proceso nuevo) → el swap de un jar del boot classpath con el sistema corriendo es **inherentemente raro** en esta ROM: ni el reboot-inmediato eliminó la ventana (los pasos 2-3 ocurren entre el `cp` y el `reboot`).
- Repeated boots con jar parcheado + wipes parciales dejaron sets de odexes incoherentes (muertes a mitad de cadena); solo la coherencia total (jar stock + cache vacía) restauró una cadena de dexopt limpia.
- **Conclusión operativa:** si P1 volviera a ser necesario, el apply requiere guardianes de **todo** `/data/dalvik-cache` (más el jar) antes del swap, o aplicarlo offline (zip en recovery). Con E27, ambas rutas quedan innecesarias: **P1 se retira**.

**Artefactos/estado:** referencias en `/tmp/opencode/p1-safe-build-0.62/` (jar stock `32300b1d…`, jar parcheado `2666e3b79bdec20cc8f0b1466352a69e66e3fa1f8f2ac73ddb1021adf9573f49`, dexes); backup vivo en `/sdcard/XperiaPad/backup/` (`framework.jar.stock`, `main.conf.stock`); APK instalado con la secuencia reordenada (útil solo si P1 regresa).

## E27 — Retiro de P1: aislamiento P2-only, el gate real y la solución `trusted`

**Objetivo:** determinar si P1 (parche de jar) es prescindible tras la saga E26, dado que la cadena de E17 señalaba al plugin `input` como demandante de la autorización HID.

**1. Diff de los dex (análisis offline, PC):**

- `baksmali 2.5.2` (descargado de Bitbucket; GitHub 404): árbol smali de `classes.dex` stock (7,275,808 B) vs `classes_patched_safe.dex` (7,285,768 B).
- **Un solo archivo difiere:** `android/server/BluetoothEventLoop.smali`. Cambio único en `onAgentAuthorize` (+59 insns, `746a747,805`): si `p2` (UUID) `equalsIgnoreCase("00001124-0000-1000-8000-00805f9b34fb")` → `mBluetoothService.setAuthorizationNative(v1=dirección, true, p3=nativeData)` → log `onAgentAuthorize(addr, uuid) = true` → `return-void`; el resto cae a `:cond_106` (flujo original). Más un `goto` → `goto/16` (`779c838`, corrección de rango de branch). O sea: **P1 = auto-aceptar Authorize de HID por la vía nativa HID-correcta.**
- El diff de strings Bluetooth entre ambos dex es **vacío** (no hay strings nuevas: el parche es puramente lógico).

**2. P2-only en vivo (framework stock, sin P1):**

- P2 aplicado por adb: `sed -i "s/^\[General\]/[General]\\nDisablePlugins = input/"` sobre `/system/etc/bluetooth/main.conf` (con `mount -o remount,rw /system` previo y `remount,ro` posterior; `/etc` es symlink a `/system/etc`). `chmod 644`. Verificado `grep -c DisablePlugins = 1`. **Nota:** `sed -i` crea un temporal en el mismo dir → requiere `/system` rw (no sirve en ro).
- `killall bluetoothd` → el framework lo **respawnea solo** (pid nuevo, `bluetoothd -n`) y relee la config. **P2 no requiere reboot ni provoca cascada** (archivo de texto plano, sin dex/map).
- Daemon: `/data/data/com.jalm.xperiapad/lib/libxpd.so` (38,364 B, `chmod 755`), auto-start: SDP borrado (`0x20000`/`0x20001`), registro HID `0x20000 OK`, DI `0x20001 OK (0x0001:0x0001, generico)`, PSM `0x11/0x13` escuchando, modo gamepad activo. (Primera corrida del día con el APK anterior logueaba DI `0x0f0d:0x1011`; tras `install -r` con el build nuevo, DI genérico — coherente con E20.)
- **Persistencia del daemon por adb:** `su -c '… &'` (con o sin `nohup`) muere al cerrar la sesión adb de forma intermitente. Método fiable: **`nohup adb shell "su -c '<libxpd>…'" &` en el PC** (la sesión adb viva mantiene el proceso). `killall` falla por comm truncado; el kill correcto es el loop `/proc/*/cmdline` con `grep -q "[l]ibxpd"` (truco del corchete para no auto-contar al propio `sh -c`).
- Aparecieron conexiones **HIDP control + interrupt aceptadas por `xpd` sin pareo ni trust** — pista de que la entrega no siempre pasa por el agente.

**3. Emparejamiento PC↔teléfono (re-pairing; el de E17 se perdió del lado PC):**

- PC: adapter `78:2B:46:4E:0A:24` (jalm-ga401iu, BlueZ 5.87), `bluetoothctl` scriptado. Teléfono visible vía `am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE` (diálogo de sistema; `setScanMode` directo sigue siendo inerte en esta ROM).
- SSP comparación numérica: intento 1 (passkey `333165`) falló (el stdin scriptado respondió mal al prompt `Confirm passkey (yes/no)` del agente del PC → `AuthenticationFailed`). Intento 2 (passkey `839624`): respuesta `yes` pre-alimentada al agente + confirmación en el diálogo del teléfono → **Paired/Bonded/Trusted/Connected**.
- Tras el emparejamiento, `info` **no** listaba el UUID `00001124` (solo los stock del teléfono; `Modalias: usb:v000Ap0000d0000`). Tras `disconnect` + `connect` (ServicesResolved fresco), el UUID HID **sí** apareció → el re-browse SDP refresca el cache del host.
- **Detalle:** `ConnectProfile` puede devolver **RC=0 aunque la conexión HIDP falle** (la validación del gate es asíncrona): no confiar en el RC; verificar `xpd.log` (accept) y `/dev/input` del host.

**4. El gate sin plugin `input` (corrección a E17):**

- `busctl call org.bluez /org/bluez/hci0/dev_8C_64_22_9B_17_15 org.bluez.Device1 ConnectProfile s "00001124-0000-1000-8000-00805f9b34fb"` con P2 aplicado y **framework stock**:
  ```
  I/BluetoothEventLoop.cpp: agent_event_filter: Received method org.bluez.Agent:Authorize
  I/BluetoothEventLoop:     Rejecting incoming 00001124-0000-1000-8000-00805f9b34fb connection from 78:2B:46:4E:0A:24
  D/BluetoothEventLoop:     onAgentAuthorize(..., 00001124-...) = false
  ```
  (También se vio `onAgentAuthorize(..., 0000110D-…) = true` — audio sigue aceptándose.) → **el demandante no es solo `input.so`: es la capa de seguridad de BlueZ, claveada al UUID HID del registro SDP** para conexiones entrantes de dispositivos enlazados. E17 atribuía el gate al plugin; el plugin es *un* demandante, no el único.
- Resultado: HIDP **no** llegó a `xpd` (sin accept, sin `/dev/input` en el PC).

**5. La solución: flag `trusted` de BlueZ 4 por dispositivo:**

- Almacenamiento de BlueZ de esta ROM (Android 2.3.4): **flat** por adapter en `/data/misc/bluetoothd/8C:64:22:9B:17:15/` (sin subdirs por dispositivo): `linkkeys` (líneas `MAC key type pinlen`), `profiles` (líneas `MAC uuid…`), `names`, `config`, etc. El bonding del PC quedó registrado (`78:2B:46:4E:0A:24` en `linkkeys`).
- Se creó `trusted` en ese dir con el formato textfile claveado por MAC: `printf "78:2B:46:4E:0A:24 true\n"`, `chown 1002:1002` (bluetooth), `chmod 644`.
- `killall bluetoothd` (respawn del framework, pid `4485`) para que cargue el flag en memoria; daemon reiniciado (re-registro SDP + PSMs OK).
- Desde el PC: `connect` → `ServicesResolved: yes` → `ConnectProfile 00001124` → **RC=0**, logcat **sin** `Rejecting 1124`, y `xpd`:
  ```
  INFO: HIDP control conectado
  INFO: HIDP interrupt conectado
  ```
- **Enumeración en el PC:** `/dev/input/js0` + `event22`; `/proc/bus/input/devices`: `N: Name="Xperia PLAY"`, vía `uhid` (`S: Sysfs=/devices/virtual/misc/uhid/0005:0001:0001.0034/input/input181`, `Bus=0005`, `Uniq=8c:64:22:9b:17:15`, `EV=1b`), además del AVRCP (`input180`). `bluetoothctl info`: `Human Interface Device (00001124…)` conectado.

**6. Efecto práctico del flag `trusted`:** BlueZ 4 auto-autoriza **todos** los servicios de ese peer sin llamar al agente (no facilita emparejamientos de extraños: el SSP sigue siendo la puerta). Para una consola dedicada, auto-trustear cada host emparejado es aceptable.

**7. Alcance y lo que queda:**

- **P1 queda retirado**: el parche del sistema se reduce a **P2** (`DisablePlugins = input`) + **trusted del host** (archivo por dispositivo + respawn de `bluetoothd`). Sin swap de jar, sin reboot, sin riesgo de cascada. La arquitectura F queda completa dentro de sus restricciones originales.
- **Pendiente (flujo Switch):** la consola hace bonding ella misma y conecta HID inmediatamente después → el gate la rechazaría antes de que se escriba el `trusted`. Solución planificada: **auto-trust en tiempo real** — el daemon vigila los bondings nuevos y aplica `SetProperty(Trusted,true)` por D-Bus (instantáneo, `libdbus.so` disponible, root pasa; la alternativa v1 es escribir el archivo + `killall bluetoothd` + re-registro SDP por el watchdog, con ventana de ~3-8 s y reintento del host).
- **Implementación v1 del auto-trust (verificada 2× desde estado frío):** módulo `trusted.c` del daemon — sweep cada 2 s (compara `linkkeys` vs `trusted`; respeta `false` explícitos), con gracia inicial de 15 s y **solo sin sesión HIDP activa** (reiniciar el stack con sesión viva la corta). Tras agregar hosts: `killall bluetoothd` (respawn del framework) → re-registro SDP + CoD/EIR en el tick siguiente; si el restart del stack invalidó el socket HCI (`hci_cmd write … bad state`), se **reabre** (`hci_open_dev`) y se reintenta. Bajo respawn, `sdp_connect` puede fallar en el primer intento (`No such file or directory`) — el reintento a 4 s lo resuelve. La app (APK 0.63-dev) retiró P1 de detección/aplicar/revertir/UI: `patchedEnough()` = P2; aplicar = escribir P2 + respawn de `bluetoothd` (sin reboot).
- **Operativa:** el reloj del teléfono es inválido (1981); los timestamps son relativos. Tras cada reboot, ciclo `adb kill-server`/`start-server` antes de operar.

**Conclusión:** el gate HID se esquiva sin tocar el framework: P2 (el plugin `input` fuera de PSMs) + `trusted` por host en el almacenamiento de BlueZ 4. E17/E18 quedan superados; P1 se conserva en el historial como fallback (ver E26 para su costo real).

## E28 — Switch/MissionControl: GameStick funciona, sus bugs, y modos GemBox/DS3

**Primera prueba real en Switch (Atmosphere + MissionControl):** con identidad GameStick (`0f0d:1011`) y reports GameStick, el mando **attach-ea como Pro Controller** y el input llega — pero: (1) el eje Y de los sticks salía invertido, y (2) pulsar d-pad **abajo** producía **Down+L+R** en la consola.

**Causas:**
1. **Y invertido (nuestro):** `GetYInverted` de MC compensa el GameStick real (arriba=alto); nuestro web usa arriba=0 → había que enviar el byte sin invertir. Corregido.
2. **Down→L+R (bug de MC, verificado en `gamestick_controller.cpp`):** el driver GameStick emula ZL/ZR reasignando `L = !ZL; R = !ZR` cuando Down está activo → Down solo produce Down+L+R. Sin arreglo del lado del teléfono usando esa identidad.
3. **Hallazgo clave sobre MC:** `AttachHandler` crea un `UnknownController` (case default) para VID/PID desconocidos — **attach-ea igual** (aparece como Pro Controller) pero espera reports de Switch en crudo → sin input. Por eso el "aparece pero no funciona" de la primera prueba (la Switch había emparejado con identidad genérica `0001:0001` a mitad del cambio de perfil). Regla operativa: **borrar el mando en la consola y re-emparejar con el modo correcto activo**.

**Solución: modos nuevos (comando UDS `MODE generic|gamestick|gembox|ds3`):**

| Modo | VID:PID (SDP DID) | Reports (id×data) | D-pad abajo | ZL/ZR | Home |
|---|---|---|---|---|---|
| generic | 0001:0001 | 0x03×10, 0x01×8 | ✅ (PC) | — | — |
| gamestick | 0f0d:1011 | 0x03×10, 0x01×8 | ❌ bug MC | combo (trampa) | ✅ |
| **gembox** | **1d79:0009** | **0x07×9, 0x02×1** | ✅ | ✅ triggers reales | ❌ |
| **ds3** | **054c:0268** | **0x01×48, sin secundario** | ✅ | ✅ L2/R2 | ✅ (botón PS) |

- **GemBox** (`gembox_controller.hpp/.cpp`): report 0x07 = dpad nibble (misma enum), sticks s8 (los mismos bytes que producimos, reinterpretados), triggers u8 (ZL/ZR por umbral), botones [7]=A,B,-,X,Y,-,LB,RB / [8]=start,LS,RS; report 0x02 = 1 byte, bit6=back (Minus).
- **DS3** (`dualshock3_controller.hpp/.cpp`): report 0x01×48 = [1]=sel,L3,R3,start,up,right,down,left; [2]=L2,R2,L1,R1,tri,cir,crs,sq; [3]=PS; [5..8]=sticks u8 centro 128; [17]/[18]=L2/R2 (ZL/ZR); [29]=batería (0xEE → "cargando/llena" en MC); [40..47]=acelerómetro neutro (bytes `01 FF` por eje: `SwapEndian==511`). MC manda un "enable payload" y reports LED/rumble al conectar — nuestro control channel los tolera (probado con PC).
- **Daemon:** buffers de report ampliados a 64 B con longitud por modo; `R3`/`R1` validan contra el tamaño del modo (`ERR R3 hex` si no coincide); dedup por longitud+bytes; `set_neutral_reports()` por modo; GET_REPORT usa los ids del modo.
- **App:** `consoleMode()` (`switch`/`ps4` → `ds3`; `gembox`/`gamestick` directos); encoders `buildGemboxReport`/`buildDs3Report` con pre-compensación de swaps (A↔B, X↔Y; DS3: fisica A→circle, B→cross, X→triangle, Y→square); MappingScreen ofrece consolas `Switch (DS3)`, `Switch (GemBox)`, `Switch (GameStick)` para pruebas.
- **Verificado en vivo:** `MODE ds3` → `registro DI 0x20001 OK (0x054c:0268)`; pipeline de reports por tamaño de modo; regresión PC en generic OK.

**Pendiente de consola (usuario):** re-emparejar con modo ds3 activo y validar d-pad (abajo sin L/R fantasma), sticks, ZL/ZR con L2/R2, Home con Guide. Futuro: rumble — MC envía el rumble del juego por output report (el Xperia tiene motor de vibración).

**Nota posterior (decisión del usuario):** los modos `gamestick` y `gembox` se **retiraron** del daemon y de la app (sin ventajas frente a DS3 y con el bug de d-pad del driver de MC). Quedan solo `generic` (web/PC) y `ds3` (Switch). Los contratos byte-exact de ambos quedan documentados arriba por si hiciera falta re-añadirlos.

## E29 — Modelo base↔protocolo y fullscreen por modo

**Modelo de dos capas (decisión de diseño):**
- **Base = perfil/mapeo**: qué significa cada botón para la consola (Switch, PS3, PC...), etiquetas del gamepad virtual, mapeo físico→lógico. La base vive en el perfil (`console`).
- **Protocolo** = capa de identidad/conexión: SDP Device ID, CoD, formato de report (`generic` / `ds3`; futuros `ds4`, `nativo-switch`), independiente del perfil. Preferencia `active_protocol` (migrada la primera vez desde la base: switch/ps4/ds3 → ds3).
- El encoder consulta la base: DS3 con base Switch pre-compensa caras (física A→circle, porque MC invierte); con base PS3 usa la convención nativa (física A→cross). Mismo protocolo, distinta semántica por base.
- UI: selector "Conexión" en Mapeo (Genérico (PC) / DS3 (MissionControl)) con aviso de re-emparejamiento; `MODE` al daemon lee solo el protocolo; cargar un perfil NO cambia el protocolo.

**Fullscreen:** retirado el `FLAG_FULLSCREEN` global de `onCreate`; ahora `applyGameModeChrome()` lo aplica **solo** en modo juego/editor (ambos usan `IDX_GAME`) y lo retira en el resto — la app muestra la barra de estado salvo en el overlay del mando.

## E30 — ¿Se puede sin root? sonda de permisos GID bluetooth

**Sonda** (`src/probe/`, APK `com.jalm.xperiapad.probe`, solo permisos BLUETOOTH/BLUETOOTH_ADMIN): ejecutada por el usuario con BT encendido y daemon parado. Resultado (logcat `XPadProbe`):

| Comprobación | Resultado |
|---|---|
| GID bluetooth 1002 en el proceso | **AUSENTE** (2 GIDs; permisos sí concedidos según `dumpsys package`) |
| `/dev/socket/bluetooth` (SDP local) | **EACCES** (errno 13; socket `660 bluetooth`) |
| `bind()` L2CAP PSM 0x11 y 0x13 | **OK sin root** |
| HCI raw `hci0` open+bind | **OK sin root** |
| `/dev/socket/dbus` (bus sistema) | **EACCES** (errno 13; `660 bluetooth`) |

**Conclusión:** en esta ROM el permiso BLUETOOTH se concede pero **no otorga el GID 1002** a apps de terceros, así que las dos piezas que hacen falta para presentarse como mando —**registrar el SDP (HID + Device ID) y marcar `trusted` por D-Bus**— quedan cerradas sin root. En cambio L2CAP/HIDP y HCI (CoD/EIR) sí funcionan sin privilegios.

**Implicación:** el diseño actual (root para todo) no se puede hacer 100% rootless. Ruta híbrida posible: daemon corriendo como app (sin root) para HIDP/HCI + un helper root **solo** para SDP/trusted en el emparejamiento (reduce superficie: root deja de estar en el loop de envío de reports).

**Corrección E30 (sonda v2-v4):** el GID 1002 buscado en un primer momento era un error de la sonda: los GIDs que da el permiso BLUETOOTH en Android son **net_bt_admin=3001 / net_bt=3002**, y ambos SÍ están presentes (`/proc/<pid>/status → Groups: 3001 3002`). El 1002 es el **usuario** `bluetooth`, dueño de los sockets, que ningún permiso de terceros otorga (`platform.xml` solo mapea net_bt/net_bt_admin/inet/camera/log/sdcard_rw/cache/input/diag). Por eso `EACCES` en `/dev/socket/bluetooth` y `/dev/socket/dbus`: ambos son `660 bluetooth:bluetooth` (1002:1002).

**Vías alternativas sondeadas (v3-v4):**
- SDP sobre L2CAP PSM 1 con `BDADDR_LOCAL` (`00:00:00:00:ff:ff:ff`), `BDADDR_ANY` y la MAC propia → **timeout** (errno 110/114): en esta ROM el servidor SDP solo atiende por el socket unix, no por L2CAP.
- `BluetoothDevice.setTrust(boolean)` → **existe como API oculta**: el system_server podría marcar trusted sin root.
- No existe ninguna API (pública u oculta) para registrar registros SDP en el framework (`strings classes.dex` solo encuentra `setTrust`).

**Conclusión final E30:** el bloqueo sin root no es el GID (que sí se asigna correctamente) sino la **pertenencia de los sockets SDP/D-Bus al usuario bluetooth (1002)** y la ausencia de API para registrar SDP. Sin SDP no hay servicio HID ni Device ID → el host nunca descubre el mando. Root es inevitable en stock 0.62. Sí es viable un **modo híbrido**: HIDP (L2CAP) y HCI (CoD/EIR) funcionan sin root, y `setTrust` se puede hacer vía system_server; el root quedaría reducido al registro SDP en el emparejamiento.

**Sonda v5:** envío de comando HCI `Read_Class_of_Device` (0x0c23) desde UID de app → **ENVIADO (n=4, errno=0)**: el proceso no-root puede enviar comandos HCI, luego CoD/EIR son posibles sin root. Completa el cuadro del modo híbrido: HIDP (L2CAP) + HCI + UDS van sin root; solo SDP (y opcionalmente trusted por archivo) requieren el helper root de corta vida.

## E31 — Retirada del modo híbrido (resumen final)

**Existencia:** E30 planteó un modo híbrido (daemon con UID de app + helper root efímero `xpdsetup` para SDP/trusted/CoE) como forma de reducir el tiempo que el daemon pasa como root. Se implementó como **opt-in** con fallback automático al modo root y se mantuvo el comportamiento por defecto intacto.

**Lo que SÍ funcionó (verificado):**
- `xpdsetup` registraba correctamente SDP HID `0x20000` + DI `0x20001` y ejecutaba `trusted_sweep` con root (`registro SDP OK`).
- Se corrigió el EIR para usar la identidad del protocolo activo (A4) — ese cambio se conserva.
- El socket de sondeo `@xperiapadctl` y la generación de conexión Java (arreglos de carrera) se conservan: son independientes del híbrido.
- La notificación/servicio ancla y el autostop a los 120 s se conservan: también son independientes.

**Lo que NO funcionó (por qué se retiró):**
1. El daemon con UID de app **no puede enlazar PSM 0x11/0x13** (`l2cap bind psm 0x11: Permission denied`). Sin listeners no hay HIDP (`intr=0`): la app mandaba `R3`, el daemon las recibía, pero no salía nada hacia el host. Un sondeo inicial había dado "bind OK" por un error de byte-order en la sonda (`htobs(0x11)=0x1100`): enlazaba el PSM equivocado.
2. Escritura HCI (`Write_Scan_Enable 0x0c1a`, CoD `0x0c24`, EIR) da **EPERM** con UID de app: la sonda solo verificó **enviar** un comando de lectura; esa capacidad no implica permiso de escritura. El host seguía viendo un teléfono.
3. `sdp_connect` al socket unix (`/dev/socket/bluetooth`, grupo 1002) da **EACCES** con UID de app. El helper root lo resolvía, pero no basta sin HIDP/CoD.

**Conclusión:** en stock 0.62 las tres piezas críticas (SDP, PSM reservados, HCI write) exigen root. El híbrido no podía cumplir su función sin comprometer la conexión, así que se **eliminó por completo**: código Java/C, helper, toggle de Ajustes y empaquetado del APK. Queda el daemon root, con las correcciones de carrera/UX y el servicio ancla.
