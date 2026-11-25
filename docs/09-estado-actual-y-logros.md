<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 09 — Estado actual, logros y trucos (mejor estado logrado)

> Snapshot del estado funcional actual en **Sony Xperia PLAY R800i**, validado con RetroActive 4.0.2.A.0.62 FULL (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69) como **configuración de referencia, no requisito**. Sin cambios de kernel.  
> Objetivo: usar el Xperia como **gamepad Bluetooth HID estándar** hacia un PC.

## 1. Resumen ejecutivo

- **Framework 100% stock — P1 retirado (E27):** el gate HID entrante se esquiva **sin parche de framework**. E26 documenta la cascada SIGBUS (yaffs2 + mmap + último enlace) que hizo inviable el swap del jar en un sistema vivo; el jar parcheado queda solo como fallback histórico.
- **P2 aplicado y validado:** `DisablePlugins = input` en `/etc/bluetooth/main.conf` (symlink a `/system/etc/bluetooth/`) + respawn de `bluetoothd` (el framework lo relanza solo y relee la config; sin reboot).
- **Trusted del host (E27):** `/data/misc/bluetoothd/<mac_adapter>/trusted` (formato textfile `MAC true`, owner `1002:1002`, 644) + respawn de `bluetoothd` → BlueZ auto-autoriza todos los servicios del peer enlazado sin llamar al agente. Verificado end-to-end: `ConnectProfile 00001124` RC=0 sin rechazo, HIDP control+interrupt a `xpd`, PC enumera `js0` vía uhid.
- **Resultado:** HID `00001124` entregado a `xpd` con framework stock, PC enumera el mando (`js0`/event) y el navegador lo ve como gamepad **`mapping: "standard"`** con entradas correctas (web “funciona perfecto” en el estado alcanzado).
- **Calidad de entrada (mejor estado hasta ahora):** sin congelamiento/pegado del daemon, con latencia acumulada **mínima** en uso normal. **Límite conocido:** ráfagas muy rápidas y constantes (p. ej. juegos de ritmo como *Friday Night Funkin*) aún pueden acumular latencia y degradar la experiencia, aunque llega a un estado **jugable**.

## 2. Qué funciona

| Área | Estado | Detalle |
|---|---|---|
| Autorización HID (E27: sin P1) | ✅ | BT-Patch + `trusted` del host; el agente Java ni se invoca. Framework stock. Auto-trust implementado por D-Bus `SetProperty(Trusted,true)` con fallback v1 (archivo + respawn). |
| Coexistencia/entrega HIDP (P2) | ✅ | Plugin `input` deshabilitado; `xpd` atiende PSM 11/13. |
| Daemon `xpd` | ✅ | Auto-start, registra SDP HID/DI, escucha PSM 0x11/0x13, CoD `0x000508`, maneja HIDP y UDS `@xperiapad`. |
| Enlace HIDP | ✅ | Control + interrupt conectados; reports enviados por interrupt. |
| Descriptor/report | ✅ | **Hat switch** para d-pad (nibble byte5: 0–7, 8=null) + 16 botones + 4 ejes; report `0x03` de 10 bytes. |
| Compatibilidad web estándar | ✅ | Slots de botón alineados a semántica **evdev Linux** para que el navegador entregue índices estándar correctos. |
| Identidad de dispositivo | ✅ | DI/identidad genérica `0001:0001` para evitar mapeos por VID/PID cacheados en el host (puede requerir quitar/re-emparejar). |
| App (`com.jalm.xperiapad`) | ✅ | En foco arma/desarma emisión (ARM/DISARM), captura teclas, conexión UDS persistente, preset/remapeo (0–19) persistente, UI scrollable, log de teclas. |
| Mapeos configurables | ✅ | Preset Standard + “Remapear boton”; fallbacks Xperia (ej. BACK→○, MENU/SELECT→Back/Select, SEARCH/START→Start) ajustables. |
| Sticks sobre touchpad | ✅ | Motor (Fase 1a) + editor visual (Fase 1b): dos zonas cuadradas centradas en los indicadores del touchpad, con zona muerta/sensibilidad/invertir-Y por stick, persistencia `sticks_v2`. |
| Robustez/latencia | ✅ (mejor logrado) | Sin cuelgues del daemon; sin colapso de transiciones; latencia acumulada muy reducida (ver §4 y §6). |

