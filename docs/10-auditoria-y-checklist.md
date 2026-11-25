<!--
Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later

Additional GPLv3 §7(b) term: this author attribution must be
preserved in modified source files and in the project's legal notices.
Documentation text; quoted third-party excerpts keep their original notices.
-->

# 10 — Auditoría (2026-11-19) y checklist de resolución

> Auditoría en tres frentes (seguridad, consistencia, funcional), más la investigación
> posterior sobre la viabilidad de funcionar sin root (E30). Estado verificado contra
> el código en la fecha. `✅` = corregido y compilado/instalado; `⬜` = pendiente;
> `ℹ️` = decisión de diseño / no es defecto.

## 1. Críticos — todos resueltos ✅

| ID | Hallazgo | Ubicación | Estado |
|---|---|---|---|
| C1 | UDS `@xperiapad` sin autenticar: cualquier app inyectaba HID / mataba el daemon root | `uds.c:11-24`, `xpd.c:361-513` | ✅ `SO_PEERCRED` en `uds_accept()` + `--uid <UID app>` al arrancar (fail-closed) |
| C2 | Inyección de comandos root por nombre de perfil con comillas desde SD | `MainActivity.java:1719`, `MappingScreen.java:120-127` | ✅ `safeProfileName()` `[A-Za-z0-9_-]{1,32}` en crear/renombrar/guardar/exportar/importar/listar |
| C3 | Crash off-by-one: 21 nombres lógicos vs `boolean[20]` al asignar "(ninguno)" | `MainActivity.java:118,121-124`, `GameScreen.java:117`, `ButtonOverlayView.java:87,224` | ✅ "(ninguno)" → `logical=-1`, guardas en `setLogicalButton` y en dibujo/touch |
| C4 | SDP muerto tras aplicar/revertir BT-Patch: se mataba `bluetoothd` sin re-registrar | `MainActivity.java:1929-1931`, `xpd.c:152-196` | ✅ comando `BTRESTART` → `sdp_rereg_pending` con reintentos cada 4 s |
| C5 | Auto-start con BT no listo: `PING` respondía con `active=0` y nunca se reintentaba | `MainActivity.java:809-827,842-890,558-570` | ✅ comando `STATE` + `START` automático al sondear y en `STATE_ON` |

## 2. Altos ⬜ (excepto los marcados)

| ID | Hallazgo | Ubicación | Estado |
|---|---|---|---|
| A1 | L2CAP sin `BT_SECURITY` y `accept()` sin validar peer | `hidp_server.c:52-76,186-212` | ⬜ (A2 cubre el gate de autorización por trusted; esto es defensa en profundidad) |
| A2 | Auto-trust de **todos** los hosts enlazados + respawn del stack | `trusted.c`, `dbus_trusted.c`, `xpd.c` | ✅ implementado: D-Bus `SetProperty(Trusted,true)` (SASL EXTERNAL corregido) + fallback v1 archivo/respawn; ver §20 |
| A3 | Suplantación DS3 + borrado del Device ID del sistema `0x10000` | `sdp_register.c:248`, `xpd.c:451-452` | ℹ️ inherente al diseño MISSIONCONTROL; documentado en `04`/`08`, consentimiento del usuario |
| A4 | EIR con VID/PID GameStick `0f0d:1011` (modo retirado) mientras el SDP usa `054c:0268` / `0001:0001` | `hci_class.c:179-185` | ✅ EIR usa `st->di_vid/di_pid` del modo activo (coherente con SDP) |
| A5 | Guardar un perfil de fábrica → Toast "actualizado" pero `saveActiveProfile()` no guarda | `MappingScreen.java:369-395`, `MainActivity.java:1628-1632` | ✅ aviso explícito en lugar de "actualizado" |
| A6 | Watchdog trusted puede matar `bluetoothd` durante un pairing lento (carrera) | `xpd.c:152-196` | ⬜ (gracia de 15 s, no cubre emparejamientos lentos) |
| A7 | `readLine()` sin timeout, `closeSocket()` fuga hilos, doble `connectSocket()` concurrente | `MainActivity.java:893-907,931-992` | ⬜ |
| A8 | `hci_cmd()` bloquea el loop hasta 40 s (20 eventos × 2 s) | `hci_class.c:98-136` | ⬜ (en esta ROM no vuelve CMD_COMPLETE: ver `hci_cmd_write`) |
| A9 | `/system` queda RW si falla el `cp` en apply/revert | `MainActivity.java:1910-1923` | ⬜ (try/finally con remount ro) |
| A10 | Build limpio fallaba: faltaba crear `$INT/assets` | `src/build.sh:35` | ✅ añadido al `mkdir -p` |

