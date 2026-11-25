<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 03 — Bluetooth, L2CAP y SDP (con las pruebas que cambian la arquitectura)

> Este documento termina en la **Variante F (coexistencia)**, que reemplaza a la Variante A que había
> planteado antes. El cambio sale de dos pruebas nuevas hechas en el teléfono el 2026-11-15:
> (1) se pueden **registrar y borrar registros SDP en caliente** en el `bluetoothd` que ya está corriendo,
> y (2) se puede **cambiar la clase de dispositivo** por HCI sin tocar el resto del stack.
> Con eso deja de ser necesario apagar el Bluetooth de Android y ser dueños del radio.
> Detalle crudo de cada prueba en `08-experimentos.md`.
>
> **Ampliación (misma fecha), tras revisar el fuente de BlueZ de Gingerbread:** aparecen tres hallazgos
> que endurecen F → el sistema **persiste y reimpone el CoD** (§6.1, H1), **`Class` no es escribible por
> D-Bus** (§6.2, H2) y el sistema **publica su propio Device ID falso** en SDP y en el EIR (§6.3, H3).
> Los tres están en `08-experimentos.md` E14-E16.

## 1. Hardware y arranque del radio

| Dato | Valor |
|---|---|
| Chip | Broadcom **BCM4329** (combo Wi-Fi/BT/FM) |
| UART | `/dev/ttyHS0` (`242,0`, modo `600`, grupo `bluetooth`) |
| Servicio `init` | `hciattach` = `/system/bin/brcm_patchram_plus --enable_hci --enable_lpm --baudrate 3000000 --patchram /system/etc/firmware/bcm4329.hcd /dev/ttyHS0` |
| Estado del servicio | `disabled` en `init.semc.rc`; lo arranca/para `libbluedroid` |
| rfkill | `/sys/class/rfkill/rfkill0/{type,state}` (`660 bluetooth`); `state` actual = `1` |
| MAC | `/proc/bt_mac_addr` → `8C:64:22:9B:17:15` |
| Clase actual | `0x58020c` (teléfono) — leída por HCI, ver §6 |

`init.rc` arranca además `dbus-daemon --system` y `bluetoothd -n` (ambos `disabled` hasta que el
framework los activa). `init.svc.bluetoothd` hoy = `running`.

## 2. Stack de usuarios

| Pieza | Qué es |
|---|---|
| `/system/bin/bluetoothd` | Stub de ~5 KB; toda la lógica vive en `libbluetoothd.so` |
| `/system/lib/libbluetoothd.so` | Exporta `add_record_to_server`, `adapter_get_address`, `btd_adapter_set_class`, `btd_register_adapter_driver`, `record_from_string` |
| `/system/lib/libbluetooth.so` | Cliente BlueZ (SDP/HCI), **presente en el dispositivo** |
| `/system/lib/libdbus.so` | Cliente D-Bus, **presente en el dispositivo** |
| `/system/lib/bluez-plugin/` | `input.so`, `audio.so` (cargados al arrancar `bluetoothd`) |
| `/dev/socket/bluetooth` | Socket de **control interno del servidor SDP** (ver §5) |
| `/dev/socket/dbus` | Bus de sistema (`660 bluetooth`, política `allow *`) |

Interfaces D-Bus que expone: solo `org.bluez.Adapter`, `org.bluez.Agent`, `org.bluez.Device`,
`org.bluez.Manager`. **No** existe `org.bluez.Service` → no hay `AddRecord` por D-Bus
(esto descartó la Variante D).

Además, `org.bluez.Adapter.SetProperty` solo acepta `Name`, `Powered`, `Discoverable`,
`DiscoverableTimeout`, `Pairable` y `PairableTimeout`: **`Class` no se puede escribir por D-Bus**
(ver §6.2).

## 3. Kernel: qué hay y qué no

