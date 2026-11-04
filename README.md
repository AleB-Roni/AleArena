# ⚽ AleArena

Paper-Plugin (Minecraft 26.3), mit dem ihr zusammen ingame Streams schaut:

- **Leinwand** aus Karten in unsichtbaren, leuchtenden Item-Frames an einer Wand
- **Webseite im Server-Browser** (`/arena web`) – die Seite läuft in einem unsichtbaren Chrome auf dem Server
- **Klicken auf der Leinwand** = Mausklick auf der Webseite (Cookie-Banner, Play-Button, Vollbild)
- **Ton ingame** über Simple Voice Chat – kommt aus der Leinwand und wird mit Abstand leiser
- Alternativ: direkte Stream-URLs (`/arena play`, m3u8 / YouTube / Twitch) oder OBS vom PC (`/arena obs`)

Freunde brauchen **keine** Mods fürs Bild – nur für den Ton den Simple-Voice-Chat-Mod.

---

## 1. Jar bauen

Doppelklick auf **`upload.command`** (Mac) bzw. **`upload.bat`** (Windows).
Das lädt das Projekt nach `github.com/AleB-Roni/AleArena`, GitHub Actions baut automatisch,
die fertige `AleArena-1.0.0.jar` liegt danach unter **Releases → latest**.

## 2. Server vorbereiten (Unraid / Docker)

Der Server braucht zusätzlich: **ffmpeg, Google Chrome, Xvfb, PulseAudio, yt-dlp**.
Im Ordner `docker/` liegt ein fertiges Image auf Basis von `itzg/minecraft-server:java25`:

```bash
cd docker
docker compose up -d --build
```

(Unraid: per Compose-Manager oder Portainer-Stack mit diesem Ordner. Den `/data`-Pfad ggf. anpassen.)

Dann:
1. `AleArena-1.0.0.jar` nach `/data/plugins/` kopieren
2. Simple Voice Chat lädt das Compose automatisch (`MODRINTH_PROJECTS`)
3. Server neu starten
4. **UDP-Port 24454** für Voice Chat freigeben (Router-Weiterleitung, falls Freunde von außen kommen)

Eure Freunde installieren den **Simple Voice Chat**-Mod (Fabric/NeoForge) – dann hören sie den Ton aus der Leinwand.
Lautstärke regeln sie im Voice-Chat-Menü (Taste `V`) unter der Kategorie **AleArena**.

## 3. Benutzen

| Befehl | Was passiert |
|---|---|
| `/arena create tv 8 5` | Auf den Block **unten links** an einer Wand schauen → Leinwand 8×5 wird gebaut |
| `/arena web tv <url>` | Webseite im Server-Browser öffnen und anzeigen |
| `/arena play tv <url>` | Stream-URL direkt abspielen (m3u8, mp4, YouTube, Twitch …) |
| `/arena obs tv` | OBS-Empfang: in OBS Server `rtmp://<server-ip>:1935/live`, Key `alearena` |
| `/arena stop tv` | Stoppen, Leinwand wird schwarz |
| `/arena list` · `/arena remove tv` | Übersicht / Leinwand abbauen |

**Browser steuern** (`/arena web`):

| Aktion | So geht's |
|---|---|
| Klicken | Rechts- oder Linksklick auf die Leinwand |
| Scrollen | Schleichen + Rechtsklick (runter) / Schleichen + Linksklick (hoch) |
| Text eingeben | `/arena type tv mein suchbegriff` (vorher ins Feld klicken) |
| Taste | `/arena key tv enter` – auch `esc`, `tab`, `space`, `f` (Vollbild bei vielen Playern), Pfeiltasten |
| Andere Seite | `/arena go tv <url>` · `/arena back tv` · `/arena reload tv` |

Rechte: `alearena.admin` (OP) baut/startet, `alearena.control` (alle) darf klicken und tippen.
Wenn nur du klicken sollst: in LuckPerms `alearena.control` auf `false` setzen.

## 4. Feintuning (`plugins/AleArena/config.yml`)

- **`fps`** (Standard 10): flüssiger vs. Bandbreite. Faustregel pro zuschauendem Spieler:
  Breite × Höhe × fps × ~8 KB/s → 8×5 bei 10 fps ≈ 3 MB/s. Im Heimnetz kein Problem,
  für Freunde von außen eher 6×4 und 8–10 fps.
- **`video-delay-ms`**: Falls der Ton dem Bild hinterherhängt, Bild etwas verzögern (z. B. 200).
- **`voicechat.distance`**: Hörweite der Leinwand.
- **`browser.height`**: Auflösung der Webseite (Breite passt sich der Leinwand an).
- **`web-audio.enabled: true`**: zusätzlich Ton im Browser für Leute ohne Voice-Chat-Mod
  (Port 8765 freigeben, `public-url` eintragen, `/arena link`).

## Gut zu wissen

- Seiten mit Kopierschutz (DRM) zeigen teils nur Schwarz – dann lieber `/arena obs` vom PC.
  Bei Bezahldiensten (DAZN, Sky/WOW …) erlauben die AGB das Weiterzeigen an andere meist nicht.
- Die Karten-Farbpalette hat nur ~250 Farben – Dithering glättet das, aber es bleibt „Minecraft-Look“.
- Der Browser behält Cookies/Logins pro Leinwand in `plugins/AleArena/browser/profile-<name>`.