## 3. Medios

| ID | Hallazgo | Ubicación | Estado |
|---|---|---|---|
| M1 | `system()` como root en `trusted_watch` | `xpd.c:193` | ⬜ (cadena constante; reemplazar por `kill()` directo) |
| M2 | Path traversal en exportación SD | `MainActivity.java:1676` | ✅ cubierto por `safeProfileName()` |
| M3 | Backups SD sin permisos ni hash (existe `sha256()` sin usar) | `MainActivity.java:1757,1871-1882` | ⬜ |
| M4 | Logs con MACs / comandos root / reportes; `fopen(...,"a")` con umask permisivo | `xpd.c:359`, `trusted.c:91`, `log.c:26-28` | ⬜ |
| M5 | `setupP1` declarado/asignado pero nunca leído; comentario P1/P2 | `MainActivity.java`, `src/build.sh` | ✅ `setupP1` eliminado; framework stock y `build.sh` ya no dice “parche del framework” |
| M6 | Botón "Configuración del sistema" sin `OnClickListener` | `DiagnosticsScreen.java:109-112` | ⬜ |
| M7 | `scanEnable` / `btScanMode()` estado muerto; app nunca envía `SCAN ?` | `MainActivity.java:69,955-956,604,723` | ⬜ (la UI de "descubrible" usa temporizador local) |
| M8 | Ternarias con ramas idénticas `swBase ? 0x80 : 0x80` | `MainActivity.java:1075-1076` | ⬜ (cosmético: ambas convenciones coinciden en esos botones) |
| M9 | Dos caminos de envío (`sendReportNow` con guardas vs `sendReportState` sin ellas) | `MainActivity.java:1020-1023,1101-1111` | ⬜ |
| M10 | Etiqueta "Switch (DS3)" en selector de **base**, mezclando base con protocolo | `MappingScreen.java:100` | ⬜ (debió quedar solo "Switch"; el protocolo lo elige el radio) |
| M11 | `README.md` y `docs/04` afirman P1 aplicado / documentan modos gamestick/gembox retirados | `docs/README.md`, `docs/04-missioncontrol.md` | ✅ estados actualizados; GameStick/Gembox marcados como históricos |
| M12 | `docs/06` tiene como próximos pasos tareas ya hechas y numeración rota | `docs/06-plan.md:70,73,75` | ⬜ |
| M13 | `keepScreenOn=false` se ignora al iniciar (`addFlags` incondicional tras `loadPrefs`) | `MainActivity.java` | ✅ `applyKeepScreenOn()` sustituye al `addFlags` incondicional |
| M14 | Perfil "Switch" no migraba el protocolo | antes `MappingScreen.java:99,324` | ℹ️ **ya no aplica**: con el modelo base↔protocolo (E29) son independientes a propósito |
| M15 | Descriptor HID estático (0x03×10, 0x01×8) con report DS3 (0x01×48) | `sdp_register.c:16-89` | ⬜ (host estricto podría rechazar; MC no lee el descriptor) |
| M16 | Flechas del d-pad dibujadas con el paint equivocado (`tp` vs `p`) | `ButtonOverlayView.java:161-164` | ⬜ |
| M17 | `InputDevice.getDeviceIds()` es API 16 con `minSdk=10`; `catch(Exception)` no atrapa `NoSuchMethodError` | `MainActivity.java:2273-2286` | ✅ guard `SDK_INT >= 16` + `catch (Throwable)` |
| M18 | Versión `0.1.0` (manifest) vs `0.2.0` (código) vs `0.63-dev` (docs) | `AndroidManifest.xml`, `MainActivity.java` | ✅ ambos `0.2.0` (versionCode 1) |

## 4. Bajos

