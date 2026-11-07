import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from werkzeug.security import generate_password_hash
import validator_web as web


class AuthenticationTests(unittest.TestCase):
    def test_authentication_fails_closed_and_covers_download_routes(self):
        with tempfile.TemporaryDirectory() as temporary:
            users = Path(temporary) / "users.json"
            environment = {"VALIDATOR_REQUIRE_AUTH": "1", "VALIDATOR_AUTH_FILE": str(users)}
            client = web.app.test_client()
            with patch.dict(os.environ, environment), patch.object(web, "cleanup_expired_jobs") as cleanup:
                self.assertEqual(503, client.get("/").status_code)
                users.write_text(json.dumps({"archivist": generate_password_hash("correct password")}))
                for path in ("/", "/download/unknown", "/validation/report/unknown"):
                    response = client.get(path)
                    self.assertEqual(401, response.status_code)
                    self.assertIn("Basic", response.headers["WWW-Authenticate"])
                headers = {"Authorization": "Basic " + base64.b64encode(b"archivist:wrong").decode()}
                self.assertEqual(401, client.get("/", headers=headers).status_code)
                cleanup.assert_not_called()
                headers = {"Authorization": "Basic " + base64.b64encode(b"archivist:correct password").decode()}
                self.assertEqual(200, client.get("/", headers=headers).status_code)
                self.assertEqual(403, client.post("/generate/local", headers=dict(headers, Origin="https://attacker.example")).status_code)

    def test_host_allowlist_rejects_untrusted_hosts(self):
        with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "localhost"}):
            self.assertEqual(400, web.app.test_client().get("/", base_url="https://attacker.example").status_code)

    def test_forwarded_headers_from_an_untrusted_peer_do_not_bypass_host_checks(self):
        with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "localhost", "VALIDATOR_TRUSTED_PROXIES": ""}), \
                patch.object(web, "cleanup_expired_jobs"):
            response = web.app.test_client().get("/", base_url="http://attacker.example",
                headers={"X-Forwarded-Host": "localhost", "X-Forwarded-Proto": "https"})
            self.assertEqual(400, response.status_code)

    def test_forwarded_headers_are_accepted_only_from_the_configured_proxy(self):
        with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "public.example", "VALIDATOR_TRUSTED_PROXIES": "127.0.0.1/32"}), \
                patch.object(web, "cleanup_expired_jobs"):
            client = web.app.test_client()
            self.assertEqual(200, client.get("/", headers={"X-Forwarded-Host": "public.example"}).status_code)
            self.assertEqual(400, client.get("/", headers={"X-Forwarded-Host": "public.example"},
                environ_overrides={"REMOTE_ADDR": "127.0.0.2"}).status_code)
        with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "public.example", "VALIDATOR_TRUSTED_PROXIES": "invalid"}):
            self.assertEqual(400, web.app.test_client().get("/", headers={"X-Forwarded-Host": "public.example"}).status_code)

    def test_remote_access_requires_tls_and_authentication(self):
        client = web.app.test_client()
        remote = {"REMOTE_ADDR": "192.0.2.3"}
        with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "public.example", "VALIDATOR_AUTH_FILE": ""}):
            self.assertEqual(503, client.get("/", base_url="http://public.example", environ_overrides=remote).status_code)
            self.assertEqual(503, client.get("/", base_url="https://public.example", environ_overrides=remote).status_code)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "users.json"
            path.write_text(json.dumps({"archivist": generate_password_hash("correct password")}))
            with patch.dict(os.environ, {"VALIDATOR_ALLOWED_HOSTS": "public.example", "VALIDATOR_AUTH_FILE": str(path)}), \
                    patch.object(web, "cleanup_expired_jobs"):
                self.assertEqual(401, client.get("/", base_url="https://public.example", environ_overrides=remote).status_code)
                headers = {"Authorization": "Basic " + base64.b64encode(b"archivist:correct password").decode()}
                self.assertEqual(200, client.get("/", base_url="https://public.example", headers=headers, environ_overrides=remote).status_code)

    def test_local_dns_rebinding_host_is_not_accepted_by_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(400, web.app.test_client().get("/", base_url="http://attacker.example").status_code)

class DeadlineTests(unittest.TestCase):
    def test_workload_defaults_and_explicit_overrides(self):
        self.assertEqual(600, web.workload_seconds(0, 0))
        self.assertEqual(1800, web.workload_seconds(0, 0, minimum=1800))
        self.assertEqual(86400, web.workload_seconds(10**12, 0))
        self.assertEqual(7, web.workload_seconds(10**12, 7))

    def test_request_deadline_prevents_starting_a_subprocess(self):
        token = web.JOB_DEADLINE.set(0)
        try:
            with patch.object(web.subprocess, 'Popen') as launch:
                with self.assertRaises(ValueError):
                    web.bounded_subprocess(['unused'], 600)
                launch.assert_not_called()
        finally:
            web.JOB_DEADLINE.reset(token)

    def test_job_capacity_and_deadline_restore(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, 'ROOT', Path(directory)), patch.object(web, 'MAX_CONCURRENT_JOBS', 1):
            self.assertIsNone(web.JOB_DEADLINE.get())
            with web.job_slot():
                self.assertIsNotNone(web.JOB_DEADLINE.get())
                with self.assertRaises(web.JobCapacityError):
                    with web.job_slot():
                        self.fail('Concurrent job bypassed admission limit')
            self.assertIsNone(web.JOB_DEADLINE.get())
            with web.job_slot():
                pass
