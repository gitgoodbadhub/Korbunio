# Korbunio Plus

Persönliche Weiterentwicklung von [Korbuino/Korbunio](https://github.com/lesecuritae/Korbunio)
(BSD-3-Clause, © lesecuritae). Grundlage ist die native, serverlose
Kotlin/Jetpack-Compose-App unter [`android/`](android/). Der Rest des Repositorys
(Python-Server, Flutter-App) bleibt unverändert aus dem Original.

**Stand:** 18.11.2026, Version 0.1.83 (`de.korbunio.plus`). Auf dem Smartphone
installiert und getestet: Start, PLZ/Ort, Einstellungen funktionieren.
Weitere Tests folgen.

## Was gegenüber dem Original geändert ist

### Phase 0 – eigenständige App ([PR #1](https://github.com/gitgoodbadhub/Korbunio/pull/1))
- Eigene App-ID `de.korbunio.plus`, Launcher-Name „Korbunio Plus“. Läuft **neben**
  der Original-App; Daten werden nicht geteilt (Übernahme über Backup-Export/-Import).
- FileProvider-Authority aus der App-ID abgeleitet (Voraussetzung für die
  parallele Installation).
- Update-Suche in der App liest Releases aus **diesem** Repository.
- Release-Workflow veröffentlicht in dieses Repository; Signatur-Variablen
  vereinheitlicht (`KORBUINO_*`).
- Texte innerhalb der App heißen weiterhin „Korbuino“ (der Release-Smoke-Test prüft darauf).

### Phase 1 – Händlerlinks und Bestpreis je Favorit (PR #1)
- Angebotskarte: Button **„Zum Angebot ↗“** öffnet die Händlerseite
  (nur bei gültiger HTTPS-Adresse) – `OfferLink.kt`.
- Favoritenkarte: **„Bestpreis heute“** mit Bild, Händler, Grundpreis,
  Gültigkeit, Link und Hinweis „Über deiner Preisgrenze“ – `FavoriteOffers.kt`,
  `FavoritesScreen.kt`.
  - Abgleich wie bei den ntfy-Meldungen (`WatchMatcher`): Suchbegriff, Händler, PLZ.
  - Nur heute gültige Angebote; Angebote ohne Gültigkeitsdatum zählen nicht.
  - Mehrkauf wird je Packung verglichen; die Preisgrenze gilt für den Zahlbetrag.
- Tests: `FavoriteOffersTest` (Unit), neuer Fall in `FavoritesScreenTest` (Emulator).

## Bauen und veröffentlichen

Alles läuft über GitHub Actions ([`android-app.yml`](.github/workflows/android-app.yml)),
kein eigener Rechner nötig.

| Auslöser | Ergebnis |
|---|---|
| Pull Request | Debug-Build, Unit-Tests, Lint, F-Droid-Build, Emulator-Tests |
| Push auf `main` | zusätzlich signierte APK als Artefakt `korbunio-apk` (30 Tage) |
| Tag `vX.Y.Z` | signierte APK als GitHub-Release (`korbuino-X.Y.Z.apk` + `.sha256`), danach Test der veröffentlichten APK im Emulator |

Das Tag muss zu `versionName` in `android/app/build.gradle.kts` passen;
`versionCode` muss bei jeder Version steigen.

### Signatur
- Repository-Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`,
  `ANDROID_KEY_ALIAS` (`korbunio-plus`), `ANDROID_KEY_PASSWORD` – eingerichtet.
- Zertifikat: `CN=Korbunio Plus, O=Korbunio Plus, C=DE`, RSA 4096,
  SHA-256 `88:96:51:A1:E9:B4:FA:40:0F:73:7C:B7:2D:E5:4A:19:E0:D9:F7:A9:57:A9:6B:87:EE:63:2D:99:C0:53:88:F1`
- **Der Keystore (`korbunio-plus-release.jks`) und die Passwörter liegen nicht im
  Repository und dürfen nie hinein.** Sicherungskopie privat aufbewahren – ohne
  diesen Schlüssel sind keine Updates der installierten App mehr möglich.

### APK auf das Smartphone holen
- **Aus einem Build:** Actions → gewünschter Lauf → im Browser ganz unten
  *Artifacts* → `korbunio-apk` (ZIP) → `app-standard-release.apk` installieren.
  Die GitHub-App zeigt keine Artefakte an.
- **Aus einem Release** (sobald vorhanden): Releases → `korbuino-X.Y.Z.apk`.
  Danach findet die App neue Versionen selbst über „Nach Updates suchen“.

## Arbeitsweise
Jede Änderung als eigener Branch und Pull Request; übernommen wird erst bei
grüner CI. Danach neues Tag für ein Release. Fehlerkorrekturen an den
Händler-Anbindungen aus dem Original gelegentlich übernehmen – eigene
Änderungen klein halten, damit das konfliktarm bleibt.

## Bekannte offene Punkte
- Workflow „Publish Docker image“ schlägt im Fork fehl (veröffentlicht fest nach
  `ghcr.io/lesecuritae/korbunio`); für die App ohne Bedeutung, abschalten oder umstellen.
- User-Agent der Händlerabrufe nennt noch die URL des Original-Repositorys.
- Noch kein GitHub-Release; erste Version wäre `v0.1.84`.

## Plan

| Phase | Inhalt | Status |
|---|---|---|
| 0 | Eigenständige App, Signatur, Release-Workflow | ✅ erledigt |
| 1 | Händlerlinks, Bestpreis mit Bild je Favorit | ✅ erledigt, Test auf dem Gerät läuft |
| 2 | Startseite mit Favoriten-Karussell und Top-Angeboten; Produkt-Vergleich über alle Händler | offen |
| 3 | Filialfinder (Tabelle `stores` befüllen, OpenStreetMap ergänzen), Navigation mit Google Maps | offen |
| 4 | Startbildschirm-Widget (Jetpack Glance) mit Favoriten und Bestpreisen | offen |
| 5 | KI-Sprachassistent: eigener Claude-API-Schlüssel im Keystore, wertet Favoriten und geladene Angebote aus; Sprache über Android-Spracherkennung | offen |

Kosten: GitHub Actions und Releases kostenlos (öffentliches Repository);
Händlerdaten, Karten-Navigation und Widget ohne kostenpflichtige API.
Nur Phase 5 verursacht Kosten über den eigenen Claude-API-Schlüssel
(geschätzt wenige Cent bis einige Euro pro Monat, je nach Modell und Nutzung).
