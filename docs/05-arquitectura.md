<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 05 — Arquitectura e implementación (estado actual, 0.62)

> Documento vivo: describe la arquitectura **real implementada y operativa** en la configuración de referencia, junto con notas de diseño/riesgo heredadas de la investigación F/P1.
>
> **Configuración de referencia (validada):** Sony Xperia PLAY R800i, **RetroActive 4.0.2.A.0.62 FULL** (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). No es un requisito: la app verifica el stack real y avisa si difiere, sin bloquear. **Sin cambios de kernel.**

## 1. Estado actual (resumen)

- **P1 RETIRADO (E26/E27):** el framework queda **stock** (jar `32300b1d…`). El swap de `framework.jar` en un sistema vivo es intrínsecamente riesgoso en esta ROM (yaffs2 + mmap + validación CRC de dalvik → SIGBUS en cascada; ver E26). El parche de jar se conserva solo como fallback histórico (`/tmp/opencode/p1-safe-build-0.62/`, backup en `/sdcard/XperiaPad/backup/`).
- **P2 aplicado:** `DisablePlugins = input` en `/etc/bluetooth/main.conf` (symlink a `/system/etc/bluetooth/`), respawn de `bluetoothd` (el framework lo relanza solo y relee la config; sin reboot).
- **Trusted del host (E27):** el gate HID entrante se esquiva con el flag `trusted` de BlueZ 4 por dispositivo: `/data/misc/bluetoothd/<mac_adapter>/trusted` (formato textfile `MAC true`) + respawn de `bluetoothd`. El host emparejado queda auto-autorizado; el gate del agente Java ni se invoca. **Pendiente:** auto-trust en tiempo real para hosts que hacen bonding solos (Switch) — daemon vigila bonds nuevos y aplica `SetProperty(Trusted,true)` por D-Bus (o v1: archivo + respawn, ventana de segundos).
- **Resultado:** conexión HIDP (PSM 0x11 control, 0x13 interrupt) entregada a `xpd` **con framework 100% stock**. El PC enumera gamepad (`js0`/event vía uhid) y el navegador expone `mapping:"standard"` con entradas correctas.
- **Calidad:** sin congelamiento/pegado del daemon; latencia acumulada mínima en uso normal. **Límite conocido:** ráfagas muy rápidas y constantes (p. ej. FNF) pueden acumular algo de latencia (jugable, no perfecto). Ver `docs/09-estado-actual-y-logros.md`.

> Contexto histórico: el gate de autorización HID en stock 0.79 se documentó en `docs/08-experimentos.md` (E17–E18). En 0.62 se desbloqueó con P1+P2; a partir de E27 se desbloquea **sin P1** (P2 + `trusted` del host). E26 documenta la cascada SIGBUS que motivó el retiro de P1.

## 2. Principio y límites

- **Coexistencia:** no se sustituye el stack del sistema; se le añade servicio HID propio (SDP/HIDP) servido por `xpd` con `bluetoothd` vivo.
- **Sin kernel:** todo en espacio de usuario/root. Funciona sobre la ROM indicada; kernels/ROMs distintas pueden requerir ajustes.
- **Persistencia:** cambios de sistema acotados a **P2 + archivos `trusted`** en `/data/misc/bluetoothd/` (con backup/rollback); el framework queda stock. Registros SDP y estado HIDP/EIR son mayormente efímeros frente a reinicios del stack; ver §10.

## 3. Diagrama (real)

```
┌──────────────────────────── Xperia PLAY (R800i, 0.62) ──────────────────────┐
│  App (Java, com.jalm.xperiapad)  ──UDS @xperiapad──►  xpd (daemon, root)  │
│   - captura teclas (focus)                           - auto-start            │
│   - preset/remap persistente                         - SDP HID+DI            │
│   - ARM/DISARM, R3/R1                                - HIDP 0x11/0x13        │
│                                                      - CoD/EIR (HCI)        │
│                                                      - envío reports        │
│                                  bluetoothd (BlueZ 4.69, plugin input off)│
│                                  hci0 (BCM4329) / L2CAP                     │
└───────────────────────────────────────────────┬────────────────────────────┘
                                                │ L2CAP PSM 0x11 + 0x13
                                       ┌────────▼────────┐
                                       │ Host PC (HID)   │  js0 / navegador
                                       └─────────────────┘
```

## 4. Componentes

