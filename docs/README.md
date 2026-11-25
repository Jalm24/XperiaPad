<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# XperiaPad — Xperia PLAY como mando Bluetooth HID

Convierte un **Sony Xperia PLAY R800i** (Android 2.3.4) en un **gamepad Bluetooth HID estándar** para PC (y potencialmente otros hosts), sin modificar el kernel.

Este repositorio contiene investigación, herramientas, implementación (`src/`) y documentación del estado logrado.

## Licencia y créditos

- **Material añadido por XperiaPad:** `Copyright (C) 2026 Jalm24`, bajo **GPLv3** (`LICENSE`), con cláusula adicional **§7(b)** para conservar la atribución del autor en modificaciones y avisos legales.
- **Código derivado/original:** BlueZ 4.69 conserva sus copyright y su licencia **GPLv2-or-later** originales; las cabeceras no se tocaron. Detalle en `NOTICE`.
- **Agradecimientos:** [`CREDITS.md`](../CREDITS.md).

## Estado actual (mejor logrado)

**Configuración de referencia (solo validación):** RetroActive `4.0.2.A.0.62 FULL` (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). No es un requisito: la app comprueba el stack real (D-Bus, SDP, L2CAP, plugin `input`, fuente touchpad) y solo avisa si difiere; nunca bloquea.

- ✅ **Framework stock (P1 retirado):** sin jar parcheado; el gate HID se esquiva con **BT-Patch** (`DisablePlugins = input`) + `trusted` del host.
- ✅ **BT-Patch (antes P2):** `DisablePlugins = input` → conexión HIDP (control+interrupt) entregada a `xpd`. Apply/revert documentados y backups intactos.
- ✅ **Gamepad funcional en PC:** enumeración HID (`js0`) y navegador con `mapping:"standard"` correcto (d-pad por hat, slots alineados a evdev).
- ✅ **Estabilidad:** sin congelamiento/pegado del daemon (I/O no bloqueante, backpressure ENOMEM/EAGAIN seguro, parseo UDS por líneas).
- ✅ **App completa:** perfiles, mapeo, sticks/botones virtuales, editor fullscreen, multi-idioma ES/EN y ciclo de vida del daemon (ancla + autostart al boot/BT-ON + botón “Reconectar gamepad”).
- ⚠️ **Switch vía MissionControl (experimental):** identidad DS3 `054c:0268` (`MODE ds3`); genérico/PC `0001:0001`. Implementado pero **sin validación en Switch real**. GameStick/Gembox retirados.
- ⚠️ **Latencia:** mínima en uso normal (mejor estado hasta ahora). Ráfagas muy rápidas y constantes (p. ej. FNF) pueden acumular algo de latencia — **jugable, no perfecto**.

**Resumen completo y cómo probar:** [`docs/09-estado-actual-y-logros.md`](09-estado-actual-y-logros.md).

## Qué se logró y cómo (en breve)

| Logro | Cómo / truco clave |
|---|---|
| HID autorizado y entregado a userspace | BT-Patch (`DisablePlugins=input`) + `trusted` del host; framework stock. |
| Mapeo web estándar correcto | Descriptor con **hat switch** + botones en **slots evdev**; DI genérica `0001:0001` (evita caches del host). |
| Fin de pérdidas por parseo | UDS tratado como stream: **buffer + parseo por líneas** y resync. |
| Sin freezes del daemon | Sockets HIDP/UDS **no bloqueantes**, respuestas best-effort, sin tick HCI bloqueante. |
| Sin busy-loop bajo carga | `EAGAIN/EWOULDBLOCK/ENOMEM/ENOBUFS` como **backpressure transitorio** (reintento, sin cerrar ni spamear). |
| Latencia reducida sin romper input | Envío por **transición + dedup** y reintento del último estado pendiente (no colapsar lotes/edges). |

Detalle técnico y cronología: [`docs/05-arquitectura.md`](05-arquitectura.md), [`docs/08-experimentos.md`](08-experimentos.md) (E19–E25).

## Uso rápido (build / deploy / prueba)

