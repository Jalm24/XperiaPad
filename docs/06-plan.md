<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 06 — Plan de implementación y estado

> Estimaciones originales en jornadas efectivas. El proyecto evolucionó por hallazgos de ROM/framework y depuración de HIDP/rendimiento.
>
> **Configuración de referencia (validada):** Xperia PLAY R800i, **RetroActive 4.0.2.A.0.62 FULL** (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). No es un requisito: la app verifica el stack real y avisa si difiere, sin bloquear. **Sin cambios de kernel.**

## Estado global (actual, mejor logrado)

- **P2 + trusted (E27):** ✅ desbloqueo del gate HID **sin parche de framework** (`DisablePlugins=input` + flag `trusted` del host + respawn de `bluetoothd`); framework 100% stock (P1 retirado tras la cascada SIGBUS de E26).
- **HIDP end-to-end:** ✅ control + interrupt hacia `xpd`, enumeración en PC (`js0` vía uhid) y navegador `mapping:"standard"` correcto — con framework stock.
- **Descriptor/mapeo:** ✅ hat switch para d-pad, 16 botones + 4 ejes, slots alineados a evdev para estándar web, DI genérica.
- **App/daemon:** ✅ captura y envío (ARM por foco, R3/R1), presets/remapeo persistente, parseo UDS robusto, envío por transición con dedup y reintento seguro.
- **Estabilidad/rendimiento:** ✅ sin congelamiento del daemon (I/O no bloqueante, best-effort, ENOMEM tratado como backpressure, sin tick HCI bloqueante). Latencia acumulada **mínima** en uso normal.
- **Sticks sobre touchpad:** ✅ Fase 1a (motor) y 1b (editor visual) implementadas y verificadas end-to-end. Dos zonas cuadradas centradas en los indicadores medidos del touchpad (izq. 172,148 / der. 806,160; superficie 966×360), con zona muerta, sensibilidad e invertir-Y por stick, persistencia en `sticks_v2`.
- **Límite conocido:** ⚠️ ráfagas muy rápidas y constantes (p. ej. FNF) pueden acumular algo de latencia (jugable, no perfecto). Mejora futura: control de ritmo adaptativo que preserve transiciones.
- ✅ **App/UX (2026-11-24):** botones virtuales, editor fullscreen con restauración de orientación, perfiles + protocolo global (`generic`/`ds3`), multi-idioma ES/EN, ancla del daemon, autostart boot/BT-ON (H3), auto-trust D-Bus y “Reconectar gamepad”.
- **Pendiente:** más pruebas de hosts/reconexión prolongada y L3/R3 por toque (aplazado). Switch/MissionControl: implementado vía DS3 y **publicado como experimental**, sin validación en Switch real.

Detalle consolidado: `docs/09-estado-actual-y-logros.md` y `docs/08-experimentos.md` (E19–E23).

## Fases (revisión respecto al plan original)

### Fase 0 — Base y herramientas ✅
- [x] Diagnóstico del dispositivo, sondas L2CAP/SDP/HCI, toolchain (NDK r16, `APP_PLATFORM=android-14`, `APP_PIE=false`), JDK 8, material de referencia.

### Fase 1 — App + captura de entrada ✅ (parcial/funcional)
- [x] App Android (captura de teclas, UI, foco→ARM/DISARM, UDS).
- [x] Mapeo lógico configurable (preset/remap persistente), log/estado básico.
- [x] Touchpad: captura de posición cruda (habilita `mProcessPositionEvents` por reflexión) y motor de sticks con zona muerta/sensibilidad.
- [x] Editor visual de sticks (mover/redimensionar, zona muerta, sensibilidad, invertir-Y, reiniciar).
- [ ] UX completa (botones en pantalla, ayuda, rotación de logs) y calibración guiada.

### Fase 2 — Daemon + SDP + HIDP + PC ✅
- [x] `xpd`: UDS, SDP HID/DI, HIDP 0x11/0x13, reports 0x03/0x01, auto-start, CoD/identidad.
- [x] Validación en PC: enumeración HID, `js0`/event y navegador con `mapping:"standard"` correcto.
- [x] Endurecimiento: parseo UDS por líneas, no-bloqueo, dedup/reintento, manejo de backpressure (EAGAIN/ENOMEM/ENOBUFS), eliminación de cuelgues.

