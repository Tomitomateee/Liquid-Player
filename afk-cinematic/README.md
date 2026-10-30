# AFK Cinematic (Fabric 1.21.11)

Bist du eine Zeit lang AFK, übernimmt eine filmische Kamera: Sie kreist langsam um deinen Charakter,
schwenkt über die Umgebung, wechselt im Takt deiner Musik die Einstellung und blendet die Lyrics des
gerade laufenden Songs synchron ein, wie in einem Musikvideo. Jede Eingabe beendet den Modus sofort,
und die Kamera gleitet in ca. 0,5 s zurück.

Die Mod läuft **rein clientseitig** und funktioniert deshalb auf jedem Server. Der Spieler dreht sich
nie, bewegt wird nur die Kamera. Es geht also nichts an den Server, was ein Anti-Cheat bemerken könnte.

## Funktionen

| Bereich | Was passiert |
|---|---|
| AFK-Erkennung | Start nach X Sekunden ohne Tastatur, Mausklick, Scrollen oder Mausbewegung (Standard 60 s). Nicht bei offenem Menü/Chat/Inventar oder wenn du tot bist. Taste **K** startet/stoppt manuell. |
| Kamera | Orbit · weiter Schwenk über die Umgebung · Low-Angle von unten · Zoom von nah nach weit. Weiche Überblendungen (Easing), zusätzliche Glättung, Raycast-Kollision gegen Blöcke. |
| Beat | Systemaudio per WASAPI-Loopback, FFT, Onset-Erkennung im Bass (40–150 Hz), BPM-Schätzung. Auf jedem Beat ein kurzer FOV-Punch und ein Pulsieren der Lyrics; Einstellungswechsel alle *n* Takte und sofort bei Drops. Ohne Musik: ruhiger Orbit ohne Effekte. |
| Lyrics | Song, Künstler und Position aus der Windows-Media-Session (SMTC), also Spotify, YouTube im Browser usw. Synchronisierte Lyrics (LRC) von [LRCLIB](https://lrclib.net), im Speicher gecacht. Aktuelle Zeile groß im unteren Drittel, vorherige/nächste kleiner und transparenter, weiches Gleiten zwischen den Zeilen. Ohne Lyrics: Titelkarte. |
| Look | HUD ausgeblendet, Kinobalken fahren rein, optional Vignette, oben links Titel & Künstler. |
| Einstellungen | Über Mod Menu → AFK Cinematic → Konfigurieren (Cloth Config ist in der Mod enthalten), oder `config/afk-cinematic.json`. |

> Beat-Erkennung und Songerkennung brauchen **Windows 10/11** (WASAPI und SMTC). Auf anderen Systemen
> läuft die Kamera trotzdem, nur eben als ruhiger Orbit ohne Beat und Lyrics.

## Installation (fertige Jar)

1. [Fabric Loader](https://fabricmc.net/use/installer/) für **1.21.11** installieren.
2. In den `mods`-Ordner legen:
   - **Fabric API** für 1.21.11
   - optional **Mod Menu** (für den Einstellungs-Button)
   - `afk-cinematic-1.0.0.jar`
3. Minecraft mit dem Fabric-Profil starten.

Den `mods`-Ordner findest du unter Windows hier: `%appdata%\.minecraft\mods`
(Win + R drücken, `%appdata%\.minecraft` eingeben, Enter. Fehlt `mods`, einfach anlegen).
Bei Modrinth App, Prism, CurseForge usw. hat jedes Profil einen eigenen Ordner
(meist Rechtsklick auf das Profil → „Ordner öffnen“).

## Selbst bauen

Voraussetzung: **JDK 21** (z. B. [Temurin 21](https://adoptium.net/temurin/releases/?version=21)).
Gradle musst du nicht installieren, der Wrapper lädt es selbst.

```bat
cd afk-cinematic
gradlew build
```

Unter Linux/macOS stattdessen `./gradlew build`. Die fertige Mod liegt danach in
`afk-cinematic/build/libs/afk-cinematic-1.0.0.jar` (die `-sources.jar` brauchst du nicht).
Diese Datei in den `mods`-Ordner kopieren, fertig.

Zum Testen direkt aus der Entwicklungsumgebung: `gradlew runClient`.

## Einstellungen

| Option | Standard | Bedeutung |
|---|---|---|
| Bei AFK automatisch starten | an | aus = nur per Taste K |
| AFK-Zeit | 60 s | Zeit ohne Eingabe bis zum Start |
| Kamera-Abstand | 6 Blöcke | Grundabstand, die Einstellungen variieren davon ausgehend |
| Orbit-Geschwindigkeit | 100 % | Tempo aller Kamerabewegungen |
| Takte pro Einstellung | 4 | Wechsel des Kamera-Modus nach so vielen Takten |
| Beat-Stärke | 100 % | Stärke von FOV-Punch, Ruck und Lyric-Pulsieren (0 = aus) |
| Lautstärke-Schwelle | 0,015 | ab diesem Pegel gilt Ton als Musik |
| Lyrics anzeigen | an | |
| Schriftgröße | 2,0× | Größe der aktuellen Lyric-Zeile |
| Titel & Künstler oben links | an | |
| Kinobalken / Vignette | an / an | |

## Aufbau

```
src/main/java/dev/afkcinematic/
├─ AfkCinematicClient.java      Einstiegspunkt: Config, Taste K, Tick-Event
├─ cinematic/
│  ├─ CinematicController.java  AFK-Erkennung + Zustände (IDLE → ACTIVE → EXITING), Überblendungen
│  ├─ CameraDirector.java       Kamera-Regie: Shots, Crossfade, Glättung, Kollision, Beat-Punch
│  ├─ CameraPose.java / Easing.java
├─ audio/
│  ├─ LoopbackAudio.java        liest PCM-Frames der Loopback-Bridge (eigener Thread)
│  ├─ BeatDetector.java         FFT, Bass-Spectral-Flux, adaptive Schwelle, BPM, Drops
│  └─ FFT.java
├─ media/MediaSession.java      aktueller Song aus SMTC (eigener Thread)
├─ lyrics/                      LRCLIB-Client mit Cache (eigener Thread), LRC-Parser
├─ render/CinematicOverlay.java Kinobalken, Vignette, Lyrics, Songinfo
├─ config/                      JSON-Config, Cloth-Config-Screen, Mod-Menu-Anbindung
├─ util/ScriptProcess.java      startet die PowerShell-Bridges versteckt im Hintergrund
└─ mixin/
   ├─ CameraMixin               setzt Kameraposition/-rotation nach Camera.setup
   ├─ GameRendererMixin         FOV (Linse + Beat-Punch)
   ├─ GuiMixin                  ersetzt das HUD durch das Overlay
   └─ Keyboard-/MouseHandlerMixin  Eingaben → AFK-Timer
src/main/resources/assets/afkcinematic/scripts/
├─ audio-loopback.ps1           WASAPI-Loopback (C# per Add-Type), mono ~11 kHz → stdout
└─ media-session.ps1            SMTC → JSON-Zeilen
```

### Warum PowerShell-Bridges statt JNA?

WASAPI und SMTC sind COM- bzw. WinRT-APIs. Statt einer nativen DLL oder seitenlanger JNA-vtable-Aufrufe
startet die Mod zwei kleine, mitgelieferte PowerShell-Skripte, die den C#-Teil beim Start selbst
kompilieren (`Add-Type`). Das ist auf jedem Windows 10/11 vorhanden, braucht keine Installation und
lässt sich leicht prüfen. Die Skripte laufen versteckt, starten erst kurz bevor die AFK-Zeit erreicht ist,
und beenden sich eine Minute nach dem Cinematic oder sobald Minecraft schließt.

### Datenschutz

Das Systemaudio wird nur lokal analysiert (Lautstärke, Beat), nicht gespeichert und nicht übertragen.
An LRCLIB gehen nur Titel, Künstler, Album und Länge des Songs, um die Lyrics zu finden.
