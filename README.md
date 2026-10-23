# Liquid Player – Spotify in Minecraft (Fabric 1.21.11 & 26.1)

Steuert die Spotify-Desktop-App direkt aus Minecraft – mit einer Liquid-Glass-Oberfläche.
**Kein Login nötig:** Die Mod liest Spotify direkt aus Windows aus (dieselben Daten wie das
Medien-Fenster bei den Lautstärketasten) und startet Songs im Hintergrund, Minecraft behält den Fokus.

## Installation

1. Fabric Loader und **Fabric API** für deine Version installieren.
2. Die passende Jar aus den [Releases](https://github.com/Tomitomateee/Liquid-Player/releases) in den `mods`-Ordner kopieren:
   - 1.21.11: `liquid-player-1.0.0.jar`
   - 26.1: `liquid-player-1.0.0+26.1.jar`
3. Spotify-Desktop-App unter **Windows** starten, Minecraft starten, **P** drücken.

## Funktionen

| Element | Funktion |
|---|---|
| Player (Mitte) | Cover, Titel, Interpret, Fortschritt (klicken zum Springen), ⏮ ⏯ ⏭, Zufall, Wiederholen |
| Lautstärke | Lautsprecher (Klick = stumm) und Regler – regelt nur Spotify, nicht Minecraft |
| Songsuche (rechts) | iTunes-Suche; Klick sucht den Song in Spotify und drückt selbst auf Play |
| Reinhören (links) | Tabs **Server** (auch alle Unterserver eines Netzwerks) und **Global** (alle Mod-Nutzer); Klick spielt den Song |
| Mithören | Wie ein Spotify Jam: jeder hört auf seinem eigenen Spotify, Song, Position und Pause bleiben synchron; „Jam verlassen / beenden“ |
| Profil (eigener Kopf oben) | Hörzeit und Lieblings-Künstler (nur mit der Mod gezählt), privat schaltbar, Freunde-Menü |
| Freunde | Anfragen ohne Login, sehen wer online ist und was gerade läuft, Benachrichtigung oben rechts |
| Zahnrad | Farbthemen, eigene Farben, Black Mode, Deckkraft, Song-Anzeige im Spiel (Karte / Vinyl / Welle), Song über dem Namen, **Saison-Themes** (Halloween, Weihnachten, Winter, Ostern) |

Zusätzliche (unbelegte) Tasten in den Steuerungs-Einstellungen: Play/Pause, Weiter, Zurück.

## Datenschutz

Geteilt wird nur, was du einschaltest („Meinen Song zeigen“, „Song über dem Namen“):
Titel, Interpret, öffentlicher Cover-Link, Minecraft-Name und -UUID, eine nicht rückrechenbare
Server-Kennung sowie Hörzeit/Lieblings-Künstler (nicht bei privatem Profil). Übertragen wird das
über den öffentlichen MQTT-Broker `broker.emqx.io`. Es gibt keine Spotify-Anmeldung und keine
Audio-Übertragung.

## Bauen

```
gradlew build
powershell -ExecutionPolicy Bypass -File tools\port-26.1.ps1
cd mc-26.1 && gradlew build
```

Eine vollständige Beschreibung der Mod (Aufbau, Technik, Fallstricke) steht in
[`LIQUID-PLAYER-PROMPT.md`](LIQUID-PLAYER-PROMPT.md).
