"""Exercise the deployed HTTP server, including multipart and proxy boundaries."""
import base64
import http.client
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from werkzeug.security import generate_password_hash


@unittest.skipUnless(importlib.util.find_spec("gunicorn"), "Gunicorn is not installed")
class GunicornTests(unittest.TestCase):
    def test_authenticated_requests_multipart_proxy_boundary_and_shutdown(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="eapdf-gunicorn-") as temporary:
            directory = Path(temporary)
            credentials = directory / "users.json"
            credentials.write_text(json.dumps({"test": generate_password_hash("local-test")}))
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                port = probe.getsockname()[1]
            env = dict(os.environ, VALIDATOR_REQUIRE_AUTH="1", VALIDATOR_AUTH_FILE=str(credentials),
                       VALIDATOR_ALLOWED_HOSTS="127.0.0.1", VALIDATOR_TRUSTED_PROXIES="")
            authorization = "Basic " + base64.b64encode(b"test:local-test").decode()
            # Use the real application with private request storage for this server.
            (directory / "test_wsgi.py").write_text(
                "from pathlib import Path\nimport validator_web as web\n"
                "base = Path(__file__).parent\n"
                "web.WEB_TMP_ROOT = base / 'uploads'\n"
                "web.GENERATOR_TMP_ROOT = base / 'generated'\n"
                "web.OUT_PDF_ROOT = base / 'out'\napp = web.app\n")

            def request(method="GET", body=None, headers=None):
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                try:
                    connection.request(method, "/", body=body, headers=headers or {})
                    response = connection.getresponse()
                    return response.status, response.read(), dict(response.getheaders())
                finally:
                    connection.close()

            with (directory / "server.log").open("w+") as log:
                server = subprocess.Popen([sys.executable, "-m", "gunicorn", "--bind", f"127.0.0.1:{port}",
                                           "--workers", "1", "--threads", "2", "--pythonpath", str(directory), "test_wsgi:app"],
                                          cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 20
                    while True:
                        try:
                            status, _, _ = request()
                            break
                        except OSError:
                            if server.poll() is not None or time.monotonic() > deadline:
                                log.seek(0)
                                self.fail("Gunicorn did not start: " + log.read())
                            time.sleep(0.05)
                    self.assertEqual(401, status)
                    status, body, headers = request(headers={"Authorization": authorization})
                    self.assertEqual(200, status)
                    self.assertIn(b"<!doctype html", body.lower())
                    self.assertIn("Content-Security-Policy", headers)
                    status, _, _ = request(headers={"Authorization": authorization, "Host": "untrusted.example",
                                                   "X-Forwarded-Host": "127.0.0.1", "X-Forwarded-Proto": "https"})
                    self.assertEqual(400, status)
                    multipart = b'--boundary\r\nContent-Disposition: form-data; name="note"\r\n\r\nempty upload\r\n--boundary--\r\n'
                    status, _, _ = request("POST", multipart, {"Authorization": authorization,
                        "Content-Type": "multipart/form-data; boundary=boundary"})
                    self.assertEqual(400, status)
                finally:
                    server.terminate()
                    try:
                        server.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        server.kill()
                        server.wait(timeout=5)
                self.assertEqual(0, server.returncode, "Gunicorn must shut down gracefully")
