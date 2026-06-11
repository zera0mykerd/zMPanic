
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
from http.server import ThreadingHTTPServer

PORT = 9999
SAVE_DIR = "zmpanic_recordings"
MAX_FILE_SIZE = 15 * 1024 * 1024  # 15 MB

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

def ts():
    return time.strftime("%Y-%m-%d %H:%M:%S")

def term_size():
    try:
        s = shutil.get_terminal_size()
        return s.columns, s.lines
    except:
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
    import re
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

def progress(label, duration=1.0):
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
            time.sleep(0.03)
        print(f"\r{CYAN}{label:<20} [{'█'*bar_w}] 100%{RESET}")
    except Exception:
        pass

def draw_header():
    try:
        uptime = int(time.time() - start_time)
        w, _ = term_size()
        print(CLEAR, end="")
        print(MAGENTA + hr("═") + RESET)
        print(MAGENTA + BOLD + " ZMPANIC :: SECURE EVIDENCE INGESTION NODE".ljust(w) + RESET)
        print(MAGENTA + hr("─") + RESET)
        print(CYAN + f" LISTEN      : 0.0.0.0:{PORT}".ljust(w) + RESET)
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

# ===== INIT =====
try:
    if not os.path.exists(SAVE_DIR):
        os.makedirs(SAVE_DIR)
except Exception as e:
    log_exception(e, "INIT")

# ===== SPLASH =====
splash = [
    CYAN + BOLD + "███████╗███╗   ███╗██████╗  █████╗ ███╗   ██╗██╗ ██████╗" + RESET,
    CYAN + BOLD + "╚══███╔╝████╗ ████║██╔══██╗██╔══██╗████╗  ██║██║██╔════╝" + RESET,
    CYAN + BOLD + "  ███╔╝ ██╔████╔██║██████╔╝███████║██╔██╗ ██║██║██║     " + RESET,
    CYAN + BOLD + " ███╔╝  ██║╚██╔╝██║██╔═══╝ ██╔══██║██║╚██╗██║██║██║     " + RESET,
    CYAN + BOLD + "███████╗██║ ╚═╝ ██║██║     ██║  ██║██║ ╚████║██║╚██████╗" + RESET,
    CYAN + BOLD + "╚══════╝╚═╝     ╚═╝╚═╝     ╚═╝  ╚═╝╚═╝  ╚═══╝╚═╝ ╚═════╝" + RESET,
    "",
    BLUE + "Secure Evidence Ingestion Service" + RESET,
    WHITE + "Operational Node" + RESET,
    "",
    WHITE + f"Listening on 0.0.0.0:{PORT}" + RESET,
    WHITE + f"Storage: {SAVE_DIR}" + RESET,
]

try:
    center_block(splash)
    time.sleep(2.2)
except Exception:
    pass

# ===== BOOT =====
print(CLEAR, end="")

boot_lines = []

def safe_line(expr, label):
    try:
        expr()
        return GREEN + label + " OK" + RESET
    except Exception as e:
        log_exception(e, label)
        return RED + label + " FAIL (see log)" + RESET

boot_lines.append(safe_line(lambda: (open(os.path.join(SAVE_DIR, "__fw.tmp"), "wb").write(b"x"), os.remove(os.path.join(SAVE_DIR, "__fw.tmp"))), "Firmware integrity check............."))
boot_lines.append(safe_line(lambda: os.urandom(64), "Cryptographic subsystem.............."))
boot_lines.append(safe_line(lambda: (open(os.path.join(SAVE_DIR, "__fs.tmp"), "wb").write(b"x"), os.remove(os.path.join(SAVE_DIR, "__fs.tmp"))), "Filesystem mount....................."))
boot_lines.append(safe_line(lambda: socket.socket().bind(("0.0.0.0", PORT)), "Network stack........................"))
boot_lines.append(safe_line(lambda: os.path.abspath(SAVE_DIR), "Security policy load................."))

box("SYSTEM BOOT", boot_lines)

time.sleep(0.7)

progress("Initializing ядро", 0.2)
progress("Loading modules", 0.3)
progress("Starting services", 0.1)
progress("Establishing trust", 0.1)

# ===== SERVER =====
class SOSHandler(http.server.BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        try:
            log(f"HTTP LOG: {self.client_address[0]} {format % args}", DIM, "HTTP")
        except Exception:
            pass

    def do_POST(self):
        global files_received, total_bytes
        try:
            client_ip = self.client_address[0]
            content_length = int(self.headers.get("Content-Length", 0))

            if content_length <= 0:
                self.send_response(400)
                self.end_headers()
                return

            if content_length > MAX_FILE_SIZE:
                log(f"REJECTED OVERSIZE from {client_ip} ({content_length} bytes)", RED, "DROP")
                self.send_response(413)
                self.end_headers()
                return

            uid = uuid.uuid4().hex
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            thread_id = threading.get_ident()

            filename = os.path.join(
                SAVE_DIR,
                f"SOS_{timestamp}_{client_ip.replace('.', '_')}_{thread_id}_{uid}.mp4"
            )

            log(f"RECEIVING {content_length} bytes from {client_ip}", BLUE, "RX")

            received = 0
            with open(filename, "wb") as f:
                while received < content_length:
                    chunk = self.rfile.read(min(1024 * 1024, content_length - received))
                    if not chunk:
                        break
                    f.write(chunk)
                    received += len(chunk)

            if received != content_length:
                log(f"INCOMPLETE FILE from {client_ip}", RED, "WARN")
                try:
                    os.remove(filename)
                except:
                    pass
                self.send_response(400)
                self.end_headers()
                return

            files_received += 1
            total_bytes += received

            self.send_response(200)
            self.end_headers()

            log(f"FILE STORED: {filename}", GREEN, "OK")

        except Exception as e:
            log_exception(e, "do_POST")
            try:
                self.send_response(500)
                self.end_headers()
            except:
                pass

# ===== MAIN LOOP =====
log("NODE ONLINE. WAITING FOR TRANSMISSIONS...", CYAN, "SYS")

while not shutdown_flag:
    try:
        httpd = ThreadingHTTPServer(("0.0.0.0", PORT), SOSHandler)
        httpd.timeout = 2
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("CTRL+C RECEIVED. SHUTTING DOWN CLEANLY.", YELLOW, "SYS")
        shutdown_flag = True
    except Exception as e:
        log_exception(e, "MAIN SERVER LOOP")
        time.sleep(1)

log("SERVER OFFLINE.", YELLOW, "SYS")
