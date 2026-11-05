# EA-PDF Validator Web Deployment

This app can run behind `thinkbox.digital` as a small Flask service proxied by nginx. The deployment template now requires authentication and TLS configuration before it serves private mail. This is a single trusted-team deployment, not a multi-tenant ownership/isolation system.

## Install

```bash
cd /path/to/eapdf
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-web.txt
```

Generation also needs Java 21 and a current generator JAR built with `mvn package`. Validation needs Docker and the configured veraPDF image (default `ghcr.io/verapdf/cli:v1.31.18`). Pre-pull the image before starting the service. The service account needs read access to application files and write access to `.validator_web_tmp`, `.generator_web_tmp`, and `out`.

Docker access must work for the service account without an interactive shell. Docker socket access is effectively host administrator access; review that deployment decision rather than granting it blindly. Without usable Docker, validation cannot establish a passing PDF/A result. Web checks use private, named containers with read-only input mounts, no networking, a 1 GiB memory limit, and a 512 MiB Java heap.

## Local smoke test

```bash
HOST=127.0.0.1 PORT=8876 python3 validator_web.py
```

Open:

```text
http://127.0.0.1:8876
```

## Production with gunicorn

```bash
cd /path/to/eapdf
. .venv/bin/activate
gunicorn --workers 2 --threads 4 --timeout 660 --bind 127.0.0.1:8876 validator_web:app
```

## systemd

Create an authentication file before starting the service. From the configured virtual environment, provision a user interactively without putting a password in shell history:

```bash
sudo install -d -m 0750 -o root -g www-data /etc/ea-pdf-validator
umask 077
users_file=$(mktemp)
python -c 'import getpass,json,sys; from werkzeug.security import generate_password_hash; print("Username: ",end="",file=sys.stderr,flush=True); user=input(); print(json.dumps({user:generate_password_hash(getpass.getpass())}))' > "$users_file"
sudo install -m 0640 -o root -g www-data "$users_file" /etc/ea-pdf-validator/users.json
rm "$users_file"
```

The application uses HTTP Basic authentication over HTTPS. Every route, including downloads/reports, requires a valid user when `VALIDATOR_REQUIRE_AUTH=1` or `VALIDATOR_AUTH_FILE` is set. Missing/malformed configuration fails closed with 503. Credentials use Werkzeug password hashes, never plaintext. Origin/host checks reject cross-site form submissions; nginx rate-limits requests. Existing bearer download URLs are scoped to this shared trusted-team authorization boundary, not per-user ownership.

Use:

- [validator-web.service](../deploy/validator-web.service)

Install it:

```bash
sudo cp deploy/validator-web.service /etc/systemd/system/validator-web.service
sudo cp deploy/validator-web-cleanup.service deploy/validator-web-cleanup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now validator-web.service
sudo systemctl enable --now validator-web-cleanup.timer
```

The service explicitly uses this checkout's `.venv/bin/gunicorn`; adjust the absolute paths in both service files if deploying elsewhere. Configure the same retention environment in the application and cleanup services when changing the default. The hourly timer removes expired and abandoned job files even when nobody visits the application.

## nginx

Provision a certificate for the configured host names and confirm the certificate/key paths in the example. The HTTP listener redirects to HTTPS; the application proxy is only on the TLS listener. Validate with `sudo nginx -t` before reloading. This repository does not install certificates, create server users, start services, or change a live deployment automatically.

Use:

- [nginx-thinkbox.digital.conf](../deploy/nginx-thinkbox.digital.conf)

This publishes the app at:

```text
https://thinkbox.digital/ea-pdf-validator/
```

Install it:

```bash
sudo cp deploy/nginx-thinkbox.digital.conf /etc/nginx/sites-available/validator-web
sudo ln -s /etc/nginx/sites-available/validator-web /etc/nginx/sites-enabled/validator-web
sudo nginx -t
sudo systemctl reload nginx
```

## Notes

- Uploads default to 64 MiB per request (`VALIDATOR_MAX_CONTENT_LENGTH`), three validation files (`VALIDATOR_MAX_UPLOAD_FILES`), and 100 generation inputs (`GENERATOR_MAX_UPLOAD_FILES`). Keep nginx's body limit consistent with the application limit.
- At most two requests execute jobs across all server workers (`VALIDATOR_MAX_CONCURRENT_JOBS`). Extra jobs receive HTTP 429, rather than forming an unlimited queue.
- `VALIDATOR_REQUEST_TIMEOUT` sets the overall generation/validation request budget (600 seconds). Individual validation and generation subprocesses have defaults of 600 and 300 seconds (`VALIDATOR_JOB_TIMEOUT`, `GENERATOR_JOB_TIMEOUT`). Keep the Gunicorn and proxy timeouts above the request budget, with cleanup headroom.
- POSIX address-space limits default to 2048 MiB for validation and 8192 MiB for generation (`VALIDATOR_JOB_MEMORY_MB`, `GENERATOR_JOB_MEMORY_MB`). Generation uses a 512 MiB Java heap. Each captured stdout/stderr log is capped at 4 MiB. Expired jobs stop their local process groups and their own veraPDF containers, not other users' jobs.
- Reports and downloads use unguessable capabilities stored atomically on disk, shared across workers and restarts. Links expire after 24 hours (`VALIDATOR_RETENTION_SECONDS`). Request-triggered cleanup and the hourly timer remove expired files, allowing the request budget plus one timer interval for abandoned-job cleanup. Reports can contain private email information; responses use `Cache-Control: no-store`.
- Web generation writes into a separate `.web-jobs/<job-id>` directory under the selected the generated-document directory location. Cleanup never deletes ordinary local CLI archives. Uploaded inputs are deleted on completion, including failed partial saves. Child PDF temporary files remain inside the managed validation job, so abandoned workers do not leave permanent extracted emails.
- Forwarded headers are ignored unless the immediate peer is listed in `VALIDATOR_TRUSTED_PROXIES` (comma-separated IPs/CIDRs). The systemd template trusts only `127.0.0.1/32`; keep the upstream port loopback-only and have nginx replace forwarded headers. Configure the public host allowlist. Direct non-loopback access requires HTTPS and the authentication file.
- Navigation, forms, and download/report URLs include the deployment prefix only from a trusted proxy. This remains a shared trusted-team service; it provides no per-user job ownership.
- `VALIDATOR_MAX_WORK_FILE_BYTES` defaults to 64 MiB and `VALIDATOR_MAX_SCRATCH_BYTES` to 512 MiB per job. Scratch and report bytes/counts are monitored during execution and after fast workers finish. Oversized work is stopped and reported as unavailable/incomplete. See [security limits](security/HARDENING.md) for decoder and CLI controls.

The Python parser keeps the configured soft address-space limit. Its hard limit
also reserves `VALIDATOR_DOCKER_CLIENT_MEMORY_MB` (default 4096 MiB) so a separate
exec can give the trusted Docker client sufficient bounded address space. This
is not an immutable parser hard limit or a RAM allocation. The Docker container's
own memory/heap limits still apply. The creator respects tighter inherited hard
file, address and CPU limits rather than attempting to raise them.

Choose **PDF/A only (sidecar finding aids)** for ordinary `_finding_aid.pdf`
companions; the full EA-PDF target remains the default. Command-line validators
select this scope with `EAPDF_VALIDATION_SCOPE=pdfa`. PDF/A-only reports explicitly
leave EA-PDF and ISO 32005 unevaluated. Filenames do not select the scope.