| ID | Hallazgo | Estado |
|---|---|---|
| B1 | 9 headers duplicados en `src/daemon/jni/bluetooth/` (copias muertas) | ⬜ |
| B2 | Imports sin uso (`DiagnosticsScreen`, `GameScreen`, `SystemSetupScreen`) | ⬜ |
| B3 | `MODE`/comandos por prefijo (`strncmp`) aceptan `STARTbasura` | ⬜ |
| B4 | `find_adapter_dir()` acepta cualquier dir de 17 caracteres como MAC | ⬜ |
| B5 | Firma debug (keystore en `build/`, SHA1withRSA) | ℹ️ por diseño; **nota**: limpiar `build/` cambió la firma una vez; se restauraron prefs y el keystore nuevo es estable |
| B6 | Comando root con `nativeLibraryDir` sin comillas | ⬜ |
| B7 | JSON de SD leído sin límite → OOM | ⬜ |
| B8 | `GameScreen.java:182-184` coordenadas fijas 854×480; runnable `refresh` vacío | ⬜ |
| B9 | `build.sh` sigue imprimiendo "assets: parche del framework" | ✅ mensajes del build en inglés; ya no menciona P1 |

## 5. Investigación sin-root (E30) — conclusiones documentadas

1. Mi diagnóstico inicial del GID era **erróneo**: los GIDs de permiso son `net_bt_admin=3001` / `net_bt=3002`, y sí se asignan. El `1002` es el **usuario** `bluetooth`, dueño de los sockets. Corregido en `docs/08 E30`.
2. `/dev/socket/bluetooth` (SDP) y `/dev/socket/dbus` son `660 bluetooth:bluetooth` (1002:1002); **ningún permiso de terceros mapea al grupo 1002** (`platform.xml` solo tiene net_bt, net_bt_admin, inet, camera, log, sdcard_rw, cache, input, diag) → `EACCES` real e inevitable.
3. SDP por L2CAP PSM 1 (BDADDR_LOCAL / ANY / MAC propia) → **timeout**: este `bluetoothd` solo sirve SDP por socket unix.
4. No hay API de framework para registrar registros SDP (`strings classes.dex` → solo `setTrust`).
5. Sí funcionan sin root: `bind()` L2CAP 0x11/0x13, HCI raw (open+bind), y `BluetoothDevice.setTrust(boolean)` es **API oculta existente** (vía system_server).
6. Conclusión: **root inevitable en stock 0.62**; viable un **modo híbrido** (daemon sin root para HIDP/HCI + helper root solo para SDP en el emparejamiento).

## 6. Checklist de cierre

- [x] 5 críticos corregidos, build limpio, APK instalado conservando prefs
- [x] Auditoría documentada (este archivo) + E26/E27/E28/E29/E30 en `08`
- [x] Corregir A4 (EIR)
- [x] Validar con sonda si HCI **write** funciona con UID de app
- [x] Implementar modo híbrido como **opt-in** (root por defecto)
- [x] A5, M5, M11, M13, M17, M18 y B9 corregidos; [ ] A6/A7/A8/A9 y M1/M3/M4/M6-M10/M15/M16/B1-B8 siguen como deuda conocida (ver §20)
- [ ] Revisión final de logcat con el usuario (el usuario prueba en el dispositivo)

**Actualización híbrida:** check 8 confirma HCI-write permitido con UID de app → el modo híbrido es viable: daemon sin root (HIDP/HCI/UDS) + helper root efímero para SDP/trusted. Se implementa opt-in con root por defecto y fallback automático.

## 7. Modo híbrido implementado (opt-in, E30)