| Elemento | Estado | Consecuencia |
|---|---|---|
| `CONFIG_BT_L2CAP` | compilado dentro | `/sys/class/bluetooth/l2cap` presente → podemos servir L2CAP desde userspace |
| `CONFIG_BT_RFCOMM`, `SCO` | dentro | irrelevantes aquí |
| `CONFIG_BT_HIDP` | en el `defconfig` de DooMKernel, **no** en el kernel stock | `/sys/class/bluetooth` no tiene `hidp` → el kernel no hace de host HID; nosotros somos el **device** HID, no hace falta |
| `/dev/uinput` | presente (`10,223`, `660 bluetooth`) | inyección de teclas para el modo evdev |
| `/dev/uhid` | **ausente** | no hay HID de kernel (llegó en 3.5) |
| `/dev/input/event0..11` | presentes, `660` grupo `input` | hay que ser root o del grupo `input` |
| Módulos en `/system/lib/modules` | solo `bcm4329.ko` | L2CAP/SCO/RFCOMM están compilados dentro |

## 4. L2CAP: la prueba que fija el requisito de root

En `net/bluetooth/l2cap.c` de Linux 2.6.32 (copia en
`download/reference/linux-2.6.32-net-bluetooth-l2cap.c`):

```c
if (la.l2_psm && __le16_to_cpu(la.l2_psm) < 0x1001 &&
            !capable(CAP_NET_BIND_SERVICE)) {
        err = -EACCES;
        goto done;
}
...
if (__le16_to_cpu(la.l2_psm) == 0x0001 ||
            __le16_to_cpu(la.l2_psm) == 0x0003)
        l2cap_pi(sk)->sec_level = BT_SECURITY_SDP;
```

PSMs relevantes: **SDP = 0x0001**, **HIDP control = 0x0011**, **HIDP interrupt = 0x0013**.

Experimento (sonda `download/tools/l2probe`, binario ARMv7 compilado con el NDK):

| Proceso | PSM | Resultado |
|---|---|---|
| `shell` (uid 2000, `CapEff 0000…`) | `0x11` | `bind: FAIL errno=13 (EACCES)` |
| `shell` | `0x1001` | `bind: ok` + `listen: ok` |
| `root` (`su`) | `0x11` | `bind: ok` + `listen: ok` |
| PC `78:2B:46:4E:0A:24` conectando al PSM `0x11` del teléfono | `0x11` | **conexión L2CAP establecida** |

Conclusiones firmes:
1. El kernel **sí** puede hospedar un servidor L2CAP de userspace (no hace falta HIDP de kernel).
2. Un dispositivo remoto puede conectarse a ese PSM.
3. **Sin root no hay PSM de HID.** Es un `capable()` del kernel, no una política de Android.
4. Con root y el Bluetooth de Android encendido, `0x11` y `0x13` están **libres** (nadie más los ocupa):
   el plugin `input.so` de este ROM no está sirviendo HID.

## 5. SDP: quién es el dueño y cómo se le añaden registros (hallazgo clave)

SDP vive en el PSM 1 y lo sirve `bluetoothd`. Sin registro SDP, ni Windows ni la Switch te ven como
gamepad (la Variante E quedó descartada por esto).

**Hallazgo:** Android parcheó BlueZ 4 para que el cliente SDP local use un socket Unix en vez de L2CAP
a sí mismo. En este árbol (`download/reference/aosp-bluez-gingerbread/lib/sdp.c`):

```c
static int sdp_connect_local(sdp_session_t *session) {
        session->sock = socket(PF_UNIX, SOCK_STREAM, 0);
        ...
        strcpy(sa.sun_path, SDP_UNIX_PATH);   /* = ANDROID_SOCKET_DIR "/bluetooth" */
```

y en el dispositivo `/dev/socket/bluetooth` **existe**. Ese socket acepta las mismas peticiones que el
servidor SDP interno (`SVC_ADD_RECORD`, `SVC_DEL_RECORD`, `SVC_GET_RECORD`…).

Prueba 1 — añadir un servicio SP (puerto serie) en caliente:

```
su -c "/data/local/tmp/sdptool add --channel=9 SP"
→ Serial Port service registered
su -c "/data/local/tmp/sdptool browse local" → aparece "Serial Port", handle 0x10007
```

Prueba 2 — añadir un **registro HID** completo (PSM 17, perfil HID 0x1124):

```
su -c "/data/local/tmp/sdptool69 add --handle=0x20000 --channel=17 KEYB"
→ HID keyboard service registered
browse local → "Service Name: HID Keyboard", "Human Interface Device (0x1124)", L2CAP PSM 17, HIDP 0x0011
```

