import http.server
import socketserver
import os
from datetime import datetime

PORT = 3000

UPLOAD_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "zMPanic_Uploads")
if not os.path.exists(UPLOAD_DIR): os.makedirs(UPLOAD_DIR)

class ForceHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        print(f"\n[{datetime.now().strftime('%H:%M:%S')}] Connection from {self.client_address}")
        try:
            content_length = int(self.headers.get('Content-Length', 0))
            data = self.rfile.read(content_length)

            filename = f"panic_{datetime.now().strftime('%H%M%S')}.mp4"
            with open(os.path.join(UPLOAD_DIR, filename), 'wb') as f:
                f.write(data)

            print(f"✅ RECEIVED: {filename} ({len(data)} bytes)")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
        except Exception as e:
            print(f"❌ Error: {e}")

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"SERVER ACTIVE ON LOCAL NETWORK")

socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", PORT), ForceHandler) as httpd:
    print(f"=== SERVER LISTENING ON PORT {PORT} ===")
    print(f"Target IP for device: 192.168.1.220:{PORT}")
    httpd.serve_forever()