```bash
# Compilar
bash src/build.sh
# -> out/apk/XperiaPad-debug.apk, build/native/libs/armeabi-v7a/xpd
```

```bash
ADB=/home/jalm/Android/Sdk/platform-tools/adb
$ADB push build/native/libs/armeabi-v7a/xpd /data/local/tmp/xpd
$ADB shell "su -c 'chmod 755 /data/local/tmp/xpd; pkill -9 -f /data/local/tmp/xpd; rm -f /data/local/tmp/xpd.log'"
$ADB shell "su -c '/data/local/tmp/xpd --log /data/local/tmp/xpd.log'"
$ADB shell "su -c '/data/local/tmp/xpdctl PING'"
# opcional: $ADB install -r out/apk/XperiaPad-debug.apk
```

- En el teléfono: abrir **XperiaPad** → **Conectar** (evitar “START daemon” innecesario) → mantener en foco (**ARM**).
- En PC: verificar con `jstest --event /dev/input/js0` o navegador (hardwaretester, `mapping:"standard"`).
- Tras reiniciar `xpd`/re-enumerar: reconectar HIDP (`bluetoothctl disconnect/connect <MAC>`). Tras toggle BT: el daemon se reengancha solo (H3); si hace falta, usa **Reconectar gamepad**.
- Diagnóstico: `tail /data/local/tmp/xpd.log`, `xpdctl PING`.

## Notas importantes

- **Requiere root** y acceso a HCI/L2CAP; sin kernel modificado. BT-Patch tiene apply/revert y backups documentados; el framework queda intacto.
- **No `INTERNET`**. El daemon arranca al boot/BT-ON (H3) y se apaga solo tras 120 s sin cliente; las órdenes de control del ancla están protegidas por permiso `signature`. Heurísticas antivirus pueden alertar por root/ELF/sockets crudos.
- **Persistencia/residuo:** CoD/EIR/SDP pueden quedar inconsistentes ante cierres forzados; se mitiga con reinicio/reaplicación y limpieza al parar cuando es posible.

## Documentos

| Doc | Contenido |
|---|---|
| [`00-inicio-rapido.md`](00-inicio-rapido.md) | **Instalación y primeros pasos (ES)** |
| [`00-quickstart-en.md`](00-quickstart-en.md) | **Quick start (EN)** |
| [`09-estado-actual-y-logros.md`](09-estado-actual-y-logros.md) | **Foto actual:** qué funciona, límites, trucos, cómo correr/probar |
| [`05-arquitectura.md`](05-arquitectura.md) | Arquitectura e implementación real (protocolo, HID, mapeo, robustez) |
| [`06-plan.md`](06-plan.md) | Estado de fases y próximos pasos |
| [`08-experimentos.md`](08-experimentos.md) | Cuaderno de evidencia (incl. E19–E25: 0.62, latencia/estabilidad, layout HID en PC, sticks/touchpad) |
| [`01-hardware-y-sistema.md`](01-hardware-y-sistema.md) | Hardware, ROM, kernel, root |
| [`02-entradas-y-touchpads.md`](02-entradas-y-touchpads.md) | Entradas físicas y touchpads |
| [`03-bluetooth-y-sdp.md`](03-bluetooth-y-sdp.md) | BT/SDP/HID, alternativas y hallazgos |
| [`04-missioncontrol.md`](04-missioncontrol.md) | Contexto Switch/MissionControl (investigación histórica; estado actual: DS3) |
| [`07-descargas.md`](07-descargas.md) | Material y herramientas de referencia |

## Contexto histórico (investigación)

Las fases previas documentan el bloqueo por autorización HID en stock 0.79 (E17–E18) y el camino P1/P2/framework-BlueZ; en 0.62 el flujo quedó operativo. **P1 y el modo híbrido son historia**: P1 se retiró por riesgo SIGBUS (E26/E27) y el híbrido por `EPERM`/`EACCES` (E30/E31). Ver `docs/08-experimentos.md`, `docs/10-auditoria-y-checklist.md` §15.
