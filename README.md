# schapfl-sync-service

## Überblick

Dieser Microservice verbindet zwei Richtungen der POS-Synchronisation:

1. **Artikel-Sync per MQTT → SCHAPFL-Dateien**  
   Artikel werden aus einem MQTT Topic gelesen, in SCHAPFL-ART/MEH-Dateien umgewandelt und anschließend lokal oder per SFTP abgelegt.
2. **Verkaufsdaten lokal → MQTT**  
   Verkaufsdateien aus einem lokalen Eingangsverzeichnis werden zyklisch gelesen und als MQTT-Nachrichten veröffentlicht.

Der Service ist als Quarkus-Anwendung umgesetzt und für den Betrieb in Docker bzw. auf einem Raspberry Pi geeignet.

---

## Fachlicher Ablauf

### 1. Artikel-Synchronisation

**Eingang:** MQTT Topic `mqtt.topic`  
**Format:** JSON mit einem Artikel oder einer Liste von Artikeln (`ArticlePosDto`)  
**Ausgang:** SCHAPFL-Dateien (`ART_*.SCHAPFL`, optional `MEH_*.SCHAPFL`)

Ablauf:
- Der Service subscribed auf `mqtt.topic`.
- JSON-Nachrichten werden in `ArticlePosDto` deserialisiert.
- `SchapflBuilder` erzeugt daraus SCHAPFL-Dateien.
- Über `FileSink` werden die Dateien je nach `sink.mode` abgelegt:
    - `local` → in `local.outputDir`
    - `sftp` → in `sftp.remoteDir`

### 2. Verkaufsdaten-Publishing

**Eingang:** Dateien in `local.inputDir`  
**Ausgang:** MQTT Topic `mqtt.sales.topic`

Ablauf:
- Das Feature ist **nur aktiv**, wenn **beide** Properties gesetzt sind:
    - `local.inputDir`
    - `mqtt.sales.topic`
- Ein Polling-Worker prüft das Verzeichnis zyklisch.
- Es werden nur **Top-Level-Dateien** verarbeitet, keine Unterverzeichnisse.
- Es werden nur Dateien mit Prefix `GES` und Suffix `.schapfl` berücksichtigt, z. B. `GES_1_2126695_20260314105137.schapfl`.
- Dateien werden erst verarbeitet, wenn sie älter als `sales.file.stability.millis` sind.
- Vor dem Publish wird jede Datei atomar nach `local.inputDir/.processing` verschoben.
- Jede Datei wird dadurch maximal einmal automatisch an MQTT gesendet.
- Nach erfolgreichem Publish wird die Datei nach `sales.processedDir` verschoben.
- Wenn Publish oder Archivierung fehlschlagen, bleibt die Datei in `.processing` und wird **nicht** automatisch erneut gesendet.

> Annahme für dieses Feature: Die Verkaufsdatei liegt bereits im gewünschten Payload-Format vor und wird **unverändert als MQTT-Payload** gesendet.

---

## Architektur

### Wichtige Komponenten

- `Consumer`  
  Verarbeitet eingehende Artikel-Nachrichten aus MQTT.

- `MqttClientService`  
  Zentrale MQTT-Verbindung für Subscribe und Publish.

- `SchapflBuilder`  
  Baut aus `ArticlePosDto` die SCHAPFL-Inhalte.

- `FileSinkProducer`  
  Entscheidet anhand von `sink.mode`, ob lokal oder per SFTP geschrieben wird.

- `LocalFileSink`  
  Schreibt Dateien atomar in ein lokales Verzeichnis.

- `SftpFileSink` / `SftpServiceJsch`  
  Übertragen Dateien atomar via SFTP.

- `LocalSalesPublisher`  
  Liest lokale Verkaufsdateien und publiziert sie in MQTT.

- `SalesFileProcessor`  
  Kapselt Dateiscan, Stabilitätsprüfung, Claiming nach `.processing` und Archivierung nach erfolgreicher Verarbeitung.

---

## Konfiguration

### Kernkonfiguration

| Property | Bedeutung |
|---|---|
| `quarkus.http.port` | HTTP-Port der Anwendung |
| `mqtt.broker` | MQTT-Broker-URL |
| `mqtt.client` | MQTT-Client-ID |
| `mqtt.username` | MQTT-Benutzer |
| `mqtt.password` | MQTT-Passwort |
| `mqtt.qos` | MQTT-QoS für Subscribe und Publish |

