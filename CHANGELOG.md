<!-- Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later
Additional GPLv3 §7(b): preserve this attribution in modified copies. -->

# Changelog

## v0.2.1 — 2026-11-24 (versionCode 3)

- **Stack probe no bloqueante:** la app comprueba D-Bus, SDP, plugin BlueZ 4, `main.conf`, fuente touchpad y build/kernel. Si difieren de la referencia, avisa “puede fallar horriblemente” pero **no impide** aplicar BT-Patch ni arrancar el daemon.
- **Sin requisito de ROM/kernel:** RetroActive 0.62 y Linux 2.6.32 pasan a ser solo configuración de referencia; los requisitos son las capacidades reales del stack.
- Pantalla de configuración del sistema reorganizada: Root / BT stack / Input / BT-Patch + línea de aviso. Misma clave de firma release que v0.2.0.

## v0.2.0 — 2026-11-24 (versionCode 2)

Primer build público. Cambios principales:

- **HIDP en PC validado:** `/dev/input/js0`, 6 ejes y 16 botones; `jstest`/navegador responden.
- **Auto-trust D-Bus:** `SetProperty("Trusted", true)` con `Hello` y marshalling correcto; sin `killall bluetoothd`.
- **Ciclo de vida H3:** autostart en boot/BT-ON, ancla con `PING` que no cuenta como cliente, autostop a los 120 s y botón **Reconectar gamepad** (`LINKRESET`).
- **Fix SDP:** `sdp_our_records_present()` impide el `SDP reused` falso tras STOP (causa de `Could not parse HID SDP record`).
- **UI/i18n:** Guardar/Restablecer en Ajustes, Controles y Mapeo; ES/EN completos; logs solo en inglés.
- **Seguridad:** permiso Android `signature` para el control del daemon.
- **Licencias:** GPLv3 para el proyecto; `NOTICE` y créditos ES/EN dentro del APK.
- **Switch/MissionControl:** soporte DS3 implementado y marcado como **experimental** (sin validación en consola real).

Notas de instalación y límites: [`docs/00-inicio-rapido.md`](docs/00-inicio-rapido.md).
Estado técnico completo: [`docs/10-auditoria-y-checklist.md`](docs/10-auditoria-y-checklist.md).
