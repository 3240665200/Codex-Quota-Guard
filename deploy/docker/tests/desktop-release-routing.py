"""Real nginx regression: collection requests must preserve method, URI and body.

Run on a Linux Docker host using a locally available web image (never pulls).
"""
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import uuid


class Echo(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        result = json.dumps({"method": self.command, "path": self.path, "body": body.decode()}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(result)))
        self.end_headers()
        self.wfile.write(result)

    do_POST = do_GET

    def log_message(self, *_):
        pass


@unittest.skipUnless(os.name == "posix", "requires a Linux Docker host network")
class DesktopReleaseRouting(unittest.TestCase):
    def test_collection_and_upload_routes_preserve_requests(self):
        config_path = Path(os.environ.get("NGINX_TEST_CONFIG", Path(__file__).resolve().parent.parent / "nginx.conf"))
        image = os.environ.get("NGINX_TEST_IMAGE", "minjm/codex-quota-guard-web:0.4.12")
        fixture = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Echo)
        worker = threading.Thread(target=fixture.serve_forever, daemon=True)
        worker.start()
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        name = "cqg-route-test-" + uuid.uuid4().hex[:12]
        try:
            with tempfile.TemporaryDirectory(prefix="cqg-nginx-route-") as directory:
                config = config_path.read_text()
                self.assertIn("server backend:8080 resolve;", config)
                config = config.replace("server backend:8080 resolve;", f"server 127.0.0.1:{fixture.server_port};")
                config = config.replace("listen 8080;", f"listen 127.0.0.1:{port};")
                staged = Path(directory) / "nginx.conf"
                staged.write_text(config)
                staged.chmod(0o644)
                subprocess.run(["docker", "run", "--detach", "--pull=never", "--name", name,
                                "--network", "host", "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                                "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                                "--mount", f"type=bind,src={staged},dst=/etc/nginx/nginx.conf,readonly",
                                "--entrypoint", "nginx", image, "-g", "daemon off;"],
                               check=True, capture_output=True, timeout=15)
                deadline = time.monotonic() + 10
                while True:
                    try:
                        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                        connection.request("GET", "/healthz")
                        response = connection.getresponse()
                        response.read()
                        connection.close()
                        if response.status == 200:
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise
                        time.sleep(0.1)
                base = "/api/v1/admin/desktop-releases"
                for method, path in [("GET", base), ("POST", base), ("GET", base + "/signing-key"),
                                     ("POST", base + "/00000000-0000-0000-0000-000000000000/artifacts")]:
                    with self.subTest(method=method, path=path):
                        body = '{"version":"0.4.12","notes":"proxy regression"}' if method == "POST" else ""
                        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                        connection.request(method, path, body, {"Content-Type": "application/json"})
                        response = connection.getresponse()
                        received = response.read()
                        connection.close()
                        self.assertEqual(response.status, 200, "must reach upstream without redirecting")
                        self.assertIsNone(response.getheader("Location"))
                        self.assertEqual(json.loads(received), {"method": method, "path": path, "body": body})
        finally:
            subprocess.run(["docker", "rm", "--force", name], capture_output=True, timeout=15)
            fixture.shutdown()
            fixture.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
