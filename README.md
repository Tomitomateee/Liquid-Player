# Liquid Player – Spotify in Minecraft (Fabric 1.21.11 & 26.1)

Zeigt und steuert das laufende Spotify-Lied in Minecraft – mit einer Liquid-Glass-Oberfläche.
**Kein Login nötig:** Die Mod liest Spotify direkt aus Windows aus (dieselben Daten wie das
Medien-Fenster bei den Lautstärketasten).

## Installation

1. Fabric Loader und **Fabric API** für deine Version installieren.
2. Die passende Jar in den `mods`-Ordner kopieren:
   - 1.21.11: `build/libs/liquid-player-1.0.0.jar`
   - 26.1: `mc-26.1/build/libs/liquid-player-1.0.0+26.1.jar`
3. Spotify-Desktop-App unter **Windows** starten, Minecraft starten, **P** drücken.

## Funktionen

| Element | Funktion |
|---|---|
| Großes Cover, Titel, Interpret | Laufender Song, automatisch erkannt |
| Fortschrittsbalken | Klicken zum Springen |
| ⏮ / ⏯ / ⏭, Leertaste | Zurück, Abspielen/Pause, Weiter |
| ⇄ / ↻ | Zufall / Wiederholen |
| Songsuche (rechts) | Sucht Songs (iTunes-Suche), Klick öffnet den Song in Spotify |
| Griff links am Player | „Reinhören“ ein-/ausklappen: wer gerade was hört (Kopf, Name, Song), Klick öffnet den Song in Spotify |
| Griff rechts am Player | Songsuche ein-/ausklappen |
| Zahnrad | Farbthema, Deckkraft, Song-Anzeige im Spiel (verschiebbar), Song über dem Namen |

Zusätzliche (unbelegte) Tasten in den Steuerungs-Einstellungen: Play/Pause, Weiter, Zurück.

**Song über dem Namen:** Andere Spieler mit der Mod sehen deinen Song als Glas-Karte mit Cover.
Auf Servern ohne die Mod wird das über den öffentlichen Dienst ntfy.sh geteilt (Titel, Interpret,
Cover-Link, Minecraft-UUID). Abschaltbar im Zahnrad-Menü.

## Bauen

```
gradlew build
powershell -ExecutionPolicy Bypass -File tools\port-26.1.ps1
cd mc-26.1 && gradlew build
```