- `Ajustes → Daemon sin root (experimental)`: toggle `daemon_hybrid` (**desactivado por defecto**: el modo root actual no cambia).
- `xpdsetup.c` (helper root efímero, empaquetado como `libxpdsdp.so`): registra SDP HID + Device ID del protocolo y ejecuta `trusted_sweep`; se invoca con `su -c` solo en emparejamiento/cambio de protocolo/BT ON / apply-revert.
- `libxpd.so` corre como **UID de app** con `--uid <uid> --no-trust`: UDS (autenticado), HIDP L2CAP 0x11/0x13 y HCI CoD/EIR (capacidades validadas por la sonda E30). `--no-trust` desactiva en el daemon todo lo que requiere root (trusted + re-registro SDP).
- El receptor de `ACTION_BOND_STATE_CHANGED` → `BOND_BONDED` dispara `runSdpSetup()` para hosts nuevos.
- **Fallback automático**: si el daemon sin root no publica el UDS, se desactiva la opción y se relanza el daemon root una vez.
- Sin cambios en el flujo de conexión: el SDP sigue siendo el mismo (mismo VID/PID y CoD), solo lo ejecuta un proceso root de corta vida en vez del daemon residente.
- Pendiente de validación por el usuario: PC y Switch en modo híbrido; revisar `xpdsetup.log` (debe decir `registro SDP OK`) y logcat (`UID app, sin root`, `xpdsetup(root) rc=0`).

## 11. Segunda auditoría (carreras y UX) — estado

- [x] Carrera #1/#2: socket de sondeo `@xperiapadctl` separado (no desplaza al cliente principal) + `connGen` en Java (un lector viejo no cierra el socket nuevo ni cambia `connected`).
- [x] Carrera #3: `daemonOps` executor de un hilo serializa ensure/start/stop/setBtEnabled; `stopDaemonNow` síncrono para el cierre.
- [x] Carrera #4: `runSdpSetup()` en `sdpOps` (nunca en UI → sin ANR).
- [x] Carrera #5: `MODE` en híbrido ya no re-registra SDP en el daemon (solo lo hace el helper).
- [x] Carrera #9: guard de sesión HIDP movido antes del re-registro SDP en `trusted_watch`.
- [x] Carreras #6/#7: import serializado con elRace del executor + flags de estado publicados desde el hilo; `setupDetected` se escribe en el hilo de detección.
- [x] Carrera #11: los sondeos ya no generan NEUTRAL al cerrar (socket separado).
- [x] UX: protocolo se aplica solo después de validar perfil; aviso de re-emparejamiento conservado.
- [x] UX: export/import informan resultado real (`sdOpOk/sdOpMsg`); import pide confirmación al sobrescribir; rename avisa.
- [x] UX: `onActivityResult` corrige el switch de "descubrible" si se cancela el diálogo.
- [x] UX: botón de Configuración en Diagnóstico con listener; apply/revert llaman `onDone` incluso en returns de error.
- [x] UX: BACK sale del modo juego/editor; aviso al apagar BT con daemon activo; flechas del d-pad visibles; versión unificada 0.2.0.
- [x] Ciclo de vida: gracia 120 s para autostop del daemon sin cliente; `DaemonService` con notificación permanente y acción Detener (visible en Servicios en ejecución); cold-start exige activación manual si no hay daemon vivo; `onDestroy/isFinishing` para el daemon.
- [ ] Validación final en dispositivo (PC/Switch) por el usuario + revisión de logcat.

## 12. Regresión del switch gris al abrir — resuelta

- Causa: `onCreate` pintaba Home con `setupBtPatch="?"` (cache *patched*) y el `onDone` de `runSetupDetection` no repintaba al terminar → `swDaemon.setEnabled(bt && patchedEnough())` quedaba en false hasta otro `refreshStatus`. Verificado en dispositivo: `DisablePlugins=1`, `last_patched_state=si`, BT encendido.
- Fix: `refreshStatus()` al final del `handler.post` del `onDone` (`MainActivity.java`). Una línea; build e instalación OK.

## 13. Híbrido: PC veía un teléfono + descubrible intermitente — corregido

- **Causa híbrido**: en `xpd.c` el auto-start usaba `if (sdp_register_all()==0 && hidp_listen()==0)`: con UID de app el SDP da EACCES y el cortocircuito impedía abrir PSM 0x11/0x13 → sin HIDP (stats `intr=0`). Además `Write_Scan_Enable`/CoD/EIR dan EPERM con UID de app (mi sonda v5 solo verificó **enviar** un comando de lectura, no escribir).
- **Fix**: `hidp_listen()` desacoplado del SDP; en híbrido el daemon marca `active=1` aunque `sdp_register_all()` falle (el helper root ya registró SDP) y no intenta CoD/EIR. `xpdsetup` (root) escribe ahora CoD `0x000508` + EIR.
- **Descubrible**: eliminado el `SCAN 1` optimista del daemon; siempre se usa `ACTION_REQUEST_DISCOVERABLE` con `startActivityForResult`, y `onActivityResult` marca el estado real (cancelado → apagado; resultCode > 0 → segundos reales). Antes la app decía "descubrible" aunque `Settings` mostrara lo contrario.