### Artikel-Sync

| Property | Bedeutung |
|---|---|
| `mqtt.topic` | Topic für eingehende Artikel |
| `sink.mode` | `local` oder `sftp` |
| `local.outputDir` | Zielverzeichnis für lokale SCHAPFL-Dateien |
| `schapfl.store-number` | Filialnummer |
| `schapfl.lane-number` | Kassen-/Lane-Nummer |
| `articlegroup.ids.weight-ean` | Artikelgruppen, deren Scancodes gekürzt werden |

### Verkaufsdaten → MQTT

| Property | Bedeutung |
|---|---|
| `local.inputDir` | Eingangsverzeichnis für Verkaufsdateien |
| `mqtt.sales.topic` | Ziel-Topic für Verkaufsdaten |
| `sales.poll.interval.millis` | Polling-Intervall für Dateiscans |
| `sales.file.stability.millis` | Mindestalter einer Datei vor Verarbeitung |
| `sales.processedDir` | Zielverzeichnis für erfolgreich verarbeitete Dateien; leer = `<local.inputDir>/processed` |

### SFTP

| Property | Bedeutung |
|---|---|
| `sftp.host` | SFTP-Host |
| `sftp.port` | SFTP-Port |
| `sftp.username` | SFTP-Benutzer |
| `sftp.password` | Passwort für Passwort-Auth |
| `sftp.privateKey` | Pfad zum Private Key |
| `sftp.privateKeyPassphrase` | Passphrase für den Private Key |
| `sftp.knownHosts` | Pfad zur `known_hosts` Datei |
| `sftp.strictHostKeyChecking` | `yes`/`no` |
| `sftp.remoteDir` | Zielverzeichnis auf dem SFTP-Server |
| `sftp.retry.maxAttempts` | Maximale Retry-Anzahl |
| `sftp.retry.delayMillis` | Wartezeit zwischen Retries |

### Wichtiger Hinweis zu SFTP

Die `sftp.*`-Properties sind **nur relevant**, wenn `sink.mode=sftp` gesetzt ist.  
Solange `sink.mode=local` aktiv ist, schreibt der Service ausschließlich nach `local.outputDir`.

---

## Beispiel-Konfiguration

### Nur Artikel-Sync lokal

```properties
sink.mode=local
local.outputDir=/data/pos/import
mqtt.topic=schapfl/articlesync
local.inputDir=
mqtt.sales.topic=
```

### Artikel-Sync lokal + Verkaufsdaten zurück an MQTT

```properties
sink.mode=local
local.outputDir=/data/pos/import
local.inputDir=/data/pos/export
mqtt.topic=schapfl/articlesync
mqtt.sales.topic=schapfl/sales
sales.poll.interval.millis=5000
sales.file.stability.millis=2000
sales.processedDir=/data/pos/export/done
```

### Artikel-Sync per SFTP

```properties
sink.mode=sftp
mqtt.topic=schapfl/articlesync
sftp.host=192.168.1.50
sftp.port=22
sftp.username=posftp
sftp.password=secret
sftp.strictHostKeyChecking=no
sftp.remoteDir=/pos-import/inbox
```

---

## Build und Start

### Anwendung paketieren

```shell
./mvnw package -DskipTests
```

### Tests ausführen

```shell
./mvnw test
```

### Docker-Image bauen

```shell
docker buildx build \
--platform linux/arm/v7 \
-t riseacr.azurecr.io/schapfl-sync-service:1.0.0-armv7 \
-f src/main/docker/Dockerfile.jvm \
--push \
.
```

Wenn das Verkaufsdaten-Feature genutzt wird, sollte `local.inputDir` z. B. auf `/data/pos/export` zeigen. Erfolgreich archivierte Dateien landen dann in `/data/pos/export/done`.

### Host-Verzeichnisse und Rechte für Verkaufsdaten

Der Host-Pfad muss für den Container **explizit beschreibbar** sein.  
Der Container läuft in `src/main/docker/Dockerfile.jvm` als **UID 185** (`USER 185`).

Wenn `/srv/sftp/pos-export` nach `<target-path>` gemountet ist, muss also auf dem Host mindestens dieses Verzeichnis für UID/GID `185` schreibbar sein.

Empfohlenes Setup auf dem Host:

```shell
sudo mkdir -p /srv/sftp/pos-export
sudo mkdir -p /srv/sftp/pos-export/done
sudo chown -R 185:185 /srv/sftp/pos-export
sudo chmod -R 775 /srv/sftp/pos-export
```

Hinweise:
- Den Ordner `.processing` legt der Service selbst an, **wenn** `/srv/sftp/pos-export` beschreibbar ist.
- Wenn `AccessDeniedException: /data/pos/export/.processing` erscheint, fehlt in der Regel genau diese Schreibberechtigung.
- Ein separates Mount für `done` ist nicht nötig; robuster ist ein gemeinsamer Mount auf `/srv/sftp/pos-export` mit dem Unterordner `done`.

---

## Dateiverarbeitung im Detail

### Lokales Schreiben

`LocalFileSink` schreibt atomar:
- erst in `*.tmp`
- danach per Move auf den finalen Dateinamen
- optional mit POSIX-Dateirechten, falls vom Dateisystem unterstützt

### SFTP-Schreiben

`SftpServiceJsch` schreibt ebenfalls atomar:
- Upload in `*.tmp`
- anschließendes Rename auf den finalen Namen

### Verkaufsdateien

`LocalSalesPublisher` arbeitet robust gegen halbfertige Dateien:
- nur Dateien im Root von `local.inputDir`
- keine versteckten Dateien
- keine Unterordner
- nur Dateien mit Prefix `GES` und Suffix `.schapfl`
- nur Dateien, die lange genug unverändert waren
- vor dem Publish wird jede Datei atomar in `local.inputDir/.processing` verschoben
- dadurch wird ausgeschlossen, dass dieselbe Verkaufsdatei mehrfach gestreamt wird
- erfolgreiche Dateien werden nach `sales.processedDir` archiviert
- wenn Publish oder Archivierung fehlschlagen, bleibt die Datei in `.processing` zur manuellen Recovery liegen und wird **nicht** automatisch erneut gesendet

Zusätzliche Sicherheitsregel:
- `local.inputDir` darf nicht identisch zu `local.outputDir` sein, um Schleifen zu vermeiden

---

## Betriebs- und Security-Hinweise

- Zugangsdaten sollten im Produktivbetrieb nicht hart in Property-Dateien liegen, sondern per Umgebungsvariablen oder Secret-Management gesetzt werden.
- Für SFTP sollte `strictHostKeyChecking=yes` mit gepflegter `known_hosts`-Datei bevorzugt werden.
- Das Sales-Publishing ist standardmäßig deaktiviert, solange `local.inputDir` oder `mqtt.sales.topic` leer bleiben.

---

## Troubleshooting

### Es werden keine SCHAPFL-Dateien geschrieben

Prüfen:
- ist `mqtt.topic` korrekt?
- kommt gültiges JSON an?
- ist `sink.mode` richtig gesetzt?
- bei `local`: existiert bzw. ist `local.outputDir` beschreibbar?
- bei `sftp`: sind `sftp.*` vollständig und korrekt gesetzt?

### Es werden keine Verkaufsdateien publiziert

Prüfen:
- sind `local.inputDir` **und** `mqtt.sales.topic` gesetzt?
- landen die Dateien direkt im Top-Level von `local.inputDir`?
- haben die Dateien das Muster `GES*.schapfl`?
- sind die Dateien alt genug (`sales.file.stability.millis`)?
- ist `local.inputDir` ungleich `local.outputDir`?
- werden Dateien nach erfolgreichem Publish nach `sales.processedDir` verschoben?
- ist `sales.processedDir` für den Container wirklich beschreibbar? Empfohlen ist ein Unterordner derselben Export-Freigabe, z. B. `/data/pos/export/done`.
- ist bereits der Root von `local.inputDir` beschreibbar? Der Service muss dort `.processing` anlegen können.
- liegt der Host-Mount `/srv/sftp/pos-export` wirklich auf `/data/pos/export` und gehört er UID/GID `185` oder ist entsprechend beschreibbar?
- liegen problematische Dateien bereits in `local.inputDir/.processing`? Dann wurden sie bereits claimed und werden nicht automatisch erneut publiziert.

### Eine Verkaufsdatei wurde mehrfach gesendet

Prüfen:
- läuft noch eine ältere Container-Version ohne `.processing`-Claiming?
- ist dieselbe Datei mehrfach neu in `local.inputDir` abgelegt worden?
- wurde eine Datei aus `.processing` manuell zurück nach `local.inputDir` verschoben?