## 3. Qué no está / límites actuales

- **Ráfagas extremas:** en pulsaciones muy rápidas y constantes (ritmo/FNF) puede acumularse algo de latencia/cola por capacidad del enlace/host; experiencia jugable pero no perfecta.
- **Entradas pendientes:** zonas sobre touchscreen (además del touchpad), más de una zona por superficie, L3/R3 por toque (aplazado por decisión), giroscopio.
- **Perfiles:** ✅ implementados (`base/profile` = mapeo; `protocol` = identidad de conexión `generic`/`ds3`, independiente).
- **Pruebas pendientes:** variedad de hosts/drivers/navegadores, reconexión prolongada, escenarios de interferencia. Switch real con MissionControl: **experimental**, sin validar.
- **Operativa (H3):** ✅ autostart al boot/BT-ON, reutilización SDP **solo si los records existen** (§21), `LINKRESET`, keepalive del ancla y botón “Reconectar gamepad”. El daemon se apaga solo tras 120 s sin cliente real.

## 4. Cómo se logró (cambios clave)

### 4.1 Sistema/framework (0.62) — histórico
- **P1 (RETIRADO):** se llegó a reemplazar `/system/framework/framework.jar` parcheado y borrar `/data/dalvik-cache/system@framework@framework.jar@classes.dex`; E26 documentó la cascada SIGBUS (yaffs2 + mmap) y E27 lo retiró. **El framework actual es stock**; backups conservados (`framework.jar.stock062.bak`, `framework_cache.stock062.bak`, artefactos en `/tmp/opencode/p1-safe-build-0.62/`).
- **P2:** `DisablePlugins = input` en `/etc/bluetooth/main.conf` y `/system/etc/bluetooth/main.conf`, reinicio de `bluetoothd`. Backups en `/data/local/tmp/p2-backup/*`, `/tmp/opencode/p2-backup/*`. Rollback: restaurar framework+cache y `main.conf` (permisos `bluetooth:bluetooth` 640), `killall bluetoothd`.

### 4.2 Descriptor HID y reporte (`src/daemon/jni/sdp_register.c`, `xpd.c`)
- Report **0x03** (gamepad): 4 ejes (X,Y,Rx,Ry, 0..255, centro 128) + **Hat switch** (d-pad, logical 0..7, null 8) + 16 botones; payload 10 bytes. Report **0x01** (consumer/home/back) definido según implementación.
- Codificación d-pad: valor ordinal en nibble bajo de byte5 (`0=N,1=NE,2=E,3=SE,4=S,5=SW,6=W,7=NW,8=centro/null`), no máscara de 4 bits.

### 4.3 Mapeo para estándar web (`src/app/java/com/jalm/xperiapad/MainActivity.java`)
- Traducción de botones físicos a **slots HID alineados a evdev** (`BTN_SOUTH/EAST/NORTH/WEST`, etc.) para que el mapeo estándar del navegador sea correcto (corrigió permutaciones de L1/R1/Start/Select/□/caras observadas antes).
- D-pad emitido como hat coherente con el descriptor.
- Presets/remapeo persistente; fallbacks intencionales de Xperia documentados y reconfigurables.

### 4.4 Protocolo y robustez (`xpd.c`, `hidp_server.c`, `uds.c`, `xpd.h`)
- **UDS `@xperiapad`:** `START/STOP/QUIT`, `ARM [0|1]`, `DISARM`, `NEUTRAL`, `PING`, `R3 <10B hex>`, `R1 <8B hex>`. Daemon desarma y envía neutro al desconectar cliente y ante eventos relevantes.
- **Parseo UDS por líneas (fix crítico):** buffer por cliente + procesamiento por `\n/\r` completos y resync en overflow; elimina corrupción/pérdida cuando comandos llegan coalescidos o fragmentados (causa real de “no detecta y luego retoma”).
- **Envío HIDP correcto:** por **transición de estado** (no colapsar lotes) con **deduplicación** (no reenviar reportes idénticos; 0x01 neutro no se repite en cada input) y reintento del último estado pendiente. Keepalive/reconexión/arm fuerzan reenvío cuando corresponde.
- **Sin bloqueos del bucle principal (fixes acumulados):** sockets no bloqueantes en interrupt/ctrl HIDP y cliente UDS; escrituras/respuestas *best-effort*; manejo de `EAGAIN/EWOULDBLOCK/ENOMEM/ENOBUFS` como backpressure transitorio (no cerrar, no spamear log, no avanzar `sent_*`, reintentar) y cierre solo en errores fatales (EPIPE/ECONNRESET/ENOTCONN/ESHUTDOWN/EBADF).
- **HCI:** reafirmación periódica CoD/EIR en tick deshabilitada para evitar bloqueos; identidad/SDP gestionadas al iniciar/operar según implementación.
- **Latencia:** intentos de coalescing por lote y cap fijo (10 ms/100 Hz) empeoraron (pérdida de transiciones o mayor retardo percibido) y se revirtieron; el estado actual prioriza **transiciones íntegras + no bloqueo**, logrando la menor latencia acumulada observada.

