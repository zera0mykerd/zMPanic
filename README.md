# 🛡️ zM SOS GUARD - Pure Vanilla Forensic Surveillance System

![Android 16 Compatible](https://img.shields.io/badge/Android-16%20Ready-brightgreen?style=for-the-badge&logo=android)
![Size](https://img.shields.io/badge/Package%20Size-<500%20KB-blue?style=for-the-badge)
![Dependencies](https://img.shields.io/badge/Dependencies-Zero%20(Pure%20Vanilla)-red?style=for-the-badge)
![Network](https://img.shields.io/badge/Networking-Native%20HttpURLConnection-purple?style=for-the-badge)

**zM SOS GUARD** is an elite protocol for emergency surveillance and forensic resilience on Android devices. Designed to operate in critical, high-stress scenarios, the system transforms the smartphone into an indestructible "Black Box". 

Unlike modern commercial apps, bloated with heavy frameworks, zM SOS GUARD is developed in **Pure Vanilla Kotlin/XML**, completely eliminating Jetpack Compose, OkHttp, and third-party libraries to guarantee a featherweight footprint of **under 500KB**, instant launch times, and an un-killable architecture.

---

## ⚡ Architectural Philosophy & Hardcore Requirements

* **Zero Framework Overhead:** Removing Jetpack Compose and external network dependencies reduces the attack surface and the thermal/energy footprint on the device.
* **Total Retrocompatibility & Longevity:** By relying exclusively on native Android SDK pillars, the application's behavior is protected from OS alterations, ensuring full support from Marshmallow up to Android 16+.
* **Native Global Localization:** Managed via system resource qualifiers (`res/values-xx/strings.xml`). It speaks the world's major languages without injecting hardcoded strings or heavy external JSON dictionaries, preserving compiler efficiency.

---

## 🛠️ Core Technical Analysis: `PanicService.kt`

The engineering core of the system lies within the `PanicService`, a high-priority **Foreground Service** structured for the absolute invulnerability of audio/video/GPS streams.

### 1. Gapless Chunk Management (Seamless Recording)
The service splits the video recording into configurable time segments (default: `20 seconds`). Leveraging native `MediaRecorder` codes:
* **Info Code 802 (`MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`):** The service pre-allocates the next file using the native `mediaRecorder?.setNextOutputFile(nextFile)` call (Android 8+).
* **Info Code 803 (`MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`):** The output file switch occurs at the hardware level **without losing a single frame** or interrupting the audio/video stream. Closed files are immediately handed over to the synchronization thread.

### 2. The Ghost Engine (Total Stealth Mode)
When the `EXTRA_HIDDEN` flag is passed, the service mutates its device footprint to operate in complete camouflage:
* **Hardware Muting:** It utilizes the `AudioManager` to instantly zero out `STREAM_SYSTEM`, `STREAM_RING`, and `STREAM_NOTIFICATION`, also disabling the camera shutter sound (`camera?.enableShutterSound(false)`).
* **Chameleon Notification:** The notification channel switches to `VISIBILITY_SECRET` with minimum priority. The icon is replaced with `stat_notify_sync` (standard system sync arrows) and the texts are altered to mimic a normal background cloud synchronization process (e.g., Google Drive/Photos).
* **Feedback Suppression:** All Toasts are deactivated, logging directly to the protected system channel `Log.d`, and the hardware vibration for chunk notifications is suppressed.

### 3. Process-Level Network Resilience
* **Forced Network Binding:** Via `connectivityManager.bindProcessToNetwork(network)`, the entire application process is locked onto the network interface that guarantees actual internet transit. 
* **Dynamic Auto-Reconnection:** In the event of a sudden cell tower switch (e.g., forced transition from Wi-Fi to 5G or temporary signal loss under stress), a recursive loop reopens a `requestNetwork` every 5000ms to re-engage the socket as soon as a route becomes available.
* **Armored Power Sources:** The combined activation of a partial `WakeLock` (12-hour timeout) and a `WifiLock` in `WIFI_MODE_FULL_HIGH_PERF` prevents the CPU and Wi-Fi card from entering IDLE or Doze Mode, even when the screen is off.

### 4. Raw Synchronization Flow (Zero Dependencies)
The `syncFiles()` module runs a continuous loop on a dedicated thread:
* It scans the recording folder `zMPanicRec`, isolating completed `.mp4` files.
* It opens a native `HttpURLConnection` / `HttpsURLConnection`.
* **SSL Bypass:** It implements a tolerant `HostnameVerifier` (`{ _, _ -> true }`) to accept self-signed certificates, ideal for privately hosted listening servers deployed on the fly.
* **Optimized Payload:** The file is transmitted as a pure binary stream (`application/octet-stream`), eliminating the overhead of Multipart/Form-Data formats.
* **Metadata in Headers:** Forensic data (File name, GPS coordinates) are not injected into the body, but passed directly as HTTP Headers (`GPS-Latitude`, `GPS-Longitude`), accelerating server-side parsing. Upon a successful request, the local file is renamed to `.synced.mp4`.

### 5. Lock Screen Bypass (Keyguard Retry Logic)
If the device screen locks before the service initializes, the camera hardware might be temporarily blocked. The system implements a recovery cycle with 5 attempts spaced 250ms apart to force sensor acquisition as soon as security policies release it, using a dummy surface texture (`dummySurfaceTexture = SurfaceTexture(10)`) to record in the background without needing a visible UI on screen.

---

## 📂 File Architecture

```text
zMPanic/
├── app/src/main/
│   ├── java/com/mykerd/panic/
│   │   ├── MainActivity.kt      # Neon Electrical Interface, Runtime Permission Management
│   │   └── PanicService.kt      # Core Engine (Recording, Muter, Native Sync)
│   └── res/                     # Pure XML Assets & International Localization
└── server/
    └── server.py                # Receiving Backend (Python)

 ## 🖥️ Backend Receiver (`server.py`)

In line with the "Zero Dependency" philosophy of the Android client, the receiving backend is engineered in Pure Python, utilizing exclusively the standard library without requiring heavy frameworks like Django or FastAPI. The server acts as an asynchronous, efficient listening point capable of processing raw binary streams.

Here is the complete implementation to be placed in the server/server.py file:

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
                # Extract forensic metadata directly from HTTP Headers
                file_name = self.headers.get("File-Name", f"SOS_UNK_{int(self.headers.get('Content-Length', 0))}.mp4")
                latitude = self.headers.get("GPS-Latitude", "0.0")
                longitude = self.headers.get("GPS-Longitude", "0.0")
                content_length = int(self.headers.get("Content-Length", 0))

                print("\n" + "="*50)
                print(f"🚨 [SOS ALERT] Video package received: {file_name}")
                print(f"📍 [COORDINATES] GPS: {latitude}, {longitude}")
                print(f"📦 [SIZE] Data Stream: {content_length} bytes")
                print("="*50)

                # Read the pure binary stream and write immediately to disk
                filepath = os.path.join(UPLOAD_DIR, file_name)
                with open(filepath, "wb") as f:
                    remaining = content_length
                    buffer_size = 64 * 1024  # 64KB buffer to maximize I/O performance
                    while remaining > 0:
                        chunk = self.rfile.read(min(remaining, buffer_size))
                        if not chunk:
                            break
                        f.write(chunk)
                        remaining -= len(chunk)

                # Generate a mirrored geolocated log file for forensic purposes
                log_path = os.path.splitext(filepath)[0] + ".meta.txt"
                with open(log_path, "w", encoding="utf-8") as meta_f:
                    meta_f.write(f"File: {file_name}\n")
                    meta_f.write(f"Latitude: {latitude}\n")
                    meta_f.write(f"Longitude: {longitude}\n")
                    meta_f.write(f"Maps Link: [https://maps.google.com/maps?q=](https://maps.google.com/maps?q=){latitude},{longitude}\n")

                # Immediate HTTP 200 OK response to free up the mobile thread
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"SUCCESS: Chunk secured.")
                print(f"✅ [SUCCESS] File correctly saved to: {filepath}")

            except Exception as e:
                print(f"❌ [INTERNAL ERROR] Error processing the stream: {str(e)}")
                self.send_response(500)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        # Silence standard HTTP logging to keep the forensic console clean
        return

def run():
    server_address = ('', PORT)
    httpd = HTTPServer(server_address, PanicReceiverHandler)
    print(f"🛡️ zM SOS GUARD Server listening on port {PORT}...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n🛑 Server safely stopped.")

if __name__ == "__main__":
    run()
```
🚀 How to Start the Server
Navigate to the directory and execute the script via terminal. No pip install commands are required.
python server.py
# or on Unix-like systems:
python3 server.py

python3 server.py


## 🔌 Network Protocol Specifications (API Mapping)
Communication occurs via high-speed stateless requests to prevent prolonged handshakes.

Endpoint: POST http://<SERVER_IP>:<PORT>/upload

Content-Type: application/octet-stream (Raw binary stream)

Required HTTP Headers (Custom Headers)HeaderTypeDescriptionExampleFile-NameStringFile name generated on the client containing the Unix timestampSOS_1718115648000.mp4GPS-LatitudeStringLatitude extracted from the last valid fix of the LocationManager41.9028GPS-LongitudeStringLongitude extracted from the last valid fix of the LocationManager12.4964⚙️ Required System Permissions (AndroidManifest.xml)To ensure the correct execution of low-level calls and bypass Android's battery optimization restrictions, the application must declare and explicitate the following permissions in the manifest:

<uses-permission android:name="android.permission.CAMERA" />
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

## 📱 Why Android Exclusive? (The iOS Wall)
The zM SOS GUARD protocol is structurally and philosophically incompatible with the iOS ecosystem due to hardware and software restrictions imposed by Apple to preserve its commercial monopoly:  

Background Cage: iOS instantly cuts power and suspends AVCaptureSession (the camera) as soon as the app loses foreground focus, is minimized, or the screen turns off. There is no equivalent to Android’s Foreground Service that allows continuous background camera access.

Infiltrated Hardware Indicators: The orange/green privacy dot in the iOS status bar is rigidly tied to the device hardware and cannot be bypassed via software, rendering any stealth mode implementation useless.

Controlled Centralized Distribution: Android allows native compilation and immediate installation via standalone APK files (Sideloading in two clicks) at zero cost. Apple imposes a hardware barrier (requiring a Mac computer to compile in Xcode), a €100 annual fee, and restrictions that block unsigned apps after just 7 days if installed via alternative free stores.

## ⚖️ Intent Declaration & Legal Notes
  
This software is a tool for forensic protection, emergency documentation, and technical research into mobile operating system resilience, released exclusively for security and personal protection purposes.

The user assumes full and total civil and criminal responsibility arising from the use of hidden recording features, in strict compliance with local laws in force regarding privacy, personal data processing, and interception of communications, images, and audio. The developer and project contributors assume no responsibility for damages, improper use, illicit actions, or unauthorized applications of the source code documented herein.