### Fase 3 — Emparejamiento/compatibilidad de host 🔄
- [x] Flujo práctico en PC (conexión HIDP estable con P2+trusted, reconexión operativa).
- [x] Desbloqueo del gate HID sin parche de framework (E27: P2 + `trusted` del host).
- [x] **Auto-trust en tiempo real (v1):** el daemon barre `linkkeys` vs `trusted` cada 2 s (con gracia inicial de 15 s y solo sin sesión HIDP activa); hosts nuevos → entrada en `trusted` + respawn de `bluetoothd` + re-registro SDP/CoD/EIR (reabriendo el socket HCI si el restart lo invalidó). Verificado dos veces desde estado frío (E27). Mejora futura: D-Bus `SetProperty(Trusted,true)` para eliminar la ventana del respawn.
- [ ] Agente D-Bus propio para emparejamiento sin diálogo (auto-accept *Just Works*), si hiciera falta.
- [ ] Switch + MissionControl: **aplazado** (fuera de alcance actual).

### Fase 4 — Entrada avanzada y ajustes ⬜
- [x] Touchpads como sticks configurables: zonas (rectángulos), eje LX/LY y RX/RY, zona muerta, sensibilidad, invertir-Y, editor visual.
- [ ] Zonas en touchscreen (además de touchpad) y más de una zona por superficie.
- [ ] L3/R3 por toque dentro de la zona (aplazado por decisión).
- [ ] Botones en pantalla y perfiles de control.
- [ ] Modo evdev/alternativas según necesidad.

### Fase 5 — Robustez, documentación y empaquetado 🔄
- [x] Correcciones de freezes/latencia al mejor estado conocido; procedimientos de build/deploy/diagnóstico.
- [x] Documentación actualizada (arquitectura, experimentos, logros, estado). 
- [ ] Pulido UX/consentimiento/ayuda, rotación de logs en app, validación de limpieza/rollback integral, README de usuario final.

## Riesgos y mitigaciones (vigentes)

| Riesgo | Estado | Mitigación |
|---|---|---|
| Bloqueo por autorización HID (ROM/framework) | Resuelto en E27 **sin parche de framework**: BT-Patch + `trusted` del host (auto-trust D-Bus implementado, con fallback v1) | `SetProperty(Trusted,true)` por D-Bus + archivo `trusted` + respawn; framework stock (ver E26). |
| Interferencia plugin `input` (BlueZ) | Mitigado | `DisablePlugins=input` (P2); `main.conf` es texto plano (sin riesgo de dexopt/SIGBUS). |
| Cuelgues del daemon por I/O bloqueante/backpressure | Mitigado | Sockets no bloqueantes, best-effort, ENOMEM/EAGAIN como transitorio, sin tick HCI bloqueante. |
| Latencia acumulada en ráfagas extremas | Parcial (mejorado, no eliminado) | Envío por transición+dedup; futuro: ritmo adaptativo preservando bordes. |
| Cache de identidad/mapeo en host | Conocido | DI genérica + re-emparejar si procede. |
| MissionControl/Switch | **Experimental** (DS3), sin prueba en Switch real | `MODE ds3` + identidad `054c:0268`; borrar host y re-emparejar si se cambia el protocolo. |

## Próximos pasos sugeridos (actualizado 2026-11-24)

1. ✅ Auto-trust D-Bus validado en dispositivo (SASL `AUTH EXTERNAL` hex-coded + `Hello`); sin `killall bluetoothd`.
2. Probar en caso real/juego y confirmar si la latencia residual es aceptable.
3. Si se requiere: ritmo adaptativo solo bajo backpressure (sin perder transiciones) e instrumentación.
4. Probar DS3 contra una Switch real con MissionControl.
5. Ampliar zonas de touchscreen (fuera del touchpad) si se decide.
6. Revisión de fallbacks de mapeo por defecto según preferencia.
7. Consolidar pruebas de reconexión/hosts y empaquetado final.