### 4.5 Sticks sobre touchpad (`MainActivity.java`) — Fase 1a/1b
- **Captura de posición cruda:** la ROM descarta los eventos de clase POSITION del touchpad salvo que `ViewRoot.mProcessPositionEvents` esté activo. Se habilita desde la app por reflexión (`Window$LocalWindowManager.mWindowManager` → `WindowManagerImpl.mRoots` → `ViewRoot.setProcessPositionEvents(true)`); confirmado en log (`posEvents enabled (1)`).
- **Espacio de coordenadas:** el touchpad entrega **espacio de dispositivo crudo** (0–966 × 0–360), igual que `getevent`; la normalización usa ese rango cuando `source == SOURCE_TOUCHPAD` (`0x100008`, custom de Sony).
- **Motor (1a):** cada `StickConfig` es un rectángulo `(x,y,w,h)` sobre la superficie + `deadzone`, `sensitivity`, `invertY`. Cada puntero del touchpad se asigna a la zona que lo contiene; salir del rectángulo sin levantar el dedo **sigue desplazando** en esa dirección (como un stick real); al soltar, el eje vuelve a 128. Máximo 2 toques (dos punteros).
- **Centros medidos de los indicadores** (con `getevent -t /dev/input/event2` mientras el usuario sostenía el dedo, superficie 966×360): **izquierdo (172, 148)** (x 158–196, y 131–156) y **derecho (806, 160)** (x 802–834, y 156–177).
- **Defaults (`sticks_v2`):** izq. `x=12,y=0,w=320,h=320` (centro 172,160) → LX/LY; der. `x=646,y=0,w=320,h=320` (centro 806,160) → RX/RY; zona muerta 0.18, sensibilidad 1.0, invertir-Y activado.
- **Editor visual (1b):** `StickEditorView` dibuja la superficie y cada zona como cuadrado con cruz central, círculo de zona muerta y cuadro rojo de redimensión. Arrastrar dentro de una zona la mueve; tirar del cuadro rojo la redimensiona (cuadrada, mín. 80, máx. `min(SURF_W,SURF_H)`); sliders de zona muerta (0–0.90) y sensibilidad (0.1–3.0); checkbox invertir-Y; botones “Reiniciar zonas” y “Cerrar”. Todo se persiste en `sticks_v2`.
- **Verificación:** render comprobado por captura de framebuffer (`/dev/graphics/fb0`, RGBA) y análisis de píxeles (fondo, superficie, bordes, rects seleccionado/no, círculo de zona muerta, cruz, handle); geometría correcta (ambas zonas 320×320 en x=12 y x=646, y=0). **Validación interactiva del usuario:** mover, redimensionar, zona muerta y sensibilidad se guardan en `sticks_v2` y el motor los aplica; con la config editada (izq. `x=31.8, 353.9×353.9, dz 0.28, sens 1.26`; der. `x=606, 360×360, dz 0.28`) los cuatro ejes recorren rango completo (LX 1..237, LY 14..255, RX 32..252, RY 42..255 en 768 reportes).

## 5. Trucos y lecciones (lo que realmente importó)