Prueba 3 — **borrar** el registro PnP del sistema (handle `0x10000`):

```
su -c "/data/local/tmp/sdptool69 del 0x10000" → Service Record deleted.
```

Prueba 4 — ¿persiste? **No.** El fichero `…/bluetoothd/<mac>/sdp` mantiene su `mtime` del 23-oct
(es una caché de registros *remotos*, no de los locales). Tras `stop bluetoothd; start bluetoothd`,
los registros añadidos desaparecen y los del sistema vuelven a estar en su sitio.

**Consecuencia:** podemos publicar el HID + Device ID que queramos, en memoria, sin escribir nada
en `/system` ni en `/data`, y sin que sobreviva a un reinicio.

Cuidado con el orden: al reiniciar `bluetoothd`, hay ~3-5 s en los que el servidor SDP está vacío
(los plugins del sistema republican sus registros al encender el adaptador). Nuestro daemon debe
**re-registrar** tras cualquier reinicio del stack.

## 6. Clase de dispositivo (CoD): cómo decir "soy un gamepad"

La Switch (y MissionControl) filtran por CoD antes de mirar nada más. Valor objetivo: `0x000508`
(periférico, gamepad).

Prueba con `download/tools/btinfo` (HCI crudo, socket `AF_BLUETOOTH/SOCK_RAW`):

```
hci0 name=hci0 bdaddr=15:17:9B:22:64:8C flags=0x0000000d
class actual=0x58020c
write class 0x000508: CC ok
controller class=0x000508        ← releído del controlador
```

Funciona con `bluetoothd` corriendo. Restaurado a `0x58020c` al terminar.

Detalles:
- Comando HCI: OGF `0x03`, OCF `0x0024` (`Write_Class_of_Device`), 3 octetos little-endian.
- Hace falta `setsockopt(SOL_HCI,HCI_FILTER)` con `type_mask`/`event_mask` a `0xffffffff` para recibir
  los `Command Complete` (si no, `read()` se queda bloqueado).
- **Riesgo de carrera (confirmado, H1):** `bluetoothd` no solo reescribe el CoD cuando cambia
  `Discoverable`: además lo **persiste en disco** y lo reimpone al arrancar. Ver §6.1.

### 6.1 El sistema reimpone el CoD (hallazgo H1)

Observado en el dispositivo, `/data/misc/bluetoothd/<mac>/config`:

```
name Xperia PLAY
class 0x58020c
discovto 120
onmode discoverable
mode discoverable
```

En el fuente (`src/storage.c:246`, `src/adapter.c:209/872/2204/2722`): `write_local_class()` guarda
`class 0x……` en ese fichero en **cada** cambio de clase aceptado; `read_local_class()` lo lee al
arrancar si no hay caché en memoria; y `adapter_set_service_classes()` recalcula
`wanted_cod = (wanted_cod & 0x00ffff) | (svc << 16)`. Como el `config` del teléfono tiene
`mode discoverable` + `onmode discoverable`, al arrancar `bluetoothd` vuelve a ponerse visible y
dispara ese recálculo.

**Consecuencia:** la reafirmación del CoD **no es una mitigación opcional, es obligatoria**. El daemon
debe reescribir CoD (y EIR, §6.3) cada vez que detecte un cambio de visibilidad/clase y, mientras dure
el modo emparejamiento, de forma periódica (2 s). Detalle en `08-experimentos.md` E14.

### 6.2 No hay atajo por D-Bus (hallazgo H2)

`org.bluez.Adapter.SetProperty` (`src/adapter.c:1378`) solo acepta `Name`, `Powered`,
`Discoverable`, `DiscoverableTimeout`, `Pairable` y `PairableTimeout`. **`Class` no se puede escribir
por D-Bus** (solo se emite, `adapter.c:877`). La vía HCI de §6 es la única. Detalle en `08` E15.

### 6.3 Device ID: el sistema publica el suyo (hallazgo H3) y hay que reescribir el EIR

El **EIR** (*Extended Inquiry Response*) es el paquete de ~240 bytes que el controlador devuelve en cada
respuesta de *inquiry*: ahí van el nombre, el TX power y el **AD tipo `0x10` (Device ID)**. Es lo que ve
un PC o una consola al descubrir el teléfono, antes incluso de conectar.

