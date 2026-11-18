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

### Wunschliste (18.11.2026)
1. **Weitere Geschäfte:** Action, KODi, KiK, Woolworth über die vorhandenen
   Marktguru-/kaufDA-Adapter – ✅ umgesetzt ([PR #3](https://github.com/gitgoodbadhub/Korbunio/pull/3)).
   Woolworth liefert auf dem Gerät Angebote; Action, KODi, KiK noch zu prüfen.
   Platzhalter-Marke „thisisnobrand“ wird entfernt ([PR #4](https://github.com/gitgoodbadhub/Korbunio/pull/4)).
   Hinweis: erfasst werden nur Prospekt-/Aktionsangebote, nicht das ganze Sortiment.
2. **Einkaufsroute:** Einkaufsliste mit den gewählten Geschäften als Route in einer
   Karten-App öffnen (Google Maps mit mehreren Stopps; Einzelziele auch für OsmAnd,
   Organic Maps u. a.). Baut auf dem vorhandenen Einkaufsplan und Phase 3 (Filialen) auf.
3. **Kaufassistent per Sprache:** Wunschprodukte, Preisobergrenze je Produkt oder für den
   ganzen Einkauf, automatische Einkaufsliste und Route – zuerst als Sprach-Button in der
   App, danach Anbindung an Gemini auf dem Smartphone (abhängig davon, was Android/Gemini
   für Dritt-Apps freigibt).
4. **Gezielte Shop-Suche (reguläres Sortiment), Vorhaltezeit 14 Tage:**
   - Sucht nur auf Anfrage (Wunsch, Favorit, Einkaufsliste, Muster-Warenkorb) in den
     Online-Shops/Sortimentsseiten der Händler – kein Spiegeln ganzer Kataloge.
   - Ohne KI: einfache Parser (schema.org-Produktdaten, Shop-Suche als JSON), `robots.txt`
     beachten, höchstens etwa eine Anfrage alle 2–3 s, erkennbarer User-Agent, keine
     Umgehung von Captchas oder Logins; AGB je Shop vorher prüfen.
   - Ergebnisse 14 Tage zwischenspeichern, danach automatisch löschen; jeder Treffer mit
     „regulärer Shop-Preis · Stand TT.MM.“ gekennzeichnet. Optional Preisverlauf (nur Änderungen).
   - Einstellbar: Vorhaltezeit 1/7/14/30 Tage, Preisverlauf an/aus, Anzeige „belegter
     Speicher“ mit Button zum Leeren. Erwarteter Speicher: wenige MB.
   - Aufwand: Grundgerüst ca. 2 PRs, je einfachem Shop 1 PR, schwierige Shops (Bot-Schutz,
     marktabhängige Preise wie REWE) 2–3 PRs oder nicht sinnvoll; zuerst 2–3 einfache Shops
     (z. B. KiK, Woolworth), dann entscheiden.
5. **Muster-Warenkörbe #1–#99:** fertige, wiederverwendbare Vorlagen, die mit aktuellen
   Angeboten und regulären Shop-Preisen (Punkt 4) befüllt werden.
   - Beispiele: #1 Wocheneinkauf aus höchstens drei Läden mit typischem Regelsortiment
     (auch Produkte, die nie im Prospekt stehen); Outdoor-/Wander-Warenkorb mit
     saisonalen Artikeln, die z. B. nur im Winter erhältlich sind und rund 50 Wochen im Jahr
     in keinem Prospekt auftauchen.
   - Eine Vorlage enthält **Produktarten** statt fester Artikel (z. B. „Vollmilch 1 l“,
     „Handwärmer“), je Position Menge, optionale Preisobergrenze, Warengruppe und
     Saison-Monate; dazu Gesamtbudget und maximale Anzahl Läden.
   - Befüllen: je Position bestes aktuelles Angebot, sonst regulärer Shop-Preis; der
     vorhandene Einkaufsplan wählt die günstigste Kombination aus höchstens N Läden.
     Außerhalb der Saison wird eine Position als „derzeit nicht erhältlich“ markiert.
   - Vorlagen als versionierte JSON-Dateien in der App (#1–#99), eigene Vorlagen anlegbar,
     Export/Import über das vorhandene Backup. Später kann der Sprach-Kaufassistent
     Vorlagen erstellen, anpassen und daraus Einkaufsliste und Route erzeugen.

Kosten: GitHub Actions und Releases kostenlos (öffentliches Repository);
Händlerdaten, Karten-Navigation und Widget ohne kostenpflichtige API.
Nur Phase 5 verursacht Kosten über den eigenen Claude-API-Schlüssel
(geschätzt wenige Cent bis einige Euro pro Monat, je nach Modell und Nutzung).