## 14. Doble diálogo de descubrible — resuelto

- Causa: `HomeScreen.paint()` usaba `setChecked()` sobre cbDisc/swBt/swDaemon, lo que dispara sus listeners igual que un toque de usuario: feedback loop → dos `setDiscoverable(true)` (y posiblemente `setDaemonEnabled(false)` espurio, explicando el toggle intermitente anterior).
- Fix: guard `updatingUi` en `paint()` para los tres controles + `discDialogPending` en MainActivity (un solo diálogo en vuelo, liberado en `onActivityResult`).

## 15. Eliminación del modo híbrido (E31)

- **Estado final:** RETIRADO. No queda código, toggle, helper ni artefacto en el APK.
- **Razón:** PSM HID reservados (0x11/0x13), SDP y HCI write requieren root en stock 0.62; sin HIDP no hay input aunque el PC esté "conectado". Ver E31.
- **Se conservan:** socket `@xperiapadctl`, generación UDS, executors serializados, servicio ancla + notificación, autostop 120 s, cold-start manual, y todas las correcciones de carrera/UX de las secciones 11-14.
- **Nota histórica:** las secciones 7/11/13/14 describen el híbrido como existente en su momento; léanse como histórico, no como estado actual.

## 16. Reducción de solicitudes root (sin tocar apply/revert)

- Detección de arranque: `main.conf` se lee directamente (`644`, sin su); `id` se comprueba **solo al abrir Configuración del sistema** (`ensureRootCheck`, una vez, cacheado). Abrir la app = 0 avisos root.
- Arrancar daemon: `killall` + lanzamiento en **una sola** orden `su -c`; se eliminó el `su killall` previo.
- Parar daemon: `STOP` por UDS desde `stopExistingDaemon` y desde `DaemonService`; `su killall` solo como fallback si el daemon sigue vivo (`PING` por `@xperiapadctl`).
- Exportar/importar SD: ya era una sola llamada; sin cambios.
- **Apply/revertir BT-Patch se deja tal cual** (petición explícita): sus múltiples `su` permanecen.

## 17. Internacionalización (EN base + ES) — primera tanda

- **Decisión:** recursos Android (`res/values/` = inglés base, `res/values-es/` = español). La app sigue el idioma del sistema por defecto; en Ajustes hay un radio **Idioma / Language** con `Seguir el sistema | Español | English` (pref `locale_mode`).
- **Mecánica:** `applyLanguage()` usa `Locale.setDefault` + `Resources.updateConfiguration` (API 10); `configChanges` incluye `locale`; `onConfigurationChanged` reaplica y repinta en caliente (`Screen.invalidate()` + etiquetas del drawer + `setScreen`) sin reiniciar la Activity, así que el daemon y el socket sobreviven.
- **Cobertura:** 165 claves EN/ES; ~150 literales de UI sustituidos por `getString(R.string...)`; cadenas dinámicas convertidas a `%1$s`/`%2$d`.
- **Logs en inglés único (decisión del usuario):** 72 `appendLog`/`setupLog` (Java) y 69 `xpd_log` (C) traducidos. Los tokens de protocolo (`OK`, `ERR`, `active=0`, `MODE`, `SCAN`) no se tocan: ningún código depende del texto en español.
- **Firmas de log actualizadas:** p. ej. `auto-start: gamepad mode active`, `HIDP listening on PSM 0x%x`, `HID registration 0x%05x OK`, `no client for 120s: stopping daemon`, `discoverable confirmed by system: %d s`, `su failed (rc=...)`. Las secciones anteriores citan las firmas en español: léanse como históricas hasta la traducción completa de docs (fase 2).
- **Sin cambios:** Bluetooth, mapas, protocolo, daemon (solo strings de log) ni firma del APK.

