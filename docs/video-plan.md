# Plan: vídeo en el Panel (`play_video`)

> Documento de trabajo para continuar donde lo dejamos (2026-11-26). Escrito desde el lado de
> Video Assistant (VA); los caminos de VA ya están construidos y probado que funcionan — lo que
> falta está casi todo del lado del firmware.

## 1. Objetivo

Que el build `panel` de echod exponga las mismas acciones de vídeo que el build del Show
(`play_video` / `stop_video` / `pause_video` / `resume_video`) y los mismos sensores
(`video_state`, `video_error`, `video_title`), de forma que Home Assistant —y en concreto
Video Assistant— pueda mandar películas al Panel (Meta Portal 2ª gen) igual que al Despacho.

**Por qué es factible**: el Portal es Android 10 de calle; el hardware y el sistema decodifican
de sobra (Netflix sideloaded funciona, el navegador del panel reproduce el reproductor web de VA
con sonido e imagen — comprobado en vivo). Lo único que falta es el cable entre «HA manda una URL»
y «Android la decodifica»: hoy el firmware lo tiene cortado en tiempo de compilación.

## 2. Estado actual (evidencia, HEAD techo5 `5730b33`)

El Panel **no puede** reproducir vídeo hoy, y está cortado en tres sitios:

| Qué | Dónde | Detalle |
|---|---|---|
| Sin reproductor | `echod/internal/feature/video/here_panel.go` | `const Here = false` — «the app has none». Con `Here=false`, `feature.go:25` (`if Here`) no registra acciones ni sensores ni switches. |
| DLNA rechaza vídeo | `echod/internal/feature/dlna/renderer.go:220` | `if s.video && !video.DLNAOn() { return errNotVideo }` → UPnP 714 «Illegal MIME-type» para cualquier MIME `video/*`. Medido en vivo contra el panel real (SOAP directo a `:8181/dlna/AVTransport/control`). |
| `DLNAOn()` a false | `feature.go:738` | `c.On && c.DLNA && Installed()`, y `Installed()` (`proc.go:108`) busca el binario techo5-ffmpeg que el APK del Panel no lleva. |

Además: el DLNA del Panel es un **altavoz** (mp3/wav/flac). Su `device.xml` se autodenomina
«TECHO5 speaker». Las cámaras de HA en el Panel son **snapshots** (`camera.go`: «decoded in the
daemon one after another»), no vídeo. Nada de eso da vídeo; hay que añadir el camino nuevo.

## 3. Topología de forks — IMPORTANTE