El sistema ya mete un Device ID falso: `/etc/bluetooth/main.conf` trae `DeviceID = android:generic:1.5`,
que en `main.c` se copia a un buffer de 15 bytes → `"android:generi"`, y en `sdpd-server.c` se parsea con
`strtol(…, 16)` → **VID `0x000a`, PID `0x0000`, versión `0x0000`**. Ese mismo valor se publica de dos
formas: (1) registro SDP PnP (`0x1200`, handle `0x10000`) y (2) AD `0x10` dentro del EIR. Detalle en
`08` E16.

Tareas derivadas (nuevas):

| # | Tarea | Detalle |
|---|---|---|
| D1 | **Borrar** el registro SDP `0x10000` del sistema y publicar el nuestro (`0x20001`, `0x0f0d:0x1011`) | ya probado que se puede (E8); deja de ser plan B y pasa a ser la ruta principal |
| D2 | **Reescribir el EIR** por HCI: `Write_Extended_Inquiry_Response`, OGF `0x03`, OCF `0x0052`, 240 bytes | `lib/hci.c:2175` (`hci_write_ext_inquiry_response`) ya lo implementa; se reutiliza el mismo cliente HCI del CoD |
| D3 | **Reafirmar CoD + EIR juntos** tras cualquier cambio que los regenere | `update_ext_inquiry_response()` se dispara desde `adapter.c:880/918/947/2733` (clase, nombre, servicios, modo SSP) |
| D4 | Respetar `ssp_mode`: `update_ext_inquiry_response()` solo escribe si `dev->ssp_mode > 0`; si no, deja el EIR **vacío** | nuestro daemon debe escribir siempre el suyo, sin depender del modo SSP del sistema |

> Cuidado: escribir nuestro AD `0x10` **y** dejar que el sistema regenere el suyo produce la misma
> carrera que el CoD. CoD y EIR van siempre de la mano. **Confirmado en E16:** el teléfono expone
> `Modalias: usb:v000Ap0000d0000` (VID `0x000a`, PID `0x0000`).

## 7. Emparejamiento: quién hace SSP

`bluetoothd` es quien negocia SSP/PIN y guarda las link keys en
`/data/misc/bluetoothd/<mac>/linkkeys`. Si dejamos que él lo haga, **no hace falta implementar
criptografía HCI** (esto es lo que ahorra ~600-800 líneas respecto a la Variante A).

Lo que falta por resolver:

- Para un emparejamiento entrante, BlueZ exige un **agente registrado** en `org.bluez.AgentManager`.
  Android registra el suyo desde `Settings` (`BluetoothEventLoop`), así que si el teléfono está
  despierto aparece el diálogo "¿Emparejar con…?" y el usuario acepta.
- Alternativa sin diálogo: nuestro daemon (root) registra su propio agente por D-Bus y auto-acepta
  *Just Works* / PIN `0000`. Es viable porque `libdbus.so` está en el dispositivo y la política del bus
  es `allow *` (el único filtro es el permiso del socket: `660 bluetooth`, y root pasa).
  **PENDIENTE:** probar el registro del agente (hay que compilar un cliente D-Bus mínimo con el NDK).
- La Switch no es descubrible: el flujo real es "el teléfono visible + la consola en *Change Grip/Order*",
  y es la **consola** la que inicia la conexión y el bonding. En ese momento el teléfono debe estar
  visible (`SCAN_MODE_CONNECTABLE_DISCOVERABLE`, 120 s por defecto).

## 8. Alternativas evaluadas (6)

