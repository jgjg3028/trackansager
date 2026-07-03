# TrackAnsager

Kleine Android-Begleit-App, die beim Musikhören (z. B. mit Amazon Music) automatisch
**Titel und Interpret per Sprachausgabe ansagt** — wahlweise am Anfang des Songs
und/oder kurz vor dem Ende. Die Musik läuft dabei ganz normal in der Musik-App
weiter; während der Ansage wird sie automatisch leiser geregelt (Audio-Ducking).

Die App streamt selbst nichts und greift **nicht** auf den Amazon-Account zu.
Sie liest lediglich die Metadaten der laufenden Wiedergabe über Androids
MediaSession-Schnittstelle aus.

## Funktionen

- Ansage am Songanfang (an/aus)
- Ansage kurz vor Songende (an/aus), Vorlauf einstellbar (3–20 Sekunden)
- Ansagesprache: Automatisch (pro Song per Heuristik), immer Deutsch oder immer Englisch
- Optional: nur auf Amazon Music reagieren (sonst jede Musik-App)
- Test-Button für die Ansage

## Bauen (einmalig, am Mac)

1. **Android Studio** installieren (https://developer.android.com/studio),
   auch als Intel-Version für den MacBook Pro verfügbar.
2. Diesen Projektordner in Android Studio öffnen (`File → Open`).
   Beim ersten Öffnen lädt Android Studio automatisch Gradle 8.7 und alle
   Abhängigkeiten herunter (Internetverbindung nötig, dauert ein paar Minuten).
3. Auf dem Galaxy A54 die **Entwickleroptionen** aktivieren
   (Einstellungen → Telefoninfo → Softwareinformationen → 7× auf „Buildnummer" tippen),
   dann **USB-Debugging** einschalten.
4. Handy per USB anschließen, in Android Studio als Zielgerät auswählen
   und auf **Run ▶** klicken. Die App wird installiert und gestartet.

Alternativ eine APK bauen (`Build → Build App Bundle(s) / APK(s) → Build APK(s)`)
und die Datei aufs Handy kopieren und dort installieren (Sideload).

## Einrichten auf dem Handy

1. App öffnen → **„Benachrichtigungszugriff einstellen"** antippen.
2. In der Liste **TrackAnsager** aktivieren und bestätigen.
   (Diese Berechtigung braucht Android, damit die App die laufende
   Musikwiedergabe erkennen darf.)
3. Zurück in der App: Einstellungen nach Wunsch setzen, Test-Ansage probieren.
4. Amazon Music öffnen, Playlist wählen, Shuffle an — fertig. Die Ansagen
   kommen ab jetzt automatisch, auch bei ausgeschaltetem Bildschirm.

## Tipps

- **Akku-Optimierung:** Falls die Ansagen nach längerer Zeit ausbleiben,
  in den Android-Einstellungen unter Apps → TrackAnsager → Akku die
  Option „Nicht eingeschränkt" wählen (Samsung schläfert Hintergrund-Apps
  sonst gern ein).
- **TTS-Stimme:** Unter Einstellungen → Allgemeine Verwaltung →
  Text-zu-Sprache kann die Google-Sprachausgabe als Engine gewählt und
  deutsche/englische Stimmdaten heruntergeladen werden (für Offline-Nutzung
  beim Joggen empfehlenswert).
- Die End-Ansage funktioniert nur, wenn die Musik-App die Songdauer in den
  Metadaten mitliefert (Amazon Music tut das normalerweise).