| Componente | Ubicación / forma | Responsabilidad |
|---|---|---|
| **App** | `com.jalm.xperiapad` (APK) | UI, captura de input físico, preset/remapeo, conexión UDS, armar/desarmar según foco, envío de estados (R3/R1). |
| **Daemon** `xpd` | `/data/local/tmp/xpd` (ELF ARM) | Auto-inicio de modo gamepad, SDP (HID/DI), HIDP server, CoD/EIR, recepción de comandos UDS, codificación/envío de reports, keepalive/neutro. |
| **`xpdctl`** | helper nativo | Envío de comandos/diagnóstico por UDS (ej. `PING`). |
| **Stack sistema** | `bluetoothd` + BlueZ 4.69 | Radio, HCI, SDP server, L2CAP (con P2: sin plugin `input`). |

## 5. Runtime y operación

- **Inicio daemon:** `su -c '/data/local/tmp/xpd --log /data/local/tmp/xpd.log'` (auto-registra HID/DI, abre PSM 11/13, aplica identidad/CoD según implementación).
- **Conexión app↔daemon:** socket `AF_UNIX` abstracto `@xperiapad`. La app arma (`ARM`) en foco y desarma/envía neutro al perder foco o desconectar según lógica de UI/estado.
- **Reconexión:** tras reiniciar `xpd` o re-enumerar, reconectar HIDP desde el host (`bluetoothctl disconnect/connect <MAC>`). Tras toggle de BT en el Xperia, reiniciar `xpd`.
- **Precaución:** evitar “START daemon” desde la app si provoca reinicio de HIDP/SDP en medio de una sesión estable.
- **Diagnóstico:** `su -c '/data/local/tmp/xpdctl PING'`, `tail /data/local/tmp/xpd.log`, `cat /sys/class/bluetooth/hci0/class`, `jstest`/`evtest`/`dmesg` y `navigator.getGamepads()` en host.

## 6. Protocolo app ↔ daemon (UDS, actual)

Socket abstracto `@xperiapad`, texto/línea (comandos terminados en `\n/\r`). Parseo en daemon: **buffer por cliente y procesamiento por líneas completas** (resync en overflow) para tolerar coalescing/fragmentación.

| App → daemon | Significado |
|---|---|
| `START` | (Re)activa modo gamepad (SDP/HIDP/CoD/EIR según estado). |
| `STOP` / `QUIT` | Detiene/limpia y termina sesión de modo activo (según implementación). |
| `ARM` / `ARM 1` | Habilita emisión (app en foco). |
| `ARM 0` / `DISARM` | Desarma y fuerza estado neutro. |
| `NEUTRAL` | Fuerza reports neutros. |
| `R3 <20 hex>` | Actualiza report **0x03** (10 bytes). |
| `R1 <16 hex>` | Actualiza report **0x01** (8 bytes). |
| `PING` | Keepalive/sondeo. |

| Daemon → app | Significado |
|---|---|
| `OK ...` | Comando aceptado. |
| `ERR ...` | Comando/parseo rechazado. |

**Comportamiento:** al desconectar cliente UDS o desarmar, se envía **neutro**. Si el enlace HIDP cae, el daemon gestiona reconexión de canales según aceptación L2CAP; al recuperar interrupt armado reenvía estado.

## 7. HIDP y reports (actual)

- **Canales:** control PSM `0x11`, interrupt PSM `0x13` (L2CAP). Reports de entrada por interrupt (`0xA1, report_id, payload`).
- **Report 0x03 (gamepad, 10 bytes):**
  - **4 ejes** (X, Y, Rx, Ry): 0..255, centro `0x80` (bytes 1–4 del payload).
  - **Hat switch (d-pad):** nibble bajo del byte 5, valores `0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW,8=null/reposo` (no máscara de 4 botones).
  - **16 botones:** distribuidos en bits según descriptor (mapeados a slots evdev para estándar web, §8).
  - Relleno/reservas según descriptor (ej. nibble alto de hat y bytes restantes constantes/reservados).
- **Report 0x01 (consumer, 8 bytes):** controles de sistema (p. ej. Home/Back) según descriptor; en operación normal puede permanecer neutro.
- **Neutro:** ejes centrados, hat `8`, botones sueltos.
- **Cadencia/estrategia:** envío por **transición** con **deduplicación** (no reenviar si el report no cambió) + reintento del último estado pendiente ante backpressure; keepalive/reconexión fuerzan reenvío. (No se usa coalescing por lote que descarte transiciones.)

## 8. Descriptor HID, mapeo y compatibilidad