## 18. SDP cacheado en el host — mitigado solo en el Xperia (H3)

- **Causa:** el host resuelve SDP al levantar el enlace. Si el daemon registra/re-registra los records con el ACL ya vivo, BlueZ no vuelve a abrir HIDP hasta un `disconnect` + `connect` manual (`intr=0` mientras tanto).
- **Capa 1 — perfil listo antes del enlace:** `BootReceiver` atiende `BOOT_COMPLETED` y `ACTION_STATE_CHANGED(STATE_ON)`, lanza `DaemonService` y el daemon con `DaemonStarter` (una sola `su -c`, respeta `daemon_enabled` y `last_patched_state`). Requiere `RECEIVE_BOOT_COMPLETED`.
- **Capa 2 — no invalidar SDP sin cambio:** `xpd.sdp.state` guarda `VID:PID`; el auto-start solo reutiliza los records si la identidad coincide **y** `sdp_our_records_present()` confirma en el server SDP local que `0x20000` (HID) y `0x20001` (DI) siguen registrados. Si falta cualquiera, re-registra ( BootReceiver borra el archivo en boot/BT ON; `BTRESTART` lo actualiza). Log: `auto-start: gamepad mode active (SDP reused|registered)`. Corrección §21: el archivo por sí solo daba un `reused` falso tras STOP.
- **Capa 3 — reconexión controlada:** botón **Reconectar gamepad** en Home envía `LINKRESET` por UDS; el daemon enumera ACL con `HCIGETCONNLIST` y baja cada enlace con `HCI_Disconnect` (`0x13`, fire-and-forget) mediante `hci_disconnect_acl()`. El host emparejado vuelve a conectar y resuelve SDP de nuevo. Es manual a propósito: evita cortar AVRCP ni depender de auto-reconnect de cada host.
- **Ancla:** `DaemonService` hace `PING` por `@xperiapadctl` cada 20 s sin ocupar el socket principal, para que el perfil siga disponible en background. **Corrección 2026-11-24:** el `PING` del ancla NO cuenta como cliente real (antes impedía el autostop de 120 s); al detener la ancla, el daemon se apaga solo.
- **Sin cambios en el host:** ni `main.conf`, ni reglas, ni scripts del PC. Build e instalación OK; queda validación en dispositivo con `OFF→ON` y el botón nuevo.

## 19. Licenciamiento GPLv3 + créditos

- **Decisión:** distribuir el conjunto como **GPLv3** (`LICENSE`). Las 23 cabeceras BlueZ copiadas dicen `GPLv2 or later`, así que la elección de versión posterior es válida; sus copyrights y cabeceras originales quedan intactos (`NOTICE`).
- **Material propio:** `Copyright (C) 2026 Jalm24` + `SPDX-License-Identifier: GPL-3.0-or-later` en C/Java/build/docs propios, con cláusula adicional **§7(b)**: conservar la atribución en archivos modificados y avisos legales. No se añadió esa cláusula a archivos BlueZ.
- **Créditos:** `CREDITS.md` (ES) y `CREDITS-en.md` (EN) en la raíz, separados de `NOTICE`: `NOTICE` es la parte legal; los créditos reconocen a Jalm24, a los autores de BlueZ/MissionControl, a OpenCode como agente de código y a quienes probaron el dispositivo. La pantalla de la app selecciona el asset según `Locale.getDefault()` (pref `locale_mode` ya aplicado).
- **APK:** `src/build.sh` copia `LICENSE`, `NOTICE`, `CREDITS.md` y `CREDITS-en.md` a `assets/legal/` para que viajen dentro del paquete.
- **Verificación:** build e instalación OK; `unzip -l` confirma `assets/legal/`; los 23 archivos upstream siguen sin SPDX propio.

## 20. Auditoría 2026-11-24 — 15 hallazgos y cierre

> Estado: **corregidos en código**; pendiente de validación en dispositivo por el usuario.
> No se tocaron apply/revert de BT-Patch ni la identidad de protocolo DS3/`0001:0001`.

### P1 (funcionamiento real)

