# Prompt: Minecraft-Mod „Liquid Player“ (Spotify-Steuerung im Liquid-Glass-Look)

Baue eine **clientseitige Minecraft-Fabric-Mod** namens **„Liquid Player“** (Mod-ID `spotifyglass`, Paket `dev.spotifyglass`) für **Minecraft 1.21.11** und zusätzlich einen Port für **Minecraft 26.1**. Mit der Mod steuert man die **Spotify-Desktop-App unter Windows** aus dem Spiel heraus. Die Oberfläche ist im **Liquid-Glass-Stil**: halbtransparentes, weiches Glas mit Verläufen, Lichtreflexen und runden Formen. Dazu kommen Social-Funktionen: Songs der anderen sehen, mithören wie bei einem Spotify Jam, Profile, Freunde und Saison-Themes.

Alle Texte im Spiel sind **auf Deutsch**.

---

## 1. Feste Vorgaben (nicht verhandelbar)

- **Kein Spotify-Login, keine Spotify Web API, kein Client-Secret.** Alles läuft lokal über Windows.
- **Hörstatistiken zählen nur Songs, die gespielt werden, während die Mod läuft.** Es wird nichts von Spotify abgefragt.
- **Kein Audio-Streaming** zwischen Spielern, denn das wäre Weiterverbreitung von Spotify. Mithören heißt: Jeder hört auf seinem eigenen Spotify, und die Mod hält Song, Position und Pause synchron.
- Für Song-IDs werden nur **freie Datenbanken** genutzt (MusicBrainz), keine eingebetteten Zugangsdaten.
- Alles passiert **im Hintergrund**. Das Spotify-Fenster darf nicht im Vordergrund stehen bleiben, und Minecraft behält den Fokus.
- Die Mod funktioniert **ohne die Mod auf dem Server**. Ein optionaler Server-Teil ist erlaubt, aber nicht nötig.
- In der GUI läuft die Darstellung mit mindestens 30 FPS, es gibt keine 5-FPS-Drosselung.

---

## 2. Build und Versionen

- **1.21.11:** Fabric Loom 1.18.x mit `net.fabricmc.fabric-loom-remap` und `loom.officialMojangMappings()`, Fabric API `0.141.6+1.21.11`, Java 21.
- **26.1:** `net.fabricmc.fabric-loom` (unobfuscated), Fabric API `0.145.1+26.1`, Java 25. Der Code liegt im Unterordner `mc-26.1/`. Er wird aus `src/` per Skript `tools/port-26.1.ps1` erzeugt, das kopiert und Namen ersetzt:
  - `GuiGraphics` → `GuiGraphicsExtractor`
  - `render(...)` → `extractRenderState(...)`, `renderBackground` → `extractBackground`, `renderBlurredBackground` → `extractBlurredBackground`
  - `drawString` → `text` (außer bei Java2D in `CardBaker`, dort bleibt `drawString`)
  - `KeyBindingHelper` → `KeyMappingHelper`
  - `PayloadTypeRegistry.playC2S/S2C` → `serverboundPlay/clientboundPlay`
  - `submitNameTag` → `submitNameDisplay`; `CameraRenderState` liegt unter `state.level`
  - `LightTexture` → `LightCoordsUtil`
  - `PlayerFaceRenderer.draw` → `PlayerFaceExtractor.extractRenderState`
  - `getChat().addMessage` → `addClientSystemMessage`
  - Toast: `render(GuiGraphicsExtractor, Font, long)` → `extractRenderState`
- Nach jedem Build werden beide Jars in die Launcher-Profile kopiert:
  - Modrinth: `SODIUM 1.21.11-1.0.0 1.0.0 (1)` und `PVP`
  - NoRisk: `SODIUM 1.21.11-1.0.0` und `Fabric 26.1`

---

## 3. Spotify-Anbindung ohne Login (Windows)

### Media-Bridge (`media-bridge.ps1`)
Die Bridge ist ein mitgeliefertes PowerShell-Skript, **nur ASCII** (deutsche Anführungszeichen machen Parse-Fehler). `MediaBridge.java` startet es versteckt (`-WindowStyle Hidden`) und startet es bei einem Absturz höchstens alle 10 s neu.