| # | Variante | Qué implica | Veredicto |
|---|---|---|---|
| **A** | Daemon dueño del radio (BT apagado por API + `brcm_patchram_plus` + SSP/link keys propios + SDP propio) | ~600-800 líneas extra de HCI; BT del teléfono inutilizable mientras juegas | Funciona, pero **ya no es necesaria** |
| **B** | Plugin en `/system/lib/bluez-plugin/` | Escribe en `/system` (yaffs2, hay que remontar rw), sobrevive a la desinstalación | ❌ Descartada: persistente y es el patrón que más alarma a un antivirus |
| **C** | Inyectar código en `bluetoothd` por `ptrace` | Sin archivos nuevos, pero es inyección literal en un proceso del sistema | ❌ Descartada: frágil y peor ópticamente que B |
| **D** | `AddRecord` por D-Bus / `sdptool add HID` | Este `bluetoothd` no expone `org.bluez.Service`; el `sdptool` del ROM no tiene builder de HID (`add == NULL`) | ❌ Imposible por esa vía |
| **E** | Servir SDP en otro PSM, o sin SDP | HID exige PSM 1 y registro SDP; Windows/Switch consultan SDP antes de conectar | ❌ Imposible |
| **F** | **Coexistencia**: dejar `bluetoothd` vivo y añadirle en caliente el registro SDP + CoD + EIR + servidor HIDP propio | Root solo para: socket `/dev/socket/bluetooth`, PSMs `0x11/0x13`, HCI `Write_Class_of_Device` y `Write_Extended_Inquiry_Response`. Cero archivos, cero reinicio del stack, BT normal sigue usable | ✅ **Elegida** |

> **Variante A (actualización):** A sigue siendo técnicamente válida, pero exige **modificar el kernel**,
> y el usuario fijó como límite duro que F no toque el kernel y funcione con kernels ≠ stock. Por eso A
> queda **fuera de este proyecto** y se reevalúa, como **proyecto separado**, solo cuando F esté terminado
> y funcional al máximo. Ver el "Registro de decisiones" en `README.md`.

### Por qué F gana

1. **No apaga el Bluetooth del teléfono**: auricular, transferencias y demás siguen funcionando.
2. **No ejecuta `brcm_patchram_plus`** ni baja/sube `hci0`: menos superficie y menos "comportamiento raro".
3. **No implementa SSP ni gestiona link keys**: lo hace el sistema, con sus diálogos.
4. **No escribe ni un byte** fuera de `/data/data/<pkg>/`; los registros SDP viven en RAM de `bluetoothd`
   y desaparecen al reiniciar.
5. Código a escribir: **HIDP (control+interrupt) + encoder de reports + registro SDP + reafirmación
   CoD/EIR**. ~1.5-2 días en vez de ~3.5.

### Riesgos asumidos de F

| Riesgo | Mitigación |
|---|---|
| `bluetoothd` reescribe el CoD al cambiar visibilidad **y lo persiste en `<mac>/config`** (H1) | reafirmar CoD **+ EIR** cada 2 s en modo emparejamiento (§6.1) |
| El sistema regenera el EIR con su Device ID falso `0x000a:0x0000` (H3) | reescribir el EIR por HCI y reafirmarlo junto al CoD (§6.3) |
| **Gate de autorización HID (E17/E18):** `bluetoothd`/plugin `input` solicita autorización de agente para HID (00001124) y `BluetoothEventLoop.onAgentAuthorize` rechaza UUIDs no-audio → la conexión no llega a `xpd` aunque SDP/PSM estén correctos | sin mitigación demostrada dentro de F puro; ruta A/A2 en memoria probada (E18) no desbloqueó HIDP (respuesta vía vía A2DP inadecuada) y mostró inestabilidad/reinicios. Requiere una respuesta nativa HID-correcta en el contexto adecuado o replantear enfoque |
| El usuario apaga el Bluetooth desde Ajustes mientras el mando está activo | detectar `STATE_TURNING_OFF` y parar el daemon ordenadamente |
| `bluetoothd` se reinicia (crash o `stop/start`) | watchdog: si el registro SDP desaparece, re-registrar |
| Registros nuestros huérfanos tras un cierre anormal | al arrancar, borrar por handle conocido (`0x20000`, `0x20001`) antes de añadir |
| El agente de emparejamiento muestra diálogo | aceptable; o agente propio por D-Bus (pendiente de prueba) |
| Root sigue siendo obligatorio | inevitable (ver §4); el APK seguirá oliendo a *riskware* |

## 9. Lo que NO cambia

- Sin root no hay mando. Punto.
- El HIDP hay que implementarlo nosotros (el kernel no lo hace como *device*).
- El descriptor de reports y el registro SDP son nuestros; el resto del stack es del sistema.