| # | Hallazgo | Fix aplicado |
|---|---|---|
| 1 | `dbus_trusted.c`: `AUTH EXTERNAL 0` con longitud 17 **y sin el NUL inicial**; además VARIANT alineado a 8, body sin alinear, padding extra en el variant y falta de `Hello` → el daemon D-Bus cortaba la conexión; caía el fallback `killall bluetoothd` | NUL inicial + UID hex (`30`) + fields `yv` alineados solo al inicio + body alineado a 8 + sin padding extra en `g("b")` + `Hello` (serial 1, SetProperty serial 2). **Validado en el dispositivo**: `SetProperty("Trusted", true)` devuelve METHOD_RETURN en `/org/bluez/<pid>/hci0/dev_…` |
| 2 | `MappingScreen`: “Guardar cambios” abortaba si no había perfil editable → imposible elegir DS3 en instalación nueva | `active_protocol` se guarda antes de validar el perfil; avisos específicos `map_proto_saved_*` ES/EN |
| 3 | `keepScreenOn=false` ignorado al arrancar | `applyKeepScreenOn()` sustituye al `FLAG_KEEP_SCREEN_ON` incondicional |
| 4 | Autostop de 120 s nunca disparaba: el `PING` del ancla contaba como cliente | `xpd.c` solo marca `had_client/last_client_ms` para comandos que no son `PING` |
| 5 | `docs/README.md` describía P1 aplicado, sin perfiles y sin autostart | Estado real reescrito: framework stock, perfiles/UX, H3, DS3 |

### P2 (consistencia / “se aplica sin Guardar”)

| # | Hallazgo | Fix aplicado |
|---|---|---|
| 6 | `AjustesScreen`: duración e idioma se aplicaban al tocar | borrador + **Guardar / Restablecer**; `onInvalidate()` suelta referencias |
| 7 | `ControlsScreen`: sliders, invertir-Y y arrastres persistían solos | `draftSticks`; único punto de persistencia = botón **Guardar** |
| 8 | `MappingScreen`: remapeo persistía al instante; etiqueta “XInput (generic)” engañosa | `draftKeyMap` aplicado en Guardar; etiqueta `Generic HID` ES/EN |
| 9 | `HomeScreen.update()` llamaba `setDiscoverableOff()` como efecto secundario y `discLabel` quedaba fuera del guard | efecto eliminado; `discLabel` protegido contra `onInvalidate()` |
| 10 | Toast español quemado en `MainActivity`, `Uso:` en `xpd.c`, build en español | `toast_bt_off_daemon_stopped` ES/EN, `Usage:` con `--uid`, mensajes de build en inglés |

### P3 (seguridad / robustez / deuda)

| # | Hallazgo | Fix aplicado |
|---|---|---|
| 11 | `DAEMON_STOP` / `ACTION_STOPPED` falsificables por cualquier app | permiso `signature` `com.jalm.xperiapad.permission.DAEMON_CONTROL` en el manifest; receiver y broadcast lo exigen; BT queda en receiver separado sin restricción |
| 12 | `xpd.c`: `continue` del error `MODE` reprocesaba el buffer | `start = i + 1` antes del `continue` |
| 13 | Salir de juego fijaba la orientación física detectada | se restaura la preferencia previa; `UNSPECIFIED` → `SCREEN_ORIENTATION_SENSOR` |
| 14 | `pm clear` dejaba el daemon huérfano; Toast de `GameScreen` se repetía al reconstruir | `setDaemonEnabled(false)` antes del wipe; flag `toastShown` en `GameScreen` |
| 15 | Textos obsoletos (A2/M5/M11/B9, híbrido, gamestick) | filas y docs actualizados; las secciones 7/11/13/14 quedan marcadas como históricas |

### No considerados defectos

- `MappingScreen.pickRename()` continúa tras aviso de nombre duplicado: `map_name_exists` dice explícitamente que el perfil “se sobrescribirá”.
- Logs y protocolo: tokens `OK`, `ERR`, `active=0`, `MODE`, `SCAN`, `START`, `STOP`, `PING`, `ARM`, `DISARM`, `BTRESTART`, `REASSERT`, `LINKRESET` intactos.

### Verificación en dispositivo (2026-11-24, log revisado)