Los cambios de daemon **no van en este repo**: `echod` vive en el repo madre
[HuskerMinion/techo5](https://github.com/HuskerMinion/techo5) (paquete `echod/internal/feature/video`).
Este repo solo aporta el build `panel` y la app.

1. Forkear también **techo5** → `adocampo/techo5`, clonar en `~/development/techo5` (o donde sea).
2. `tools/build-apk.sh [ruta techo5]` acepta la ruta como argumento, o `$TECHO5`, o `../techo5`
   por defecto. Tener el fork de techo5 al lado (`../techo5`) hace que el build lo coja sin flags.
3. Branches de trabajo: `video` en ambos repos. Los commits de daemon llevan el tag de estilo del
   repo madre; los de aquí, el de este repo.
4. Antes de tocar nada: **Fase 0** es que el build tal cual funcione desde los forks (baseline).

Nota upstream: merece la pena abrir un feature request en `HuskerMinion/techo5-panel` (y/o techo5)
con este mismo diseño — el hueco está deliberadamente marcado en el código («the switches and the
play_video actions would do nothing there»), así que el autor ya pensó dónde iría. El fork es el
laboratorio; si upstream lo acepta mejor.

## 4. Arquitectura del feature de vídeo (lo que hay que respetar)

API pública del paquete `video` (la consumen el renderer DLNA, `feature/display` y el registro
ESPHome) — ver `feature/video/api.go`:

- `Play(Request{URL, Title, Origin, Start}) (uint64, error)`, `Stop/StopID/Pause/Resume`,
  `Seek(id, dur)`, `Seekable(id)`, `Current() State`, `SetOn/SetDLNA`, `Listen(fn)`.
- `Actions() []*esphome.Action` → `play_video(url,title)`, `stop_video`, `pause_video`, `resume_video`.
- Sensores ESPHome (nombres exactos, VA depende de ellos): `video_state` (idle/loading/playing/
  paused), `video_error` (texto del último fallo), `video_title`.
- `State{Phase, ID, Title, Pos, Dur, Ended, Failed, Err, ErrAt, ...}` — el renderer DLNA lo usa
  entero para `GetPositionInfo`/transport state.
- `DLNAProtocols` (`api.go`, final del fichero): los MIMEs de vídeo que el renderer anunciaría si
  `DLNAOn()` fuera true — mp4, mkv, mp2t, quicktime, m3u8. Coinciden con lo que VA sabe servir.
- Origen: `FromHomeAssistant` (acción ESPHome, no pregunta en pantalla) vs `FromDLNA` (pregunta).

**Diseño elegido (Opción B)**: no parchear el pipeline de ffmpeg de `feature.go` (Frame/next/Done,
refit, seek por pipe — todo casado con techo5-ffmpeg). En su lugar, dar al panel su propia
implementación del paquete, igual que hace el Dot con `none.go`:

- Cambiar los build tags de `feature.go` & cía de `!dot` a `!dot && !panel`.
- Nuevo `panel.go` (`//go:build panel`) con una implementación **fina**: mismo API, mismo
  `State`, mismas acciones y sensores, pero en vez de arrancar ffmpeg **manda la orden a la app
  por el socket `techo5.ctl`** y el estado le vuelve de la app.
- `Here = true` en `here_panel.go`; `Installed()` → true en el panel (el decodificador es del
  sistema); `DLNAOn()` queda `c.On && c.DLNA && Installed()` sin cambios → funcionará.

Así el renderer DLNA, los sensores, las acciones ESPHome y VA funcionan **sin tocarlos**.

## 5. El canal: `techo5.ctl`

El socket de texto `techo5.ctl` (`ControlLink.java` en este repo) ya es el bus de comandos
bidireccional entre echod y la app (volumen, updates, abrir apps/ajustes). Formato: una línea,
primera palabra = comando. Añadir:

**echod → app**
```
video play <url> <title>     (title puede venir vacío: usar "" como en apps item)
video pause
video resume
video stop
video seek <segundos>        (fase 4, opcional)
```

**app → echod**
```
video state <id> <phase>     phase: loading|playing|paused|idle
video pos <id> <pos_s> <dur_s>     cada ~5 s mientras play (0 en dur si live)
video ended <id>
video error <id> <texto>
```

Reglas: un vídeo a la vez (un `id` nuevo por `play`, monótono); la app responde `video state`
siempre que cambie algo y también cuando echod reconecta (el ctl se cae y rehace; el estado tiene
que resincronizar). En el daemon, un `watchdog` marca `Failed` si tras `play` no llega `state
loading|playing` en ~15 s (app muerta, URL inalcanzable).

## 6. La app (este repo, Java puro, sin Gradle)

- **`VideoLink.java`** (o extensión de `ControlLink`): parsea las líneas `video *` y pilota la
  actividad. `LocalPeer` ya garantiza que solo conecta el usuario de la app.
- **`VideoActivity.java`**: a pantalla completa, `sensorLandscape`, `keepScreenOn`, wacklock.
  - **v1: `MediaPlayer` + `VideoView`** — cero dependencias (sin Gradle, sin AARs), y cubre de
    sobra el contrato de VA: h264+AAC en MP4 progresivo y MPEG-TS (y MKV h264). HLS de VA no hace
    falta (VA normaliza a TS/MP4 para aparatos).
  - v2 si hace falta (subtítulos hard, formats raros): media3/ExoPlayer — implica bajar los JARs
    y d8-earlos en `build-apk.sh`; evitar hasta que duela.
- **Audio**: el audio del Panel ya es el media volume de Android (`ControlLink` «volume»). Al
  arrancar vídeo, el daemon debe hacer `media.Get().Stop()` (pausar MA/DLNA/radio, igual que en
  el Show) — eso pasa en `panel.go`, no en la app. La app pide audio focus normal y lo suelta al
  terminar.
- **Fin/error**: `onCompletion` → `video ended`; `onError` → `video error <texto>` (texto
  legible: VA lo muestra y lo usa para auto-remedio). Al terminar, cerrar la actividad (volver al
  clock).
- **Manifest**: registrar `VideoActivity` (theme fullscreen, `launchMode=singleTask`,
  `excludeFromRecents`). No hacen falta permisos nuevos (INTERNET y WAKE_LOCK ya están — verificar
  WAKE_LOCK en `AndroidManifest.xml`).
- **Tocar durante el vídeo**: v1 = un toque muestra/oculta una barra mínima con Stop (el control
  fino viene de HA/VA). No reutilizar el protocolo de pantalla: el vídeo no pasa por ahí.

## 7. El daemon (fork de techo5)

1. `feature/video/panel.go` (`//go:build panel`): implementación fina del API (§4). Guarda
   `State`, reparte acciones/sensores (copiar el bloque `Actions()` y los `TextSensor` de
   `feature.go` — extraerlos a un fichero compartido si facilita el diff), habla por ctl.
2. Ajustar build tags de `feature.go`, `proc.go`, `pipe*.go`, `seek*.go`, `guard.go`, `fence.go`,
   `session.go`, `camera.go`… de `!dot` a `!dot && !panel` donde no aplique en panel (el panel no
   tiene ffmpeg ni decodificador propio; `camera.go` sí aplica si la cámara del panel sigue igual
   — revisar uno a uno, `go vet -tags panel ./...` dirá).
3. `here_panel.go`: `Here = true` + comentario nuevo.
4. `Installed()`: en panel → true (decodificador del sistema).
5. `feature/display/video.go` (build `!dot`): usa `video.Get().State()`, `Asking()`, `Covered()`,
   seek-bar dibujada… Compilará igual (mismo API); en el panel esas rutas deben ser inocuas —
   verificar que con vídeo activo el canvas dibujado no intenta cubrirse con frames (no los hay:
   `next()` nunca produce frames en panel). Posible ajuste fino: en panel, `Covered` no debe
   apagar el canvas mientras el vídeo está en la app por encima.
6. Consentimiento DLNA (`FromDLNA` pregunta en pantalla): en v1, **auto-permitir** en panel (red
   LAN de confianza, y no hay UI de pregunta dibujada que usar). Apuntar como deuda si upstream
   quiere la pregunta.
7. `media.Get().Stop()` al arrancar vídeo (patrón ya visto en `dlna/renderer.go: set`).

## 8. Integración con Video Assistant — cero código en VA

VA ya soporta exactamente esta forma (es lo que usa con el Despacho). Al terminar el fork, editar
el player del panel en VA (BD `players`, `config_json`) de:

```json
{"media_player_entity": "media_player.techo5_panel_540a85", ...}
```

a (kind `techo5`, mismo patrón que player 1):

```json
{"play_service": "esphome.techo5_panel_540a85_play_video",
 "stop_service": "esphome.techo5_panel_540a85_stop_video",
 "pause_service": "esphome.techo5_panel_540a85_pause_video",
 "resume_service": "esphome.techo5_panel_540a85_resume_video",
 "state_sensor": "sensor.salon_techo5_panel_540a85_video_state",
 "title_sensor": "sensor.salon_techo5_panel_540a85_video_title",
 "error_sensor": "sensor.salon_techo5_panel_540a85_video_error",
 "volume_entity": "media_player.techo5_panel_540a85_2"}
```

(ajustar nombres reales de entity tras el primer arranque con el fork — el dispositivo está
renombrado a «Panel», así que se espera `esphome.panel_play_video`, `sensor.panel_video_state`…) Con eso VA aplica tal
cual todo lo que ya tiene: decisión de directo con contenedor (perfil techo5: mp4/mov/m4v/mkv/ts),
normalización garantizada h264+AAC+TS, auto-remedio (`va/autoheal.py` usa `error_sensor` fresco),
minas aprendidas, seguimiento de posición y scrobble (`state_sensor`).

Contrato que VA espera del firmware (ya cumplido por el Show, el fork debe cumplirlo igual):

- `play_video(url, title)` — arranca; error → la acción falla **y** `video_error` se rellena.
- `video_state` — `idle|loading|playing|paused` (VA cierra sesiones en `idle`, reanuda en `paused`).
- `video_error` — texto no vacío con cada fallo nuevo; VA lo lee y normaliza/reintenta una vez.
- Posición: VA estima la posición por reloj desde su `started_at`; si el fork reporta `video pos`,
  se puede mejorar (fase 4) — no es bloqueante.
- El arranque con posición: VA lo resuelve por servidor (`start=` recorta en ffmpeg), la app no
  necesita `seek` para reanudar.

## 9. Fases

- [x] **F0 — baseline** (2026-11-26): forkeado techo5 → `adocampo/techo5` (el clon local aún
      apuntaba a upstream: ahora `origin`=fork, `upstream`=madre, `main` sincronizado a `b184a51`);
      `tools/build-apk.sh` compila e instala en el Portal. Lo que ha hecho falta fuera del plan:
      subir el pin de Go a 1.27.3 (el `go.mod` nuevo de echod exige ≥1.27; commit `7522da6` — upstream
      también lo necesita), clave de firma nueva (`~/.config/techo5-panel/`, con `PANEL_UPDATE_URL=none`
      en los builds locales), y `fetch-inputs.py` para los modelos. El APK oficial se ha desinstalado
      (firmas distintas). El dispositivo renombrado a **«Panel»** en HA: las entidades y servicios
      saldrán como `panel_*` / `esphome.panel_*`, no `techo5_panel_540a85_*`.
- [ ] **F1 — daemon**: `panel.go` del paquete video + build tags + `Here=true` + sensores.
      Criterio: `esphome.panel_play_video` aparece en HA y `video_state` existe;
      llamarla con una URL de VA de prueba no petar el daemon (aunque la app aún no haga nada).
- [ ] **F2 — app**: `VideoActivity` MediaPlayer + líneas ctl. Criterio: `esphome..._play_video`
      con `http://192.168.1.10:8099/files/8/...?stream=va&fmt=ts` → imagen+sonido en el Portal;
      stop/pause/resume funcionan; `video_state` sigue la verdad.
- [ ] **F3 — VA**: reconfigurar player 3 (§8) y probar de punta a punta: peli local rara (Xvid/AVI
      → normalizado), Plex directo (12 Monos), VOD Dispatcharr, pausa desde la web, «Seguir
      viendo», y el camino de fallo (URL inválida → `video_error` → autoheal no buclea).
- [ ] **F4 — pulido**: `video pos` → posición real en VA; `video seek` para DLNA seek; subtítulos
      si algún día; evaluar ExoPlayer solo si algo real se resiste; feature request upstream con
      el diff como argumento.

## 10. Decisiones abiertas

1. **¿También el Intercom FCC595?** Si corre la app Panel, hereda todo; si no tiene pantalla,
   marcarlo audio-only en VA igualmente.
2. Consentimiento en pantalla para `FromDLNA` (v1 auto-permite — ¿ok para ti?).
3. ¿Barra de controles en la app o todo desde HA? (v1: barra mínima con Stop.)
4. ¿Fork permanente o rama hasta que upstream mergee? (Sugerido: rama + PR.)

## 11. Referencias rápidas

- Este repo: `docs/plan.md` (arquitectura), `docs/screen-protocol.md` (socket de pantalla — NO
  usarla para vídeo), `app/src/app/techo5/panel/ControlLink.java` (el canal), `tools/build-apk.sh`.
- techo5: `echod/internal/feature/video/` (`api.go` = contrato, `feature.go` = referencia Show,
  `none.go` = patrón de implementación alternativa), `feature/dlna/renderer.go` (línea ~220),
  `feature/display/video.go` (consumidor del State).
- VA (`~/development/video-assistant`): `va/playback.py` (`_launch_on_ha` — respeta
  `play_service`), `va/autoheal.py`, `va/players.py` (perfil techo5), commit `0af4ac9`.
- Prueba SOAP del DLNA actual: `/tmp/opencode/dlna_test.py` (útil para verificar que tras el fork
  el renderer anuncia y acepta vídeo).
