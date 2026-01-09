import http.server
import os
import time

PORT = 9999
SAVE_DIR = "zmpanic_recordings"

if not os.path.exists(SAVE_DIR): os.makedirs(SAVE_DIR)

class SOSHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            content_length = int(self.headers['Content-Length'])
            timestamp = time.strftime("%Y%m%d_%H%M%S")
            filename = os.path.join(SAVE_DIR, f"SOS_{timestamp}.mp4")
            raw_data = self.rfile.read(content_length)
            with open(filename, 'wb') as f: f.write(raw_data)
            self.send_response(200)
            self.end_headers()
            print(f"✅ RECEIVED: {filename} ({content_length} bytes)")
        except Exception as e:
            print(f"❌ ERROR: {e}")
            self.send_response(500)
            self.end_headers()

print(f"🚀 SOS SERVER ONLINE on port {PORT}")
http.server.HTTPServer(('0.0.0.0', PORT), SOSHandler).serve_forever()