1. ✅ `auto-start: gamepad mode active (SDP reused)` tras `OFF→ON`; sin `killall bluetoothd` en los arranques nuevos. **Matiz:** ese `reused` era falso y rompía HIDP; ver §21.
2. ✅ `no client for 120s: stopping daemon` a las 16:23:25 — el autostop ya dispara con el ancla retirada.
3. ✅ D-Bus trust a las 16:41:17: `trusted: 78:2B:46:4E:0A:24 set via D-Bus at /org/bluez/17963/hci0/dev_78_2B_46_4E_0A_24 (no stack restart)` + `D-Bus trust initial apply OK`. Sin `killall bluetoothd`, sin `Connection reset by peer` y sin ciclo BT OFF→ON.
4. ⏳ Ajustes: cambiar valores, pulsar **Restablecer** y confirmar que no se aplicaron; pulsar **Guardar** y confirmar que sí.

## 21. Regresión 2026-11-24 — `SDP reused` sin records (HIDP no abre)

- **Síntoma del usuario:** el PC empareja y muestra `Connected: yes` / `Icon: input-gaming`, pero no aparece `/dev/input/js0`; `jstest` y el navegador no ven botones. `intr=0` en el log del daemon.
- **Evidencia:**
  - `bluetoothctl` del host: HID UUID presente (caché), sin nodo `input` en `busctl tree org.bluez`, `Modalias=usb:v0001p0001d0100`.
  - `journalctl -u bluetooth`: `hidp_add_connection() Could not parse HID SDP record: No such file or directory (2)`.
  - Log del daemon: `16:40:58 STOP` → `SDP removed` de `0x20000`/`0x20001`; `16:41:01 auto-start ... (SDP reused)` porque `xpd.sdp.state` seguía con `0001:0001`.
  - Probe ARM local contra el SDP server del teléfono: `HID total=0`, `DI total=0` (records realmente ausentes).
- **Causa:** el archivo de estado solo guardaba VID/PID, no si los records seguían vivos. STOP los borraba y el reinicio los daba por buenos.
- **Fix:** `sdp_our_records_present()` en `sdp_register.c` busca los UUIDs HID y PnP en el SDP server local y exige los handles `0x20000`/`0x20001`; el auto-start añade esa comprobación a `sdp_state_matches()`. Si faltan, limpia y re-registra.
- **Validado en dispositivo + PC (17:16–17:18 log, 19:50 host):** `17:16:00 auto-start: gamepad mode active (SDP registered)` → `HIDP control/interrupt connected`; `LINKRESET` desconectó 1 ACL y la reconexión desde el PC volvió a abrir ambos canales. `intr=1` estable. `/dev/input/js0` creado (`Xperia PLAY`, `Bus=0005 Vendor=0001 Product=0001`, `js0/event21`) con **6 ejes y 16 botones**; sin nuevos `Could not parse HID SDP record` en `journalctl -u bluetooth`. **Confirmado por el usuario: los botones responden en juego/navegador.**

## 22. Stack check no bloqueante (v0.2.1)

- **Cambio:** `ROM_ID` deja de ser gate. `runSetupDetection()` comprueba las capacidades que consume `libxpd.so`: `/dev/socket/dbus`, `/dev/socket/bluetooth`, plugin `input` de BlueZ 4, `/etc/bluetooth/main.conf`, fuente `SOURCE_TOUCHPAD` (solo API ≥ 16) y build/kernel como referencia. Los resultados se loguean en inglés (`stack probe: ...`) y, si algo difiere, `unverified stack: may fail horribly (...)`.
- **No bloquea:** `canApply = root && !BT-Patch` y `patchedEnough()` siguen dependiendo solo de root y de `DisablePlugins = input`. Un build/kernel distinto solo pinta la línea ámbar en Configuración del sistema.
- **Validado por el usuario en dispositivo (18:54–18:55):** `HIDP control connected` + `HIDP interrupt connected`, `intr=1` estable, UI del stack check correcta ("funciona a la perfección"). Tras la prueba el daemon recibió `cmd: STOP` a las 18:55:16 y se retiraron los records SDP, por lo que `js0` desaparece hasta el próximo `OFF→ON` + reconexión: comportamiento esperado.