- **Descriptor (implementado en `src/daemon/jni/sdp_register.c`):** Game Pad (report 0x03) con ejes X/Y/Rx/Ry, **Hat switch** (0..7, null 8) y 16 botones; Consumer Control (report 0x01) para Home/Back. El diseño previo (d-pad como 4 botones) quedó reemplazado por hat para compatibilidad.
- **Mapeo a estándar web:** la app traduce teclas físicas a **slots alineados a semántica evdev Linux** (p. ej. `BTN_SOUTH/EAST/WEST/NORTH`, hombros, gatillos, select/start, sticks) para que `navigator.getGamepads()` entregue índices estándar correctos (A/B/X/Y, LB/RB, LT/RT, Back/Start, LS/RS, d-pad por hat). Esto corrigió permutaciones observadas en navegador.
- **Fallbacks Xperia (preset, configurables):** p. ej. `BACK (4)`→B/○, `MENU (82)`/`BUTTON_SELECT (109)`→Back/Select, `SEARCH (84)`/`BUTTON_START (108)`→Start; reconfigurables y persistentes desde la app (“Remapear boton”).
- **Identidad:** DI genérico **`0001:0001`** (SDP/EIR según implementación) para evitar mapeos específicos por VID/PID cacheados en el host; puede requerir quitar y re-emparejar el dispositivo en el host.

## 9. Robustez, rendimiento y trucos aplicados

- **No bloquear el bucle principal (hilo único):** sockets **no bloqueantes** en L2CAP interrupt/ctrl y cliente UDS; escrituras y respuestas *best-effort*.
- **Backpressure HIDP:** `EAGAIN/EWOULDBLOCK/ENOMEM/ENOBUFS` tratados como transitorios (reintento, sin cerrar canal, sin spam de log, sin avanzar estado “enviado”) para converger al último estado; errores fatales (`EPIPE/ECONNRESET/ENOTCONN/ESHUTDOWN/EBADF`) cierran el canal.
- **Sin busy-loop:** el manejo anterior de `ENOMEM` que logueaba/reintentaba en caliente se corrigió (evitó cuelgue/CPU/log masivo).
- **Dedup + transiciones íntegras:** no reenviar reports idénticos; no colapsar por lote (preserva press/release). Caps fijos agresivos (p. ej. 10 ms) empeoraron y se revirtieron.
- **UDS stream-safe:** buffer + parseo por líneas y resync.
- **HCI:** reafirmación periódica CoD/EIR en tick deshabilitada para evitar bloqueos; CoD objetivo `0x000508`.
- **Resultado:** latencia acumulada mínima en uso normal; ráfagas extremas constantes pueden aún acumular algo (límite de enlace/host) sin congelar el daemon.

## 10. Persistencia, backups y rollback

- **P1 (retirado):** backups `framework.jar.stock062.bak`, `framework_cache.stock062.bak` y artefactos en `/tmp/opencode/p1-safe-build-0.62/`; backup vigente en `/sdcard/XperiaPad/backup/framework.jar.stock` (SHA `32300b1d…`, verificado). El framework está **stock**; no requiere rollback.
- **P2:** backups `/data/local/tmp/p2-backup/*`, `/tmp/opencode/p2-backup/*`, y `/sdcard/XperiaPad/backup/main.conf.stock`. Rollback: restaurar `main.conf` en `/etc` (`/system/etc/bluetooth`; permisos `644`) y reiniciar `bluetoothd` (killall → el framework lo respawnea).
- **Trusted:** archivo `/data/misc/bluetoothd/<mac_adapter>/trusted` (formato `MAC true`, owner `bluetooth:bluetooth` 1002:1002, 644). Rollback: borrar la línea/dispositivo del archivo y reiniciar `bluetoothd`.
- **Residuo operativo aceptado:** SDP en RAM de `bluetoothd`, CoD/EIR sujetos a reescritura del sistema ante cierres forzados; mitigado con inicio/reaplicación y limpieza al parar cuando es posible. Link keys son del sistema.

## 11. Límites actuales y trabajo pendiente

- **Rendimiento:** ráfagas muy rápidas/constantes (ritmo/FNF) pueden degradar por acumulación bajo carga extrema (jugable). Mejoras futuras: ritmo adaptativo solo en backpressure, cola acotada preservando bordes, coalescing seguro en origen, instrumentación.
- **Funcionalidad:** touchpads/touchscreen como sticks/zonas, botones en pantalla, más UX de configuración; perfiles de identidad opcionales.
- **Compatibilidad:** más hosts/navegadores/drivers, reconexión prolongada; Switch/MissionControl fuera del foco actual.

## 12. Referencias

- Implementación: `src/daemon/jni/{xpd.c,hidp_server.c,sdp_register.c,uds.c}`, `src/daemon/include/xpd.h`, `src/app/java/com/jalm/xperiapad/MainActivity.java`, `src/build.sh`.
- Evidencia y estado: `docs/08-experimentos.md`, `docs/09-estado-actual-y-logros.md`, `docs/06-plan.md`.
- Investigación/base histórica F/P1: secciones y entradas previas en `docs/03-bluetooth-y-sdp.md`, `docs/08-experimentos.md`.