1. **No colapsar transiciones:** coalescer por lote o por ventana fija puede soltar press/release → estado “pegado” o incorrecto. Mejor enviar cada cambio distinto (dedup) y, si hay backpressure, converger al **último estado pendiente** sin bloquear.
2. **Nunca bloquear el hilo único:** un `write` bloqueante (interrupt, ctrl, o respuesta UDS por cada `R3`) congela todo (PING/lectura/envío). Solución: `O_NONBLOCK` + reintentos/best-effort.
3. **Tratar ENOMEM/ENOBUFS como EAGAIN:** en L2CAP/HIDP indican presión de buffers; loguearlos/reintentar en caliente crea busy-loop y “pegado” masivo.
4. **Parsear UDS como stream:** `read` puede devolver múltiples comandos o fragmentos; el parseo por mensaje completo es obligatorio.
5. **Identidad y caché del host:** DI/VID:PID genérico evita que el navegador/host aplique un mapeo específico cacheado; a veces hay que quitar/re-emparejar.
6. **Operativa estable:** reconectar HIDP tras reiniciar `xpd` o re-enumerar; reiniciar `xpd` tras toggle BT; no usar “START daemon” de la app de forma innecesaria.

## 6. Limitación conocida (calidad de juego)

- En ráfagas **muy rápidas y constantes** (ritmo/FNF), el enlace/host puede no drenar al ritmo de entrada; al preservar todas las transiciones puede aparecer **latencia acumulada leve**. Es el mejor equilibrio logrado: **no se pega** y es **jugable**, pero no perfecto para ese patrón extremo.
- Direcciones futuras (sin implementar): ritmo adaptativo solo bajo backpressure, cola acotada que preserve bordes, coalescing seguro en origen (app) manteniendo edges, instrumentación de contadores para ajuste fino.

## 7. Cómo compilar, desplegar y probar

```bash
# Compilar (daemon nativo + APK)
cd /home/jalm/Documentos/Projects/XperiaPad
bash src/build.sh
# -> out/apk/XperiaPad-debug.apk, build/native/libs/armeabi-v7a/xpd
```

```bash
ADB=/home/jalm/Android/Sdk/platform-tools/adb
# Desplegar daemon
$ADB push build/native/libs/armeabi-v7a/xpd /data/local/tmp/xpd
$ADB shell "su -c 'chmod 755 /data/local/tmp/xpd; pkill -9 -f /data/local/tmp/xpd; rm -f /data/local/tmp/xpd.log'"
# Iniciar (en segundo plano)
$ADB shell "su -c '/data/local/tmp/xpd --log /data/local/tmp/xpd.log'"
$ADB shell "su -c '/data/local/tmp/xpdctl PING'"
# Instalar app (opcional)
$ADB install -r out/apk/XperiaPad-debug.apk
```

- **Probar:** abrir XperiaPad → **Conectar** (no “START daemon” salvo necesidad) → mantener en foco (**ARM**). Verificar en PC con `jstest`/`evtest` y en navegador (`mapping:"standard"`). Si no responde tras reinicio de daemon, reconectar HIDP desde el host (`bluetoothctl disconnect/connect <MAC>`).
- **Probar sticks:** en la app, **Editar sticks (touchpad)** para mover/redimensionar zonas y ajustar zona muerta/sensibilidad; luego deslizar el dedo sobre los indicadores del touchpad y comprobar en PC que `js0` mueve LX/LY (izq.) y RX/RY (der.). Los cambios se guardan en `sticks_v2` (`/data/data/com.jalm.xperiapad/shared_prefs/xperiapad.xml`).
- **Diagnóstico rápido:** `tail /data/local/tmp/xpd.log`, `xpdctl PING`, estado BT/HIDP y `dmesg`/navegador en host si hay dudas de lado PC.

## 8. Referencias de implementación

- `src/daemon/jni/xpd.c` — bucle principal, UDS/parseo, ARM/estado, envío/dedup/reintento, neutro/keepalive.
- `src/daemon/jni/hidp_server.c` — L2CAP/HIDP, I/O no bloqueante, envío de reportes y manejo de errores.
- `src/daemon/jni/sdp_register.c` — registros SDP/DI, descriptor HID, identidad.
- `src/daemon/jni/uds.c` — socket UDS `@xperiapad`.
- `src/daemon/include/xpd.h` — estado compartido.
- `src/app/java/com/jalm/xperiapad/MainActivity.java` — UI, captura, mapeo evdev/hat, presets/remap, UDS, motor de sticks y editor visual (Fase 1a/1b).
- `src/build.sh` — build nativo + APK.
- `docs/05-arquitectura.md`, `docs/08-experimentos.md`, `docs/06-plan.md` — detalle de arquitectura, evidencia y plan.
