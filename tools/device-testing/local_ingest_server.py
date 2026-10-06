import http.server, ssl, gzip, json, io, sys, datetime
LOG = sys.argv[1]
CONFIG_FILE = sys.argv[4] if len(sys.argv) > 4 else '/tmp/apm_cfg.json'
received = []

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_POST(self):
        # OkHttp streams the gzip body without a Content-Length, so the request arrives with
        # chunked transfer encoding. Reading Content-Length bytes yields nothing.
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                size = int(line.split(b';')[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            raw = b''.join(chunks)
        else:
            raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        enc = self.headers.get('Content-Encoding', '')
        body = raw
        if enc == 'gzip':
            try: body = gzip.decompress(raw)
            except Exception as e: body = b'<gunzip failed: %s>' % str(e).encode()
        rec = {
            "transfer_encoding": self.headers.get("Transfer-Encoding"),
            "at": datetime.datetime.now().isoformat(timespec='seconds'),
            "path": self.path,
            "content_encoding": enc,
            "x_apm_key": self.headers.get('X-APM-Key'),
            "x_apm_sdk": self.headers.get('X-APM-Sdk'),
            "raw_bytes": len(raw),
            "unzipped_bytes": len(body),
        }
        try:
            env = json.loads(body)
            rec["schema_version"] = env.get("schema_version")
            rec["sdk"] = env.get("sdk", {}).get("name")
            rec["health"] = env.get("sdk", {}).get("health")
            rec["events"] = len(env.get("events", []))
            rec["event_types"] = sorted({e.get("type") for e in env.get("events", [])})
            rec["integrity"] = env.get("integrity")
            rec["user_id_source"] = env.get("user_id_source")
        except Exception as e:
            rec["parse_error"] = str(e)
        received.append(rec)
        open(LOG, 'w').write(json.dumps(received, indent=2))
        self.send_response(202)
        self.send_header('Content-Length', '0')
        self.end_headers()
    def do_GET(self):
        # GET /v1/config — remote config (01 section 9). Body comes from a file the test rewrites,
        # so the kill switch can be flipped without restarting the server.
        import os
        if self.path.startswith('/v1/config'):
            cfg = open(CONFIG_FILE).read().encode() if os.path.exists(CONFIG_FILE) else b'{"enabled":true}'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(cfg)))
            self.end_headers()
            self.wfile.write(cfg)
        else:
            self.send_response(404); self.send_header('Content-Length','0'); self.end_headers()

    def log_message(self, *a): pass

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(sys.argv[2], sys.argv[3])
srv = http.server.HTTPServer(('0.0.0.0', 8443), H)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
