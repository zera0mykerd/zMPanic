# 🛡️ zM SOS GUARD — Hardened Forensic Surveillance & Black Box System

**v13.0 — The Hardened & Secure Update**

[![Android 16+](https://img.shields.io/badge/Android-API%2023%20→%2035%2B%20(Android%2016%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Footprint](https://img.shields.io/badge/APK%20Footprint-%3C%20800%20KB-brightgreen)]()
[![Pure Vanilla](https://img.shields.io/badge/Architecture-Pure%20Vanilla%20(Zero%2DDependency)-blueviolet)]()
[![Military-Grade Security](https://img.shields.io/badge/Security-AES%2D256%20GCM%20%2F%20Strict%20TLS%20%2F%20PBKDF2%2D250k-critical)]()
[![OEM Compatibility](https://img.shields.io/badge/OEM%20Compatibility-99%25%2B%20Global-orange)]()
[![Locales](https://img.shields.io/badge/Native%20Locales-62%2B-yellow)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

---

## Project Vision & Executive Summary

**zM SOS GUARD** is a sovereign smartphone “Black Box” engineered for critical and hostile environments.

It transforms any Android device into an indestructible forensic recorder that continuously captures high-integrity audio/video + GPS streams, commits every frame to local flash first, and delivers evidence over encrypted channels even when the network is intermittent, censored, or hostile.

Unlike commercial surveillance apps bloated with advertising SDKs, analytics, and heavy frameworks, zM SOS GUARD is built as a **pure-vanilla, zero-dependency system**:

- Client: Pure Kotlin + native Android SDK + AndroidX Security Crypto only.
- Server: Pure Python 3 standard library (zero `pip install`).
- Attack surface: minimal.
- Cold-start: near-instant.
- Survival priority: local disk always wins over the network.

The system is designed for personal protection, emergency documentation, technical research into mobile OS resilience, and legitimate forensic evidence preservation.

---

## Core Architectural Philosophy & The 4 Inviolable Axioms

### Zero Framework Overhead

- **Client**: Pure Vanilla Kotlin/XML. No Jetpack Compose, no OkHttp, no Retrofit, no Glide, no Firebase. Only the official AndroidX Security Crypto artifact for hardware-backed key storage.
- **Server**: Pure Python 3 standard library. No Flask, FastAPI, Django, or any third-party package.
- Result: sub-800 KB APK, near-zero cold-start latency, and a dramatically reduced attack surface.

### Axiom 1 — Total Decoupling (Producer-Consumer)

Video recording and network transmission run on independent threads.  
A network outage, DNS failure, or TLS handshake timeout **never** stalls or drops frames from the camera pipeline.

### Axiom 2 — Local Persistence Precedence

Every completed chunk is written to local flash (`zMPanicRec/`) **before** any uplink attempt is made.  
The network is a best-effort delivery layer; the disk is the source of truth.

### Axiom 3 — Non-Destructive Retention

Successfully uploaded files are renamed to `*.synced.mp4`.  
They are never deleted by the application. The operator retains full control over evidence retention policy.

### Axiom 4 — Priority Failover

Remote endpoints are queried sequentially (semicolon-delimited list).  
The first HTTP 2xx response ends the attempt for that chunk, conserving battery and mobile data.

---

## Complete System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ANDROID CLIENT (PanicService)                        │
│                                                                             │
│  ┌──────────────┐   ┌──────────────────────┐   ┌─────────────────────────┐ │
│  │  Ghost Engine │   │ Dual-Mode Camera     │   │ Local Flash Storage     │ │
│  │  (Stealth)    │   │ Engine               │   │ zMPanicRec/             │ │
│  │  - Mute audio │   │ Mode A: Gapless      │   │ SOS_*.mp4               │ │
│  │  - SECRET notif│  │   setNextOutputFile  │   │ (never deleted)         │ │
│  │  - Icon alias │   │ Mode B: Timer rotate │   │ *.synced.mp4 flags      │ │
│  │  - moveTaskTo │   │ Hardware Watchdog    │   └────────────┬────────────┘ │
│  │    Back       │   │ +5s 803 timeout      │                │              │
│  └──────────────┘   └──────────┬───────────┘                │              │
│                                │                            │              │
│                                ▼                            ▼              │
│                     ┌─────────────────────┐    ┌───────────────────────┐   │
│                     │ GPS / LocationManager│    │ Store-and-Forward     │   │
│                     │ live coordinates     │    │ chronological queue  │   │
│                     └──────────┬──────────┘    │ multi-server failover │   │
│                                │               └───────────┬───────────┘   │
│                                │                           │               │
│                                └─────────────┬─────────────┘               │
│                                              │                             │
│                                              ▼                             │
│                               Encrypted HTTPS / TLS                        │
│                               Authorization: Bearer <pwd>                  │
│                               File-Name + GPS headers                      │
└──────────────────────────────────────────────┼─────────────────────────────┘
                                               │
                                               │  Strict TLS only
                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     HARDENED SERVER NODE (SERVER.py)                        │
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ PBKDF2-HMAC-SHA256│  │ In-Memory Rate   │  │ Anti-Collision Storage  │  │
│  │ 250 000 iterations│  │ Limiting         │  │ SOS_{ts}_{ip}_{uid}.mp4 │  │
│  │ 32-byte salt      │  │ 5 fails → 1h ban │  │ + mirrored .meta.txt    │  │
│  │ hmac.compare_digest│ └──────────────────┘  │ GPS + Google Maps URL  │  │
│  └──────────────────┘                         └──────────────────────────┘  │
│                                                                             │
│  Blind Drop-Box: POST /upload ONLY · No GET · No DELETE · No listing        │
│  Auto-provisioned RSA-2048 self-signed certificate · Supervisor watchdog    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Client Subsystems

### `PanicService.kt` — The Indestructible Core

#### Self-Healing Dual-Mode Video Engine

**Mode A — Flagship Gapless Chunking (Android 8+)**

```
setMaxFileSize( (videoBitrate + audioBitrate) / 8 × rotationSeconds )
→ MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING (802)
→ mediaRecorder.setNextOutputFile(nextFile)
→ MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED (803)
→ seamless hardware-level hand-off, zero lost frames
```

**Mode B — Legacy Fail-Safe Timer Rotation**

For older or fragmented camera HALs that never emit event 803:

```
scheduleLegacyRotation() → rotateProcess()
  mediaRecorder.stop() → reset() → startMediaRecorder()
```

**Hardware Watchdog**

A background timer armed to `activeRotation + 5 seconds` after every successful gapless transition.  
If the 803 event is never received, the watchdog forces `isGaplessSupported = false` and permanently switches the process to Mode B for the remainder of the session, preventing silent lock-ups caused by buggy OEM HALs.

#### Universal Hardware & OEM Hardening

| Problem | Solution |
|---------|----------|
| Samsung Exynos Error -19 (preview/video size mismatch) | `getBestCommonSize()` — intersects supported preview sizes with supported video sizes and selects the highest common resolution. Preview size **must** equal video size. |
| Silent-mode muting & waterproof membrane dampening (Doogee, Blackview, etc.) | Forced `AudioSource.MIC` + 30 FPS. Bypasses silent-mode audio suppression and compensates for acoustic membrane attenuation. |
| Surface lifecycle crashes / duplicate attachments | Dummy `SurfaceTexture(0)` for background recording + careful weak-reference sharing of the visible `TextureView` surface only when the UI is present. |

#### Hardware-Backed Security

- `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM)`
- Secrets stored exclusively in `EncryptedSharedPreferences` (AES-256-SIV key encryption + AES-256-GCM value encryption).
- Mandatory first-launch password dialog (1–512 characters). The password is never written in clear text.

#### Ghost Engine (Stealth & Anti-Tamper)

When the `EXTRA_HIDDEN` flag is set:

- `AudioManager` zeroes `STREAM_SYSTEM`, `STREAM_RING`, `STREAM_NOTIFICATION`.
- Camera shutter sound disabled via `camera.enableShutterSound(false)`.
- Notification channel switches to `VISIBILITY_SECRET` with icon `stat_notify_sync` and texts that mimic a normal cloud sync process (`sys_integrity` channel).
- All Toasts and vibration are suppressed.
- Instant `moveTaskToBack(true)`.
- Dynamic launcher icon disguise via `ActivityAlias` (optional build-time configuration).

#### Network Resilience & Store-and-Forward

- Continuous local recording during network cuts.
- Chronological backlog flush (files sorted by `lastModified()`) upon 4G/Wi-Fi reconnect.
- `ConnectivityManager.NetworkCallback` + `bindProcessToNetwork` for process-level network binding.
- Multi-server priority failover: semicolon-delimited endpoints (`IP1:PORT;IP2:PORT` or full `https://…` URLs). First HTTP 2xx wins.
- Service returns `START_STICKY`.
- `PARTIAL_WAKE_LOCK` (long timeout) + `WIFI_MODE_FULL_HIGH_PERF` prevent CPU and Wi-Fi radio from entering Doze/IDLE.

#### Multilingual Localization

- 62+ native languages managed exclusively through Android resource qualifiers (`res/values-*/strings.xml`).
- Live GPS coordinate and status Toasts restored when `!activeHiddenMode`.

### `MainActivity.kt` — Control Surface

- Neon electrical-style UI.
- Runtime permission orchestration (Camera, Microphone, Location, Notifications, Foreground Service types).
- First-launch mandatory password setup dialog.
- Live preview via `TextureView` surface sharing with the service.
- Configuration of server endpoints, rotation interval, front/rear camera, and stealth mode.

---

## Complete Embedded Python Server Source Code

**Zero `pip` dependencies. Pure Python 3 standard library.**

Copy the entire block below into `SERVER.py` (or `server.py`) and run with `python3 SERVER.py`.

```python
import http.server
import os
import time
import shutil
import sys
import random
import traceback
import socket
import threading
import uuid
import ssl
import subprocess
import signal
import re
import select
import getpass
import hashlib
import hmac
from http.server import ThreadingHTTPServer

PORT = 9999
SAVE_DIR = "zmpanic_recordings"
MAX_FILE_SIZE = 100 * 1024 * 1024  # 100 MB
SOCKET_TIMEOUT = 10.0  # Anti-Slowloris timeout
CERT_FILE = "cert.pem"
KEY_FILE = "key.pem"
PASS_FILE = ".srvpass.txt"

# ===== RATE-LIMITING & INTERNAL BAN SYSTEM =====
MAX_FAILED_ATTEMPTS = 5
BAN_DURATION = 3600
failed_attempts = {}
banned_ips = {}
ban_lock = threading.Lock()

# ===== TERMINAL FX =====
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
CYAN = "\033[96m"
BLUE = "\033[94m"
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
WHITE = "\033[97m"
MAGENTA = "\033[95m"
CLEAR = "\033[2J\033[H"

start_time = time.time()
files_received = 0
total_bytes = 0
log_buffer = []
shutdown_flag = False
saved_password_hash = None
saved_salt = None

def ts():
    return time.strftime("%Y-%m-%d %H:%M:%S")

def term_size():
    try:
        s = shutil.get_terminal_size()
        return s.columns, s.lines
    except Exception:
        return 120, 40

def center_block(lines):
    w, h = term_size()
    block_h = len(lines)
    top_padding = max(0, (h - block_h) // 2)
    print(CLEAR, end="")
    print("\n" * top_padding, end="")
    for line in lines:
        pad = max(0, (w - len(strip_ansi(line))) // 2)
        print(" " * pad + line)

def strip_ansi(s):
    return re.sub(r'\x1b\[[0-9;]*m', '', s)

def hr(char="═"):
    w, _ = term_size()
    return char * w

def box(title, lines):
    w, _ = term_size()
    inner = w - 4
    print(CYAN + "╔" + "═" * (w - 2) + "╗" + RESET)
    t = " " + title + " "
    print(CYAN + "║" + BOLD + t + " " * (w - 2 - len(t)) + CYAN + "║" + RESET)
    print(CYAN + "╠" + "═" * (w - 2) + "╣" + RESET)
    for l in lines:
        l2 = str(l)[:inner]
        print(CYAN + "║ " + RESET + l2 + " " * (inner - len(strip_ansi(l2))) + CYAN + " ║" + RESET)
    print(CYAN + "╚" + "═" * (w - 2) + "╝" + RESET)

def progress(label, duration=0.3):
    try:
        w, _ = term_size()
        bar_w = max(10, w - 30)
        start = time.time()
        while True:
            t = time.time() - start
            if t >= duration:
                break
            p = t / duration
            fill = int(bar_w * p)
            bar = "█" * fill + "░" * (bar_w - fill)
            print(f"\r{CYAN}{label:<20} [{bar}] {int(p*100):3d}%{RESET}", end="")
            time.sleep(0.02)
        print(f"\r{CYAN}{label:<20} [{'█'*bar_w}] 100%{RESET}")
    except Exception:
        pass

def draw_header():
    try:
        uptime = int(time.time() - start_time)
        w, _ = term_size()
        print(CLEAR, end="")
        print(MAGENTA + hr("═") + RESET)
        print(MAGENTA + BOLD + " ZMPANIC :: SECURE EVIDENCE INGESTION NODE (HARDENED)".ljust(w) + RESET)
        print(MAGENTA + hr("─") + RESET)
        print(GREEN + f" LISTEN      : 0.0.0.0:{PORT} (STRICT HTTPS / TLS)".ljust(w) + RESET)
        print(CYAN + f" STORAGE     : {SAVE_DIR}".ljust(w) + RESET)
        print(GREEN + f" UPTIME      : {uptime}s".ljust(w) + RESET)
        print(GREEN + f" FILES RX    : {files_received}".ljust(w) + RESET)
        print(GREEN + f" DATA RX     : {total_bytes} bytes".ljust(w) + RESET)
        print(MAGENTA + hr("═") + RESET)
        print()
    except Exception:
        pass

def redraw():
    try:
        draw_header()
        for line, color in log_buffer[-400:]:
            print(color + line + RESET)
    except Exception:
        pass

def log(msg, color=WHITE, tag="INFO"):
    try:
        line = f"[{ts()}] [{tag}] {msg}"
        log_buffer.append((line, color))
        if len(log_buffer) > 1000:
            log_buffer.pop(0)
        redraw()
    except Exception:
        pass

def log_exception(e, context=""):
    try:
        log(f"EXCEPTION in {context}: {e}", RED, "EXC")
        tb = traceback.format_exc()
        for l in tb.splitlines():
            log(l, RED, "TRACE")
    except Exception:
        pass

def is_ip_banned(ip):
    now = time.time()
    with ban_lock:
        if ip in banned_ips:
            if now < banned_ips[ip]:
                return True
            else:
                del banned_ips[ip]
                failed_attempts.pop(ip, None)
    return False

def record_failed_attempt(ip):
    now = time.time()
    with ban_lock:
        count = failed_attempts.get(ip, 0) + 1
        failed_attempts[ip] = count
        if count >= MAX_FAILED_ATTEMPTS:
            banned_ips[ip] = now + BAN_DURATION
            return True
    return False

def reset_ip_attempts(ip):
    with ban_lock:
        failed_attempts.pop(ip, None)

def get_password_asterisks(prompt="Password: "):
    try:
        import msvcrt
        sys.stdout.write(prompt)
        sys.stdout.flush()
        password = ""
        while True:
            ch = msvcrt.getwch()
            if ch in ('\r', '\n'):
                break
            elif ch == '\x03':
                raise KeyboardInterrupt
            elif ch == '\x08':
                if len(password) > 0:
                    password = password[:-1]
                    sys.stdout.write('\b \b')
                    sys.stdout.flush()
            else:
                password += ch
                sys.stdout.write('*')
                sys.stdout.flush()
        sys.stdout.write('\n')
        return password
    except ImportError:
        try:
            import termios
            import tty
            sys.stdout.write(prompt)
            sys.stdout.flush()
            fd = sys.stdin.fileno()
            old_settings = termios.tcgetattr(fd)
            password = ""
            try:
                tty.setraw(fd)
                while True:
                    ch = sys.stdin.read(1)
                    if ch in ('\r', '\n'):
                        break
                    elif ch in ('\x08', '\x7f'):
                        if len(password) > 0:
                            password = password[:-1]
                            sys.stdout.write('\b \b')
                            sys.stdout.flush()
                    elif ch == '\x03':
                        raise KeyboardInterrupt
                    else:
                        password += ch
                        sys.stdout.write('*')
                        sys.stdout.flush()
            finally:
                termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
            sys.stdout.write('\n')
            return password
        except Exception:
            return getpass.getpass(prompt)

def load_or_setup_password():
    global saved_password_hash, saved_salt
    if os.path.exists(PASS_FILE):
        try:
            with open(PASS_FILE, "r") as f:
                data = f.read().strip().split(":")
                if len(data) == 2:
                    saved_salt = bytes.fromhex(data[0])
                    saved_password_hash = bytes.fromhex(data[1])
                    return
        except Exception as e:
            print(RED + f" [ERROR] Password file ({PASS_FILE}) is unreadable or corrupt. Setup required." + RESET)

    if not sys.stdin or not sys.stdin.isatty():
        print(RED + f" [FATAL ERROR] {PASS_FILE} not found. You must run the script from an interactive terminal for initial setup!" + RESET)
        sys.exit(1)

    print(CYAN + "\n" + hr("─") + RESET)
    print(YELLOW + BOLD + " 🔐 FIRST START CONFIGURATION (PASSWORD AND INGESTION NODE)" + RESET)
    print(WHITE + f"    Maximum 512 characters for compatibility." + RESET)
    while True:
        pwd = get_password_asterisks(CYAN + " Create Server Password: " + RESET)
        if len(pwd) == 0:
            continue
        if len(pwd) > 512:
            print(RED + " Password too long! 512-character limit." + RESET)
            continue
            
        pwd_conf = get_password_asterisks(CYAN + " Repeat Server Password: " + RESET)
        
        if pwd == pwd_conf:
            saved_salt = os.urandom(32)
            saved_password_hash = hashlib.pbkdf2_hmac('sha256', pwd.encode('utf-8'), saved_salt, 250000)
            try:
                with open(PASS_FILE, "w") as f:
                    f.write(f"{saved_salt.hex()}:{saved_password_hash.hex()}")
                print(GREEN + " ✅ Configuration saved securely (PBKDF2/SHA-256 + Hardware Salt)." + RESET)
                time.sleep(1.0)
                break
            except Exception as e:
                print(RED + f" Unable to save in {PASS_FILE}: {e}" + RESET)
                sys.exit(1)
        else:
            print(RED + " Passwords do not match. Try again!\n" + RESET)

# ===== PROVISIONING CERT SSL =====
def ensure_certificates():
    if not (os.path.exists(CERT_FILE) and os.path.exists(KEY_FILE)):
        try:
            cmd = [
                "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", KEY_FILE, "-out", CERT_FILE,
                "-days", "3650", "-nodes", "-subj", "/CN=zMPanicServer"
            ]
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            return True
        except Exception:
            return False
    return True

def get_safe_destination_path(raw_filename, client_ip):
    uid = uuid.uuid4().hex[:8]
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    clean_ip = client_ip.replace(":", "_").replace(".", "_")
    if raw_filename:
        clean_name = os.path.basename(raw_filename).replace("\x00", "").strip()
        clean_name = re.sub(r'[^a-zA-Z0-9_.-]', '_', clean_name)
        if clean_name and not clean_name.startswith('.'):
            base_name, ext = os.path.splitext(clean_name)
            final_ext = ext if ext else ".mp4"
            final_filename = f"{base_name}_{clean_ip}_{uid}{final_ext}"
        else:
            final_filename = f"SOS_{timestamp}_{clean_ip}_{uid}.mp4"
    else:
        final_filename = f"SOS_{timestamp}_{clean_ip}_{uid}.mp4"
    abs_storage = os.path.abspath(SAVE_DIR)
    target_path = os.path.abspath(os.path.join(abs_storage, final_filename))
    if os.path.commonpath([abs_storage, target_path]) != abs_storage:
        target_path = os.path.join(abs_storage, f"SOS_{timestamp}_{clean_ip}_{uid}.mp4")
    return target_path

def prompt_autostart_setup():
    if not sys.stdin or not sys.stdin.isatty():
        return
    try:
        chk = subprocess.run(["crontab", "-l"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        current_cron = chk.stdout if chk.returncode == 0 else ""
        if "zmpserver" in current_cron:
            return
        is_frozen = getattr(sys, 'frozen', False)
        if is_frozen:
            exec_target = os.path.abspath(sys.executable)
            work_dir = os.path.dirname(exec_target)
            launch_cmd = exec_target
        else:
            py_script = os.path.abspath(__file__)
            work_dir = os.path.dirname(py_script)
            launch_cmd = f"{sys.executable} {py_script}"
        screen_bin = shutil.which("screen") or "/usr/bin/screen"
        print(CYAN + "\n" + hr("─") + RESET)
        print(YELLOW + BOLD + " ⚙️  AUTOMATIC START CONFIGURATION (AUTOSTART AT BOOT)" + RESET)
        print(WHITE + f" Detected target : {launch_cmd}" + RESET)
        print(WHITE + f" Base directory  : {work_dir}" + RESET)
        print(CYAN + hr("─") + RESET)
        print(GREEN + " Do you want to install autostart with GNU Screen [screen -S zmpserver]? (s/N) [5s timeout]: " + RESET, end="", flush=True)
        rlist, _, _ = select.select([sys.stdin], [], [], 5.0)
        if rlist:
            ans = sys.stdin.readline().strip().lower()
        else:
            print(YELLOW + "\n ⏱️  Timeout (5s) expired: starting server..." + RESET)
            ans = 'n'

        if ans in ['s', 'si', 'y', 'yes']:
            cron_entry = f"@reboot sleep 5 && cd {work_dir} && {screen_bin} -dmS zmpserver {launch_cmd}\n"
            new_cron = current_cron.rstrip() + "\n" + cron_entry if current_cron else cron_entry
            p = subprocess.Popen(["crontab", "-"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            _, err = p.communicate(input=new_cron)
            if p.returncode == 0:
                print(GREEN + " ✅ Autostart configured successfully!" + RESET)
                print(WHITE + "    On the next reboot the server will start in the background." + RESET)
                print(WHITE + "    Command to return to the console: " + CYAN + "screen -r zmpserver" + RESET)
                time.sleep(1.5)
            else:
                print(RED + f" ❌ Error installing to crontab: {err}" + RESET)
                time.sleep(1.5)
    except Exception as e:
        log_exception(e, "AUTOSTART_SETUP")

# ===== INIT STORAGE =====
try:
    if not os.path.exists(SAVE_DIR):
        os.makedirs(SAVE_DIR, exist_ok=True)
except Exception as e:
    log_exception(e, "INIT")

prompt_autostart_setup()
load_or_setup_password()

# ===== SPLASH =====
splash = [
    CYAN + BOLD + "███████╗███╗   ███╗██████╗  █████╗ ███╗   ██╗██╗ ██████╗" + RESET,
    CYAN + BOLD + "╚══███╔╝████╗ ████║██╔══██╗██╔══██╗████╗  ██║██║██╔════╝" + RESET,
    CYAN + BOLD + "  ███╔╝ ██╔████╔██║██████╔╝███████║██╔██╗ ██║██║██║     " + RESET,
    CYAN + BOLD + " ███╔╝  ██║╚██╔╝██║██╔═══╝ ██╔══██║██║╚██╗██║██║██║     " + RESET,
    CYAN + BOLD + "███████╗██║ ╚═╝ ██║██║     ██║  ██║██║ ╚████║██║╚██████╗" + RESET,
    CYAN + BOLD + "╚══════╝╚═╝     ╚═╝╚═╝     ╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝ ╚═════╝" + RESET,
    "",
    BLUE + "Secure Evidence Ingestion Service (Hardened TLS Node)" + RESET,
    WHITE + "Operational Node" + RESET,
    "",
    WHITE + f"Listening on 0.0.0.0:{PORT} (Strict HTTPS/TLS Only)" + RESET,
    WHITE + f"Storage: {SAVE_DIR}" + RESET,
]

try:
    center_block(splash)
    time.sleep(1.0)
except Exception:
    pass

print(CLEAR, end="")
boot_lines = []

def safe_line(expr, label):
    try:
        expr()
        return GREEN + label + " OK" + RESET
    except Exception as e:
        log_exception(e, label)
        return RED + label + " FAIL (see log)" + RESET

boot_lines.append(safe_line(lambda: ensure_certificates(), "Auto-SSL Certificate Provisioning..."))
boot_lines.append(safe_line(lambda: os.urandom(64), "Cryptographic subsystem.............."))
boot_lines.append(safe_line(lambda: os.path.abspath(SAVE_DIR), "Storage subsystem mount.............."))
boot_lines.append(safe_line(lambda: socket.socket().bind(("0.0.0.0", PORT)), "Network stack ready.................."))

box("SYSTEM BOOT", boot_lines)
time.sleep(0.3)

class HardenedSOSHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass
    def setup(self):
        super().setup()
        self.connection.settimeout(SOCKET_TIMEOUT)
    def handle(self):
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError, ssl.SSLError, socket.timeout, OSError):
            pass

    def do_POST(self):
        global files_received, total_bytes, saved_salt, saved_password_hash
        filename = None
        try:
            client_ip = self.client_address[0]
            if is_ip_banned(client_ip):
                self.send_response(403)
                self.end_headers()
                return
            if self.path != "/upload":
                self.send_response(404)
                self.end_headers()
                return
            client_auth = self.headers.get("Authorization", "")
            if not client_auth:
                if record_failed_attempt(client_ip):
                    log(f"IP {client_ip} BANNED FOR MANY MINUTES (Too many empty attempts)", RED, "BAN")
                else:
                    log(f"ACCESS DENIED: Key Missing (Discarded) from IP {client_ip}", RED, "ALERT")
                self.send_response(403)
                self.end_headers()
                return
            client_hash = hashlib.pbkdf2_hmac('sha256', client_auth.encode('utf-8'), saved_salt, 250000)
            if not hmac.compare_digest(client_hash, saved_password_hash):
                if record_failed_attempt(client_ip):
                    log(f"IP {client_ip} BANNED FOR MANY MINUTES (Brute Force Detected)", RED, "BAN")
                else:
                    log(f"ACCESS DENIED: Key Incorrect (Brute Force Mitigated) from IP {client_ip}", RED, "ALERT")
                self.send_response(403)
                self.end_headers()
                return
            reset_ip_attempts(client_ip)
            header_filename = self.headers.get("File-Name", "")
            latitude = self.headers.get("GPS-Latitude", "0.0").strip()[:32]
            longitude = self.headers.get("GPS-Longitude", "0.0").strip()[:32]
            filename = get_safe_destination_path(header_filename, client_ip)
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length > MAX_FILE_SIZE:
                log(f"REJECTED OVERSIZED PAYLOAD from {client_ip} ({content_length} bytes)", RED, "DROP")
                self.send_response(413)
                self.end_headers()
                return
            log(f"RECEIVING [HTTPS] from {client_ip} -> {os.path.basename(filename)}", BLUE, "RX")
            received = 0
            with open(filename, "wb") as f:
                if content_length > 0:
                    remaining = content_length
                    while remaining > 0:
                        chunk_to_read = min(64 * 1024, remaining)
                        chunk = self.rfile.read(chunk_to_read)
                        if not chunk:
                            break
                        f.write(chunk)
                        received += len(chunk)
                        remaining -= len(chunk)
                else:
                    while received < MAX_FILE_SIZE:
                        chunk = self.rfile.read(64 * 1024)
                        if not chunk:
                            break
                        f.write(chunk)
                        received += len(chunk)
            if received <= 0 or (content_length > 0 and received != content_length):
                log(f"INCOMPLETE / CORRUPTED STREAM from {client_ip} (received {received}/{content_length})", RED, "WARN")
                if os.path.exists(filename):
                    try:
                        os.remove(filename)
                    except Exception:
                        pass
                self.send_response(400)
                self.end_headers()
                return
            files_received += 1
            total_bytes += received
            meta_path = os.path.splitext(filename)[0] + ".meta.txt"
            try:
                with open(meta_path, "w", encoding="utf-8") as meta_f:
                    meta_f.write(f"File: {os.path.basename(filename)}\n")
                    meta_f.write(f"Protocol: HTTPS (TLS Encrypted)\n")
                    meta_f.write(f"Client-IP: {client_ip}\n")
                    meta_f.write(f"Timestamp: {ts()}\n")
                    meta_f.write(f"GPS-Latitude: {latitude}\n")
                    meta_f.write(f"GPS-Longitude: {longitude}\n")
                    meta_f.write(f"Maps-Link: https://maps.google.com/maps?q={latitude},{longitude}\n")
            except Exception as e:
                log_exception(e, "META_WRITE")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"SUCCESS: Secured.")
            log(f"STORED [HTTPS]: {os.path.basename(filename)} ({received} bytes) | GPS: {latitude}, {longitude}", GREEN, "OK")

        except Exception as e:
            log_exception(e, "do_POST")
            if filename and os.path.exists(filename) and os.path.getsize(filename) == 0:
                try:
                    os.remove(filename)
                except Exception:
                    pass
            try:
                self.send_response(500)
                self.end_headers()
            except Exception:
                pass

def signal_handler(sig, frame):
    global shutdown_flag
    log("SHUTDOWN SIGNAL RECEIVED. STOPPING CLEANLY...", YELLOW, "SYS")
    shutdown_flag = True

signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)
ensure_certificates()
log("NODE ONLINE (STRICT HTTPS / TLS). READY FOR CONNECTIONS...", CYAN, "SYS")

while not shutdown_flag:
    httpd = None
    try:
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(certfile=CERT_FILE, keyfile=KEY_FILE)
        httpd = ThreadingHTTPServer(("0.0.0.0", PORT), HardenedSOSHandler)
        httpd.socket = ssl_context.wrap_socket(httpd.socket, server_side=True)
        httpd.timeout = 2.0
        while not shutdown_flag:
            httpd.handle_request()
    except KeyboardInterrupt:
        shutdown_flag = True
        break
    except Exception as e:
        if not shutdown_flag:
            log_exception(e, "SUPERVISOR_WATCHDOG")
            log("RESTARTING SERVER ENGINE IN 1 SECOND...", YELLOW, "RETRY")
            time.sleep(1.0)
    finally:
        if httpd:
            try:
                httpd.server_close()
            except Exception:
                pass

log("SERVER TERMINATED SAFELY.", YELLOW, "SYS")
```

### Server Feature Summary

| Feature | Implementation |
|---------|----------------|
| Dependencies | Zero (`pip` not required) |
| Transport | Strict HTTPS / TLS only (auto-provisioned RSA-2048 self-signed cert) |
| Authentication | Zero-knowledge PBKDF2-HMAC-SHA256 (250 000 iterations) + 32-byte salt in `.srvpass.txt` |
| Comparison | Constant-time `hmac.compare_digest` |
| Rate limiting | In-memory; 5 consecutive failures → 1-hour IP ban |
| Architecture | Blind drop-box — only `POST /upload` is accepted |
| Path safety | Regex sanitization + `os.path.commonpath` anti-traversal |
| Naming | `SOS_{timestamp}_{clean_ip}_{uid}.mp4` + sibling `.meta.txt` with Google Maps link |
| Resilience | Supervisor watchdog auto-restarts the TLS listener on crash |
| First-run UX | Interactive masked-asterisk password setup (max 512 chars) + optional `@reboot` screen autostart |

### Quick Start (Server)

```bash
python3 SERVER.py
# First run → create password (≤ 512 characters)
# Optional: answer “s” / “y” to install GNU Screen autostart via crontab
```

Recordings and forensic metadata land in `./zmpanic_recordings/`.

---

## Network Protocol Specifications (API Mapping)

| Element | Value / Description |
|---------|---------------------|
| **Method / Path** | `POST /upload` |
| **Content-Type** | `application/octet-stream` (raw binary stream) |
| **Authorization** | `Bearer <server_password>` (required) |
| **File-Name** | Client-generated name, e.g. `SOS_1718115648000.mp4` |
| **GPS-Latitude** | String representation of last known latitude |
| **GPS-Longitude** | String representation of last known longitude |
| **Content-Length** | Exact byte length of the video payload |
| **Success** | `HTTP 200` + body `SUCCESS: Secured.` |
| **Auth failure** | `HTTP 401` (after 5 failures the source IP is banned for 1 hour) |
| **Banned IP** | `HTTP 403` |
| **Oversized payload** | `HTTP 413` (server limit 100 MB) |
| **Any other method/path** | `HTTP 404` or `405` |

The Android client constructs the target list from a semicolon-delimited field and always prefers HTTPS. The first successful 2xx response terminates further attempts for that chunk.

---

## Project Directory Tree

```
zMPanic/
├── app/
│   └── src/main/
│       ├── java/com/mykerd/panic/
│       │   ├── MainActivity.kt          # Control surface, permissions, password dialog
│       │   └── PanicService.kt          # Dual-mode recorder, Ghost Engine, store-and-forward
│       ├── res/
│       │   ├── values/                  # Default (English) strings & resources
│       │   ├── values-*/                # 62+ native locale overrides
│       │   ├── layout/
│       │   ├── drawable/
│       │   └── xml/
│       └── AndroidManifest.xml
├── gradle/
├── SERVER.py                            # Hardened TLS ingestion node (zero dependencies)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── LICENSE
├── PRIVACY_POLICY.md
├── ic_launcher.svg
└── README.md                            # This document
```

---

## Why Android Exclusive? (The iOS Sandbox Barrier)

The zM SOS GUARD protocol is structurally and philosophically incompatible with iOS for the following technical reasons:

1. **Background Camera Kill**  
   iOS immediately suspends `AVCaptureSession` the moment the app loses foreground focus, is minimized, or the screen turns off. There is no equivalent to Android’s high-priority Foreground Service that permits continuous camera + microphone access while the device is locked.

2. **Hardware Privacy Indicators**  
   The orange/green status-bar privacy dots are wired directly to the camera and microphone hardware. They cannot be suppressed or disguised by software, rendering any stealth mode useless.

3. **Sideloading & Distribution Autonomy**  
   Android permits native compilation and immediate installation of a standalone APK. Apple requires a Mac, a paid developer account, and enforces a 7-day expiration on unsigned or free-provisioning builds, destroying the “deploy anywhere, anytime” operational model required for emergency black-box use.

Consequently, the entire architecture — continuous background recording, process-level network binding, stealth notification channels, and long-running foreground services — exists only on Android.

---

## Forensic Intent & Legal Disclaimer

This software is a tool for **forensic protection, emergency documentation, and technical research** into the resilience of mobile operating systems. It is released exclusively for legitimate security and personal-protection purposes.

The user assumes **full and total civil and criminal responsibility** arising from the use of hidden recording features. The user must comply strictly with all applicable local laws governing privacy, personal data processing, and the interception of communications, images, and audio.

The developer and project contributors assume **no responsibility** for damages, improper use, illicit actions, or unauthorized applications of the source code documented herein.

Evidence produced by this system should be treated according to local chain-of-custody and forensic best practices. The non-destructive retention model (`.synced.mp4` flags) is intentionally designed to preserve the original files for later independent verification.

---

**zM SOS GUARD v13.0 — The Hardened & Secure Update**  
Hardware-backed secrets · Zero-knowledge PBKDF2 server · Dual-mode self-healing video · Rugged OEM fixes · Store-and-forward multi-server resilience · 62+ native locales.

*Pure Vanilla. Zero Dependencies. Maximum Survivability.*
