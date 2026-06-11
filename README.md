# 🛡️ zM SOS GUARD - Pure Vanilla Forensic Surveillance System

![Android 16 Compatible](https://img.shields.io/badge/Android-16%20Ready-brightgreen?style=for-the-badge&logo=android)
![Size](https://img.shields.io/badge/Package%20Size-<500%20KB-blue?style=for-the-badge)
![Dependencies](https://img.shields.io/badge/Dependencies-Zero%20(Pure%20Vanilla)-red?style=for-the-badge)
![Network](https://img.shields.io/badge/Networking-Native%20HttpURLConnection-purple?style=for-the-badge)

**zM SOS GUARD** è un protocollo d'élite per la sorveglianza d'emergenza e la resilienza forense su dispositivi Android. Progettato per operare in scenari critici e ad alto stress, il sistema trasforma lo smartphone in una "Scatola Nera" indistruttibile. 

A differenza delle moderne app commerciali, gonfie di framework pesanti, zM SOS GUARD è sviluppato in **Pure Vanilla Kotlin/XML**, eliminando completamente Jetpack Compose, OkHttp e librerie di terze parti per garantire un peso piuma **inferiore ai 500Kb**, tempi di avvio istantanei e un'architettura impossibile da abbattere.

---

## ⚡ Filosofia Architetturale & Requisiti Hardcore

* **Zero Framework Overhead:** La rimozione di Jetpack Compose e delle dipendenze di rete esterne riduce la superficie d'attacco e l'impronta termica/energetica sul dispositivo.
* **Retrocompatibilità & Longevità Totale:** Sfruttando esclusivamente i pilastri nativi dell'SDK Android, il comportamento dell'applicazione è protetto dalle alterazioni del sistema operativo, garantendo pieno supporto da Marshmallow fino ad Android 16+.
* **Localizzazione Globale Nativa:** Gestita tramite qualificatori di risorse di sistema (`res/values-xx/strings.xml`). Parla le principali lingue del mondo senza iniettare stringhe hardcoded o pesanti dizionari JSON esterni, preservando l'efficienza del compilatore.

---

## 🛠️ Analisi Tecnica del Core: `PanicService.kt`

Il cuore ingegneristico del sistema risiede nel `PanicService`, un **Foreground Service** ad alta priorità strutturato per l'invulnerabilità dei flussi audio/video/GPS.

### 1. Gestione dei Chunk Senza Interruzioni (Gapless Recording)
Il servizio fraziona la registrazione video in segmenti temporali configurabili (default: `20 secondi`). Sfruttando i codici nativi del `MediaRecorder`:
* **Info Code 802 (`MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`):** Il servizio alloca preventivamente il file successivo tramite la chiamata nativa `mediaRecorder?.setNextOutputFile(nextFile)` (Android 8+).
* **Info Code 803 (`MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`):** Lo switch del file di output avviene a livello hardware **senza perdere un singolo frame** o interrompere il flusso audio/video. I file chiusi vengono immediatamente passati al thread di sincronizzazione.

### 2. The Ghost Engine (Stealth Mode Totale)
Quando viene passata la flag `EXTRA_HIDDEN`, il servizio muta la sua impronta sul dispositivo per operare in totale camuffamento:
* **Silenziamento Hardware:** Sfrutta l'`AudioManager` per azzerare istantaneamente `STREAM_SYSTEM`, `STREAM_RING` e `STREAM_NOTIFICATION`, disattivando anche il suono di scatto della fotocamera (`camera?.enableShutterSound(false)`).
* **Notifica Camaleontica:** Il canale di notifica passa a `VISIBILITY_SECRET` con priorità minima. L'icona viene sostituita con `stat_notify_sync` (le frecce di sincronizzazione di sistema standard) e i testi vengono alterati per simulare un normale processo di sincronizzazione cloud in background (es. Google Drive/Foto).
* **Soppressione Feedback:** Vengono disattivati tutti i Toast loggando direttamente sul canale protetto di sistema `Log.d`, e viene soppressa la vibrazione hardware di notifica chunk.

### 3. Resilienza di Rete a Livello di Processo
* **Binding di Rete Forzato:** Tramite `connectivityManager.bindProcessToNetwork(network)`, l'intero processo dell'applicazione viene agganciato all'interfaccia di rete che garantisce l'effettivo transito internet. 
* **Auto-Riconnessione Dinamica:** In caso di repentino switch di cella (es. passaggio forzato da Wi-Fi a 5G o perdita temporanea di segnale sotto stress), un ciclo ricorsivo riapre una `requestNetwork` ogni 5000ms per riagganciare il socket non appena una rotta torna disponibile.
* **Sorgenti Energetiche Blindate:** L'attivazione combinata di un `WakeLock` parziale (timeout 12 ore) e di un `WifiLock` in modalità `WIFI_MODE_FULL_HIGH_PERF` impedisce alla CPU e alla scheda Wi-Fi di entrare in IDLE o Doze Mode, anche a schermo spento.

### 4. Flusso di Sincronizzazione Raw (Zero dependencies)
Il modulo `syncFiles()` esegue un loop continuo in un thread dedicato:
* Spazzola la cartella di registrazione `zMPanicRec` isolando i file `.mp4` conclusi.
* Apre una connessione nativa `HttpURLConnection` / `HttpsURLConnection`.
* **Bypass SSL:** Implementa un `HostnameVerifier` tollerante (`{ _, _ -> true }`) per accettare certificati self-signed, ideale per server di ascolto privati allestiti al volo.
* **Payload Ottimizzato:** Il file viene trasmesso come stream binario puro (`application/octet-stream`), azzerando l'overhead dei formati Multipart/Form-Data.
* **Metadati negli Header:** I dati forensi (Nome file, coordinate GPS) non vengono iniettati nel body, ma passati direttamente come Header HTTP (`GPS-Latitude`, `GPS-Longitude`), velocizzando il parsing lato server. Al successo della richiesta, il file locale viene rinominato in `.synced.mp4`.

### 5. Bypass del Blocco Schermo (Keyguard Retry Logic)
Se lo schermo del dispositivo si blocca prima dell'inizializzazione del servizio, l'hardware della fotocamera potrebbe risultare temporaneamente interdetto. Il sistema implementa un ciclo di recupero con 5 tentativi distanziati da 250ms per forzare l'aggancio del sensore non appena le policy di sicurezza lo rilasciano, sfruttando una `SurfaceTexture` fittizia (`dummySurfaceTexture = SurfaceTexture(10)`) per registrare in background senza bisogno di una UI visibile a schermo.

---

## 📂 Architettura dei File

```text
zMPanic/
├── app/src/main/
│   ├── java/com/mykerd/panic/
│   │   ├── MainActivity.kt      # Interfaccia Elettrica Neon, Gestione Permessi Runtime
│   │   └── PanicService.kt      # Core Engine (Registrazione, Silenziatore, Sync Nativo)
│   └── res/                     # Asset XML Puri & Localizzazione Internazionale
└── server/
    └── server.py                # Backend di Ricezione (Python)

    ## 🖥️ Backend Receiver (`server.py`)

In linea con la filosofia "Zero Dependency" del client Android, il backend di ricezione è ingegnerizzato in **Pure Python**, sfruttando esclusivamente la libreria standard senza richiedere framework pesanti come Django o FastAPI. Il server agisce come un punto di ascolto asincrono ed efficiente in grado di elaborare stream binari raw.

Ecco l'implementazione completa da inserire nel file `server/server.py`:

```python
import os
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT = 9999
UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "zM_Vault")

if not os.path.exists(UPLOAD_DIR):
    os.makedirs(UPLOAD_DIR)

class PanicReceiverHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/upload":
            try:
                # Estrazione metadati forensi direttamente dagli Header HTTP
                file_name = self.headers.get("File-Name", f"SOS_UNK_{int(self.headers.get('Content-Length', 0))}.mp4")
                latitude = self.headers.get("GPS-Latitude", "0.0")
                longitude = self.headers.get("GPS-Longitude", "0.0")
                content_length = int(self.headers.get("Content-Length", 0))

                print("\n" + "="*50)
                print(f"🚨 [ALERT SOS] Ricezione pacchetto video: {file_name}")
                print(f"📍 [COORDINATE] GPS: {latitude}, {longitude}")
                print(f"📦 [DIMENSIONE] Data Stream: {content_length} bytes")
                print("="*50)

                # Lettura dello stream binario puro e scrittura immediata su disco
                filepath = os.path.join(UPLOAD_DIR, file_name)
                with open(filepath, "wb") as f:
                    remaining = content_length
                    buffer_size = 64 * 1024  # Buffer da 64KB per massimizzare l'I/O
                    while remaining > 0:
                        chunk = self.rfile.read(min(remaining, buffer_size))
                        if not chunk:
                            break
                        f.write(chunk)
                        remaining -= len(chunk)

                # Generazione file di log geolocalizzato speculare per scopi forensi
                log_path = filepath.replace(".mp4", ".meta.txt")
                with open(log_path, "w", encoding="utf-8") as meta_f:
                    meta_f.write(f"File: {file_name}\n")
                    meta_f.write(f"Latitude: {latitude}\n")
                    meta_f.write(f"Longitude: {longitude}\n")
                    meta_f.write(f"Maps Link: [https://www.google.com/maps/search/?api=1&query=](https://www.google.com/maps/search/?api=1&query=){latitude},{longitude}\n")

                # Risposta HTTP 200 OK immediata per liberare il thread mobile
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"SUCCESS: Chunk secured.")
                print(f"✅ [SUCCESS] File salvato correttamente in: {filepath}")

            except Exception as e:
                print(f"❌ [INTERNAL ERROR] Errore durante il processing dello stream: {str(e)}")
                self.send_response(500)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        # Silenzia i log standard di loggin HTTP per pulizia della console forense
        return

def run():
    server_address = ('', PORT)
    httpd = HTTPServer(server_address, PanicReceiverHandler)
    print(f"🛡️ zM SOS GUARD Server in ascolto sulla porta {PORT}...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 Server arrestato in sicurezza.")

if __name__ == "__main__":
    run()

    Come avviare il server:Posizionarsi nella cartella ed eseguire lo script tramite terminale. Non sono necessari comandi pip install.Bashpython server.py
# oppure su sistemi Unix-like:
python3 server.py
🔌 Specifiche del Protocollo di Rete (API Mapping)La comunicazione avviene tramite richieste stateless ad alta velocità per evitare handshake prolungati.Endpoint: POST http://<SERVER_IP>:<PORT>/uploadContent-Type: application/octet-stream (Stream binario grezzo)Intestazioni HTTP Richieste (Custom Headers)HeaderTipoDescrizioneEsempioFile-NameStringNome del file generato sul client contenente il timestamp UnixSOS_1718115648000.mp4GPS-LatitudeStringLatitudine estratta dall'ultimo fix valido del LocationManager41.9028GPS-LongitudeStringLongitudine estratta dall'ultimo fix valido del LocationManager12.4964⚙️ Permessi di Sistema Richiesti (AndroidManifest.xml)Per garantire il corretto funzionamento delle chiamate a basso livello e bypassare i blocchi energetici di Android, l'applicazione deve dichiarare ed esplicitare i seguenti permessi nel manifest:XML<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
📱 Perché Esclusiva Android? (Il Muro di iOS)Il protocollo zM SOS GUARD è strutturalmente e filosoficamente incompatibile con l'ecosistema iOS a causa delle restrizioni hardware e software imposte da Apple per preservare il proprio monopolio commerciale:  Gabbia del Background: iOS taglia istantaneamente l'alimentazione e sospende l'AVCaptureSession (la fotocamera) non appena l'app perde il primo piano, viene ridotta a icona o lo schermo si spegne. Non esiste un equivalente del Foreground Service di Android che consenta l'accesso continuativo alla fotocamera in background.Indicatori Hardware Infiltrati: Il pallino arancione/verde della privacy nella barra di stato di iOS è legato rigidamente all'hardware del dispositivo e non può essere aggirato via software, rendendo vana qualsiasi implementazione di una modalità Stealth.Distribuzione Centralizzata Controllata: Android permette la compilazione nativa e l'installazione immediata tramite file APK autonomi (Sideloading in due clic) a costo zero. Apple impone una barriera hardware (obbligo di un computer Mac per compilare in Xcode), una tassa annuale di 100€ e restrizioni che bloccano le app non firmate dopo soli 7 giorni se installate tramite store alternativi gratuiti.⚖️ Dichiarazione di Intenti & Note Legali  Questo software è uno strumento di tutela forense, documentazione d'emergenza e ricerca tecnica sulla resilienza dei sistemi operativi mobili, rilasciato esclusivamente per scopi di sicurezza e protezione personale.L'utilizzatore si assume la piena e totale responsabilità civile e penale derivante dall'utilizzo di funzionalità di registrazione nascosta, in stretta conformità con le leggi locali vigenti in materia di privacy, trattamento dei dati personali e intercettazione di comunicazioni, immagini e audio. Lo sviluppatore e i contributori del progetto non si assumono alcuna responsabilità per danni, usi impropri, illeciti o applicazioni non autorizzate del codice sorgente qui documentato.