**Lesen**
- Das Skript liest die **Windows Media Session (SMTC)**: Titel, Künstler, Cover, Play/Pause, Position, Dauer, Shuffle und Repeat.
- Das Cover wird per Reflection über `AsStreamForRead` als Datei gespeichert, die letzten 20 bleiben im Temp-Ordner.
- Ausgabe sind JSON-Zeilen auf stdout:
  - alle 50 ms `{"peak":x,"vol":y}`
  - jeden 8. Durchlauf die vollen Infos

**Befehle über stdin** (StreamReader auf `OpenStandardInput`, nicht `Console.In`)
- `toggle`, `play`, `pause`, `next`, `prev`
- `seek <ms>`, `shuffle <bool>`, `repeat None|Track|List`
- `volume <0..1>`
- `open <spotify:uri>`
- `searchplay <query> <titel> <künstler>` (URL-kodiert)

**Pegel und Lautstärke**
- **Audio-Pegel:** WASAPI `IAudioMeterInformation` der Spotify-Audio-Session (per C#-`Add-Type`). Er treibt die Wellen-Animation.
- **Lautstärke:** WASAPI pro App.
  - `ISimpleAudioVolume` liefert auf manchen PCs `E_NOINTERFACE`. Dann wird auf `IChannelAudioVolume` (alle Kanäle) ausgewichen.
  - Die Zugriffe laufen in einem eigenen **MTA-Thread**.
  - Die Lautstärke wird nur etwa alle 0,4 s gelesen.

### Songs im Hintergrund abspielen
**Bekannte Spotify-ID**
- `spotify:track:<id>` öffnen.
- Vorher pausieren (`TryPauseAsync`, 500 ms warten). Sonst wechselt Spotify bei laufender Musik nicht.
- Danach den Fokus zurück an Minecraft geben (user32: `SetForegroundWindow`, Alt-Tap, Spotify minimieren oder nach hinten).

**Ohne ID: per UI Automation in der Spotify-App suchen und Play drücken**
1. Ist Spotify minimiert, wiederherstellen (`ShowWindow 4`). Minimiert aktualisiert Spotify seinen UIA-Baum nicht.
2. Den „Start/Home“-Knopf drücken. Sonst ignoriert Spotify einen neuen Such-Link, wenn schon eine Suchseite offen ist.
3. `spotify:search:<query>` öffnen. Kommas, Doppelpunkte und Anführungszeichen vorher aus der Query entfernen.
4. Im DataGrid „Search results / Suchergebnisse“ die passende Zeile finden. Bedingungen:
   - Der Titel steht am Anfang.
   - Die Zeile ist vom Typ **„Song“**, also keine Folge, Playlist oder kein Album.
   - Der Künstler passt.
   - Es ist **keine Karaoke-, Cover- oder Instrumental-Version** (Wortliste).
   - Exakter Titel gibt +3 Punkte, Künstler +2.
5. Den Play-Knopf **der Zeile** auslösen. Seine Namen sind „… wiedergeben“ oder „… von … abspielen“.
6. **Fallback:** Liegt der Song nur in der „Top-Ergebnis“-Karte, deren Titel-Hyperlink öffnen (das öffnet die Albumseite) und dort in der Zeile des Songs auf Play drücken.
   - **Nicht** den großen grünen Play-Knopf nehmen, der startet das ganze Album ab Song 1.

Das Ganze läuft maximal 12 s, danach wird der Fokus wiederhergestellt. Unterstützt werden deutsche und englische UI-Beschriftungen.

### Weitere Dienste
- **Spotify-ID-Suche** (`TrackIdLookup`):
  - MusicBrainz-Recording-Suche, Kandidaten nach Anzahl der Releases sortiert, dann die URL-Relationen nach `open.spotify.com/track/<22 Zeichen>` durchsuchen.
  - Maximal 1 Anfrage pro Sekunde, bei HTTP 503 erneut versuchen.
  - Dauerhafter Cache in `config/liquid-player-trackids.json`.
  - Zuerst wird der volle Künstlername probiert, dann nur der erste von mehreren.
- **Songsuche in der GUI:** iTunes Search API (frei), 15 Treffer mit Cover.
- **Öffentliche Cover zum Teilen:** iTunes-Artwork (`mzstatic`). Lokale Cover-Dateien werden nie geteilt. `safeImage` lässt nur erlaubte Hosts durch.

---

## 4. Haupt-GUI (Taste **P**)

Drei Glas-Panels, die Gruppe ist mittig angeordnet und zentriert sich beim Auf- und Zuklappen weich neu:

### Player (Mitte, 220×250)
- **Kopfzeile:**
  - links Zahnrad (Einstellungen), dazu „Liquid Player“ und ein kleiner Statuspunkt (grün = Spotify erkannt, orange = wartet)
  - rechts der **eigene Kopf** (öffnet das eigene Profil) und „–“ zum Schließen
- **Großes Cover** (92 px) mit Schatten, darunter Titel (groß) und Künstler.
- **Fortschrittsbalken:** klicken zum Spulen, die Zeit wird flüssig weitergerechnet.
- **Steuerung:** Shuffle-Blase · Zurück · großer Play/Pause-Knopf (Akzentverlauf) · Weiter · Repeat-Blase (↻ bzw. „1“).
- **Lautstärke:**
  - Lautsprecher-Blase im selben Stil (0–2 Schallwellen; Klick schaltet stumm, dann lila mit ✕; ein weiterer Klick stellt den alten Wert wieder her)
  - Schieberegler wie der Fortschrittsbalken, Prozentanzeige
  - Befehle werden gedrosselt, höchstens einer alle 60 ms

### Songsuche (rechts, einklappbar über einen Glas-Griff)
- Suchfeld, Trefferliste mit Cover, Titel und Künstler.
- Klick spielt den Song **sofort im Hintergrund** ab (siehe oben).

### Reinhören (links, einklappbar über einen Glas-Griff)
- **Titelzeile:** rechts ein Button **„Jam verlassen“** (wenn ich mithöre) bzw. **„Jam beenden“** (wenn andere bei mir mithören, beendet für alle).
- **Tabs:**
  - **„Server · N“**: Spieler auf meinem Server inklusive aller Unterserver des Netzwerks
  - **„Global · N“**: alle Mod-Nutzer weltweit, die ihren Song teilen
- **Statusboxen:** „Du hörst bei X mit“ mit „Ende“, Einladungen mit „Mithören“/✕, „N Spieler hören bei dir mit“.
- **Liste:** pro Spieler eine Glasbox mit Kopf (oder Anfangsbuchstabe, wenn er nicht in der Tab-Liste steht), Name, kleinem Cover und „Titel · Künstler“.
  - Klick auf den Namen öffnet das Profil.
  - Klick auf die Box spielt den Song.
- **Unten:** Schalter „Meinen Song zeigen“.
- **Leerzustände** sagen, warum die Liste leer ist: verbindet noch / auf keinem Server → „Global“ / niemand hört etwas.

### Einstellungs-Schublade (fährt aus der Kopfzeile)
- **Farbthemes** als Kreis-Swatches: Lavendel, Ozean, Mint, Sunset, Rosé, Neon, Velvet, Crimson, Storm, Gold, Aqua.
- **Eigene Farben:** eigener Screen mit zwei Farbtönen und Intensität, zum Mischen.
- **Black Mode:** dunkles Glas, mit jedem Theme kombinierbar.
- **Deckkraft** 20–100 %.
- **Song-Anzeige im Spiel:** Aus / Karte / Vinyl / Welle, dazu „Anzeige verschieben“ (Ziehen; Mausrad ändert die Größe).
- Nach einem Farbwechsel zeigt ein kurzer Vorschau-Streifen oben alle drei Anzeige-Stile.
- **„Song über dem Namen“** öffnet einen eigenen Screen mit Skin-Vorschau, Schaltern und Größe.
- **„Saison“:** fünf runde Knöpfe: **Aus**, Halloween, Weihnachten, Winter, Ostern (siehe Abschnitt 8).

---

## 5. Song-Anzeige im Spiel (HUD)

Drei Stile, frei verschiebbar und skalierbar:
- **Karte:** Glaskarte mit Cover, Titel und Künstler.
- **Vinyl:** sich langsam drehende Schallplatte, das Cover dreht sich mit.
- **Welle:** animierte Welle, die auf den echten Spotify-Pegel reagiert. Sie ist als 60-Frame-Loop vorgebacken und wird pro Theme gecacht (LRU mit 3 Einträgen).

Farben kommen aus dem Theme, mit leichtem Schimmer.

---

## 6. Song über dem Spielernamen

- Über dem Namen schwebt eine Liquid-Glass-Karte mit Cover, Titel und Künstler.
- Die Karte wird per Java2D in **eine** Textur gebacken (`CardBaker`) und als unbeleuchtetes Quad gezeichnet.
- Umsetzung per Mixins auf `AvatarRenderer` und `AvatarRenderState`.
- Einstellbar:
  - eigenen Song zeigen ja/nein
  - Songs anderer zeigen ja/nein
  - Größe

---

## 7. Teilen, Relay, Mithören, Profile und Freunde

### Relay
- Ein öffentlicher **MQTT-Broker** (`broker.emqx.io:1883`). Ein schlanker MQTT-3.1.1-Client ist selbst geschrieben, ohne Bibliothek: CONNECT mit Last Will, SUBSCRIBE, PUBLISH QoS 0, Ping alle 30 s, Neuverbindung mit Backoff. Gesendet wird in einem eigenen Thread, nie im Render-Thread.
- **Vorher war es ntfy.sh. Das ist ungeeignet:** nur 250 Nachrichten pro Tag pro IP, und danach wird stillschweigend verworfen.
- **Topics** unter `liquidplayer/<hash>/`:
  - `s/<uuid>`: Song, *retained*
  - `p/<uuid>`: Online-Status, *retained*; der Last Will ist `"w":"off"`
  - `c/<uuid>`: Live-Steuernachrichten (Einladung, Annehmen, Verlassen)
  - `f/<an>/<von>`: Freundesnachrichten, *retained*, bis sie beantwortet sind
- **Song-Nachricht** enthält:
  - Titel, Künstler, öffentliches Cover, Spielername
  - **Server-Kennung** = gekürzter SHA-256-Hash der Serveradresse; alle Unterserver eines Netzwerks haben dieselbe, und die Adresse ist nicht ablesbar
  - Flags „über Namen“ und „in Reinhören“
  - Hörminuten und Top-Künstler (−1 bzw. leer, wenn das Profil privat ist)
  - spielt ja/nein, Position, Spotify-ID, Zeitstempel
- Jeder darf nur unter seiner eigenen UUID schreiben.
- Fremde Texte werden bereinigt: keine §-Codes, keine Steuerzeichen, höchstens 60 Zeichen.
- **Aktualität:**
  - Songs gehen bei Änderungen raus und sonst alle 2 min (alle 10 s, solange jemand mithört).
  - Nach **5 min ohne Nachricht** fliegt ein Spieler aus der Liste.
  - Ein **Live-„offline“** (Beenden oder Last Will) entfernt ihn sofort.
  - Beim Beenden löscht die Mod den eigenen gespeicherten Song (leere retained-Nachricht).
- **Uhrzeit:** Live-Nachrichten zählen als „jetzt“, damit falsch gehende PC-Uhren nichts kaputt machen. Gespeicherte Nachrichten zählen mit ihrem Zeitstempel.
- **Kontowechsel im Spiel** (z. B. In-Game Account Switcher): Das Relay verbindet sich mit der neuen UUID neu. Jam und Freundesliste gehören immer zum aktuellen Konto.

### Mithören
- Einladung im Profil, der Eingeladene bekommt einen Liquid-Toast.
- Funktioniert **über Unterserver und Server hinweg**.
- **Synchronisierung:**
  - Ist es nicht derselbe Song (Titel ohne Klammern und „ - …“, erster Künstler), wird er einmal gestartet: per Spotify-ID des Hosts oder per MusicBrainz, sonst über Suchen-und-Play.
  - Danach wird Play/Pause angeglichen (Drossel 2,5 s).
  - Bei mehr als 3 s Abweichung wird gespult (Drossel 6 s).
- Sendet der Host 60 s lang nichts, endet das Mithören.
- Beim Beenden des Spiels werden Jams sauber verlassen bzw. beendet.

### Profile
- Kopf, Name, Hörzeit, Top-Künstler und aktueller Song. Die Daten frischen sich einmal pro Minute auf.
- **Eigenes Profil:**
  - „Profil privat“
  - „In Reinhören zeigen“
  - rechts daneben das **Freunde-Menü**
- **Fremdes Profil:**
  - Mithören einladen, annehmen oder beenden
  - Song in Spotify öffnen
  - Freund hinzufügen, annehmen oder entfernen (Entfernen mit Bestätigung)

### Freunde
- Anfragen laufen ohne Login übers Relay. Sie bleiben gespeichert, bis sie beantwortet sind, und erreichen so auch Spieler, die gerade offline sind.
- Die Freundesliste liegt **pro Minecraft-Konto** in `config/liquid-player-friends-<uuid>.json`, zusammen mit den gesendeten Anfragen.
- Anzeige im Freunde-Menü: Online-Punkt, Status (Menü / Einzelspieler / LAN / „auf deinem Server“ / anderer Server / „Offline · vor X“), Hörzeit, aktueller Song oder Top-Künstler. Auch offline bleiben die zuletzt bekannten Werte sichtbar.
- **Benachrichtigungen** oben rechts als Liquid-Toasts in Themefarben, mit Kopf:
  - Freundschaftsanfrage
  - neuer Freund
  - Freund online (nur bei Live-Nachrichten, nicht beim Spielstart)
  - entfernt worden
  - Einladungen und Jam-Ereignisse
- Online-Heartbeat jede Minute. Beim Beenden geht sofort „offline“ raus.

### Statistiken
- Hörzeit und Zeit pro Künstler werden lokal gezählt und in `config/liquid-player-stats.json` gespeichert.

---

## 8. Saison-Themes

Die Saison-Themes sind in den Einstellungen an- und ausschaltbar. Jede Saison bringt eigene Farben mit, die überall gelten: GUI, HUD, Toasts und Profil. Dazu kommt **selbst gezeichnete, animierte Deko**. Es gibt keine Bilddateien, die Deko entsteht aus einfachen Formen und wird über die Uhrzeit animiert:
- **Halloween** (immer dunkel, Orange und Lila): flackernd leuchtende Kürbisse auf den unteren Panel-Ecken (halb außerhalb, damit sie keine Knöpfe verdecken), Spinnennetze in den Ecken, Mond mit Schein, fliegende Fledermäuse mit Flügelschlag.
- **Weihnachten** (Rot und Grün):
  - eine Tannengirlande in Bögen über dem Player, mit roten Schleifen, roten und goldenen Kugeln und funkelnden Lichtern
  - blinkende bunte Lichterketten über den Seitenpanels
  - leichter Schneefall
- **Winter** (Eisblau): Schneekappen auf den Panels, dichter Schneefall.
- **Ostern** (Pastell): bunte Eier an den Ecken, Gras am unteren Rand.

**Wichtig für die Leistung:** Formen mit wechselnder Helligkeit werden als *eine* gecachte Textur gezeichnet, die Helligkeit kommt über die Tönung. Sonst läuft der Form-Cache (300 Einträge) über.

---

## 9. Grafik-Technik

- `ShapeCache`: Formen (runde Rechtecke, Verläufe, weiche Blobs, Zahnrad) werden per SDF in Texturen gerastert und gecacht, abhängig von der GUI-Skalierung. Die Deckkraft kommt per Tönung, damit beim Verstellen nichts neu gerastert werden muss.
- `Glass`: Hilfsfunktionen für Schatten, Glasflächen mit Glanzstreifen, Pills, Umrisse, Kreise und Icons (Play, Pause, Skip, Plus, Häkchen, Pfeil, Lupe, Note, Lautsprecher).
- Themes sind Records mit Akzent-, Zweit-, Panel-, Blob-, Text- und Pill-Farben. Dazu kommt eine Black-Variante und eigene Farben aus HSV.
- Der Hintergrund ist der Vanilla-Menü-Blur mit sanfter Tönung.

---

## 10. Häufige Fallen (aus der Entwicklung)

- **ntfy.sh:** 250 Nachrichten pro Tag pro IP, danach wird stillschweigend verworfen. Deshalb MQTT.
- **Konto im Spiel gewechselt:** Das alte Relay-Postfach blieb abonniert, dadurch kamen Anfragen und Toasts nicht an. Lösung: bei UUID-Wechsel neu verbinden, Freundesliste pro Konto.
- **Unterserver eines Netzwerks** stehen nicht in der Tab-Liste. Den „Server“ deshalb über den Hash der Serveradresse bestimmen.
- **Spotify:**
  - Ein Track-Link spielt nur, wenn vorher pausiert wurde.
  - Ein Such-Link wird ignoriert, solange schon eine Suchseite offen ist (vorher Home drücken).
  - Minimiert gibt es keinen UIA-Baum.
  - Ein Komma in der Query zerstört den Such-Link.
- **PowerShell:**
  - `$home` ist schreibgeschützt.
  - Das Bridge-Skript nur in ASCII schreiben.
  - `Console.In.ReadLineAsync` ist in Wahrheit synchron.
- **WASAPI:** `ISimpleAudioVolume` kann fehlen, dann `IChannelAudioVolume` im MTA-Thread nehmen.
- Netzwerkzugriffe nie im Render-Thread.
