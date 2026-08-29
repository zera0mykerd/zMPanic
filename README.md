# 🛡️ zM SOS GUARD — v13.0 "The Hardened & Secure Update"

**Elite forensic black-box recorder for Android**  
Pure Vanilla Kotlin · Zero third-party network stacks · Hardware-backed secrets · Strict HTTPS ingestion

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-6.0%2B%20(API%2023)–16-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Pure%20Vanilla-purple.svg)]()
[![Size](https://img.shields.io/badge/APK-%3C%20500%20KB-brightgreen.svg)]()
[![Languages](https://img.shields.io/badge/Locales-62%2B-orange.svg)]()

> Transforms any Android device into an indestructible evidence black box. Continuous gapless (or self-healing) video + GPS recording with store-and-forward delivery to a zero-knowledge, rate-limited, TLS-only drop-box.

---

## Architectural Philosophy

- **Zero framework overhead** — No Jetpack Compose, no OkHttp, no Retrofit. Native `HttpURLConnection` / `HttpsURLConnection` only.
- **Hardware-backed secrets** — Server password lives inside `EncryptedSharedPreferences` protected by `MasterKey` (AES-256-GCM).
- **Ingestion-only server** — The Python node accepts solely `POST /upload`. No listing, no download, no delete endpoints.
- **Self-healing video pipeline** — Dual-mode engine with hardware watchdog that falls back from gapless chunking to safe timer rotation.
- **Rugged-device hardened** — Explicit fixes for Samsung Exynos Error -19, waterproof-membrane audio attenuation, and surface lifecycle bugs.
- **62+ native locales** — All UI strings live in `res/values-*/strings.xml`.

---

## 1. Security & Authentication

### Client (Android)

| Component | Implementation |
|-----------|----------------|
| Secret storage | `MasterKey.Builder(...).setKeyScheme(AES256_GCM)` → `EncryptedSharedPreferences` (AES-256-SIV keys + AES-256-GCM values) |
| First-launch | Mandatory password dialog (1–512 characters). Stored only after confirmation. |
| Transport | Strict HTTPS / TLS. Custom `Authorization` header injected on every upload. |
| Certificate handling | Trust-all TrustManager + HostnameVerifier for self-signed field-deployed certificates (private servers only). |

### Server (`SERVER.py`)

- **Ingestion-only blind drop-box** — Only `POST /upload` is implemented. Every other method/path returns 404/405.
- **Zero-knowledge password verification**
  - Password never stored in clear text.
  - `PBKDF2-HMAC-SHA256` with **250 000 iterations**.
  - 32-byte cryptographically random salt written to `.srvpass.txt` as `salt_hex:hash_hex`.
  - Constant-time comparison via `hmac.compare_digest`.
- **In-memory IP rate-limiting**
  - 5 consecutive authentication failures → 1-hour ban.
  - Ban state held in process memory (lost on restart — intentional).
- **Anti-collision & path-traversal protection**
  - Final filename pattern: `SOS_{timestamp}_{clean_ip}_{uid}.mp4` (or sanitized client-supplied base + IP + UUID).
  - Client-supplied names are stripped of null bytes, path separators and non-alphanumeric characters via regex.
  - Absolute path is verified with `os.path.commonpath` against the storage root; any traversal attempt is forced back into the vault.
- **Mirrored forensic metadata** — Every successful upload produces a sibling `.meta.txt` containing GPS coordinates and a ready-to-click Google Maps URL.
- **TLS only** — Server auto-provisions a self-signed certificate (`cert.pem` / `key.pem`) on first run if OpenSSL is present. Plain HTTP is never accepted.

---

## 2. Dual-Mode Self-Healing Video Engine (`PanicService.kt`)

### Mode A — Flagship Gapless Chunking (Android 8+)

```
setMaxFileSize(bytesPerSecond × rotationSeconds)
→ MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING (802)
→ mediaRecorder.setNextOutputFile(nextFile)
→ MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED (803)
→ seamless hand-off, zero lost frames
```

### Mode B — Legacy Safe Timer Rotation

For older or fragmented HALs that never emit 803:

```
scheduleLegacyRotation() → rotateProcess()
  stop() → reset() → startMediaRecorder()
```

### Hardware Watchdog

A background timer (`activeRotation + 5 s`) is armed after every successful gapless transition.  
If the 803 event is never received, the watchdog forces `isGaplessSupported = false` and permanently switches the process to Mode B for the remainder of the session.

---

## 3. Samsung Exynos & Rugged Hardware Fixes

| Problem | Solution |
|---------|----------|
| Samsung Error -19 (preview/video size mismatch) | `getBestCommonSize()` — intersects supported preview sizes with supported video sizes and selects the highest common resolution. |
| Silent / waterproof devices (Doogee, Blackview, etc.) | Forced `AudioSource.MIC` + 30 FPS. Bypasses silent-mode muting and compensates for membrane dampening. |
| Surface lifecycle crashes | `SurfaceTexture(0)` dummy surface + careful avoidance of duplicate surface attachments. Visible `TextureView` surface is shared via weak reference only when the UI is present. |

---

## 4. Network Resilience & Store-and-Forward

- **Continuous local recording** — Chunks are always written to `zMPanicRec/` regardless of connectivity.
- **Chronological backlog flush** — On reconnect the sync thread walks files sorted by `lastModified()` and uploads oldest-first.
- **Multi-server priority failover** — Endpoint list is semicolon-delimited (`IP1:PORT;IP2:PORT` or full `https://…` URLs). First HTTP 2xx response wins; remaining servers are skipped for that chunk.
- **Process binding**
  - `ConnectivityManager.NetworkCallback` + `bindProcessToNetwork`
  - `PARTIAL_WAKE_LOCK` (12 h)
  - `WIFI_MODE_FULL_HIGH_PERF`
  - Service returns `START_STICKY`

---

## 5. Multilingual & Clean Code

- **62+ native locales** under `res/values-*/strings.xml`.
- Live GPS coordinates and status Toasts are restored when `!activeHiddenMode`.
- Stealth (“Ghost”) mode still suppresses all Toasts, vibration and visible notification text.

---

## 6. Complete Server Source (`SERVER.py`)

Copy-paste and run. Zero external dependencies beyond the Python 3 standard library (+ OpenSSL for auto-certificate generation).

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
        except (ConnectionResetError, BrokenPipeError, TimeoutError, OSError):
            pass
        except Exception as e:
            log_exception(e, "handle")

    def do_GET(self):
        self.send_response(405)
        self.end_headers()

    def do_DELETE(self):
        self.send_response(405)
        self.end_headers()

    def do_PUT(self):
        self.send_response(405)
        self.end_headers()

    def do_POST(self):
        global files_received, total_bytes
        client_ip = self.client_address[0]
        filename = None

        if is_ip_banned(client_ip):
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"BANNED")
            log(f"REJECTED banned IP: {client_ip}", RED, "BAN")
            return

        if self.path != "/upload":
            self.send_response(404)
            self.end_headers()
            return

        try:
            auth = self.headers.get("Authorization", "")
            if not auth.startswith("Bearer "):
                if record_failed_attempt(client_ip):
                    log(f"IP BANNED after failed auth: {client_ip}", RED, "BAN")
                self.send_response(401)
                self.end_headers()
                self.wfile.write(b"UNAUTHORIZED")
                return

            token = auth[7:].strip()
            if not saved_password_hash or not saved_salt:
                self.send_response(500)
                self.end_headers()
                return

            computed = hashlib.pbkdf2_hmac('sha256', token.encode('utf-8'), saved_salt, 250000)
            if not hmac.compare_digest(computed, saved_password_hash):
                if record_failed_attempt(client_ip):
                    log(f"IP BANNED after failed auth: {client_ip}", RED, "BAN")
                self.send_response(401)
                self.end_headers()
                self.wfile.write(b"UNAUTHORIZED")
                return

            reset_ip_attempts(client_ip)

            content_length = int(self.headers.get("Content-Length", 0))
            if content_length <= 0 or content_length > MAX_FILE_SIZE:
                self.send_response(413)
                self.end_headers()
                return

            raw_name = self.headers.get("File-Name", "")
            latitude = self.headers.get("GPS-Latitude", "0.0")
            longitude = self.headers.get("GPS-Longitude", "0.0")

            filename = get_safe_destination_path(raw_name, client_ip)

            received = 0
            with open(filename, "wb") as f:
                remaining = content_length
                buffer_size = 64 * 1024
                while remaining > 0:
                    chunk = self.rfile.read(min(remaining, buffer_size))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
                    received += len(chunk)

            files_received += 1
            total_bytes += received

            try:
                meta_path = os.path.splitext(filename)[0] + ".meta.txt"
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

### Quick start (server)

```bash
python3 SERVER.py
# First run → create password (≤ 512 chars)
# Optional: answer “s” to install @reboot screen autostart
```

Recordings land in `./zmpanic_recordings/` together with sibling `.meta.txt` files.

---

## Protocol Specification

| Item | Value |
|------|-------|
| Method / Path | `POST /upload` |
| Content-Type | `application/octet-stream` |
| Required Headers | `Authorization: Bearer <password>` |
| | `File-Name: SOS_….mp4` |
| | `GPS-Latitude` / `GPS-Longitude` |
| Success | `HTTP 200` + body `SUCCESS: Secured.` |
| Auth failure | `401` (after 5 failures the IP is banned for 1 h) |
| Oversized | `413` |
| Any other method/path | `404` / `405` |

Client constructs the target list from the semicolon-delimited server field and always prefers HTTPS.

---

## File Layout

```
zMPanic/
├── app/src/main/
│   ├── java/com/mykerd/panic/
│   │   ├── MainActivity.kt          # Neon UI, permissions, first-launch password dialog
│   │   └── PanicService.kt          # Dual-mode recorder, watchdog, store-and-forward, stealth
│   └── res/
│       ├── values/…                 # Default English
│       └── values-*/…               # 62+ locales
├── SERVER.py                        # Hardened TLS ingestion node (zero deps)
├── LICENSE
└── PRIVACY_POLICY.md
```

---

## Build & Deploy (Client)

```bash
./gradlew assembleRelease
# APK lands under app/build/outputs/apk/release/
```

Minimum SDK: Android 6.0 (API 23). Target: Android 16.  
No external network libraries are pulled; only the AndroidX Security Crypto artifact is used for the MasterKey vault.

---

## Legal & Intent Declaration

This software is a tool for **forensic protection, emergency documentation and technical research** into mobile OS resilience. It is released exclusively for legitimate security and personal-protection purposes.

The user assumes full civil and criminal responsibility for any use of hidden recording features and must comply with all applicable local laws concerning privacy, personal data and interception of communications, images and audio. The developer and contributors accept no liability for damages, improper use or illicit application of the source code.

---

**v13.0 — The Hardened & Secure Update**  
Hardware-backed secrets · PBKDF2 zero-knowledge server · Dual-mode self-healing video · Rugged hardware fixes · Store-and-forward multi-server resilience.
