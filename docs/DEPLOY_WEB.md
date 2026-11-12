# EA-PDF Validator Web Deployment

This app can run behind `thinkbox.digital` as a small Flask service proxied by nginx. The deployment template now requires authentication and TLS configuration before it serves private mail. This is a single trusted-team deployment, not a multi-tenant ownership/isolation system.

## Install

```bash
cd /opt/eapdf
python3 -m venv .venv
. .venv/bin/activate
pip install --require-hashes -r requirements-web.lock
```

`requirements-web.txt` records supported version ranges; `requirements-web.lock` pins the reviewed dependency closure and artifact hashes for deployment. Regenerate the lock in an isolated environment with `pip-compile --generate-hashes --output-file requirements-web.lock requirements-web.txt`, then rerun the public regressions before adopting updates.

Existing deployments must install the updated lock and restart their Python web workers to load pikepdf 10.18.0 and pypdf 7.0.1. The pikepdf minimum includes the fix for the [cyclic JBIG2 native crash](https://github.com/pikepdf/pikepdf/issues/753). pypdf 7.0.1 includes an image-buffer sizing security fix and text-decoding fixes. Updating the checkout alone does not update an existing virtual environment:

```bash
cd /opt/eapdf
env -u PYTHONPATH PYTHONNOUSERSITE=1 PIP_USER=0 .venv/bin/python -m pip install --require-hashes -r requirements-web.lock
env -u PYTHONPATH PYTHONNOUSERSITE=1 .venv/bin/python -m pip check
env -u PYTHONPATH PYTHONNOUSERSITE=1 .venv/bin/python -c 'import pikepdf, pypdf; print("pikepdf", pikepdf.__version__, "pypdf", pypdf.__version__)'
sudo systemctl restart validator-web.service
```

Use the actual checkout and virtual-environment paths if they differ from the service template. Restart separately managed Gunicorn workers through their process manager. Disposable validator workers and their resource limits remain required after this dependency update.

Deploy the complete source checkout, including the internal `eapdf_validation/`
and `eapdf_web/` packages alongside the root Python entry points. Copying only
`validate_eapdf.py` or `validator_web.py` is insufficient. CLI commands and the
`validator_web:app` WSGI entry point are unchanged.

Generation also needs Java 21 and a current generator JAR built with `mvn package`. Validation needs Docker and the configured veraPDF image (default `ghcr.io/verapdf/cli:v1.31.18`). Pre-pull the image before starting the service. The service account needs read access to application files and write access to `.validator_web_tmp`, `.generator_web_tmp`, and `out`.

Docker access must work for the service account without an interactive shell. Docker socket access is effectively host administrator access; review that deployment decision rather than granting it blindly. Without usable Docker, validation cannot establish a passing PDF/A result. Web checks use private, named containers with read-only input mounts, no networking, a finite memory limit, and a Java heap sized from the job allowance.

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
cd /opt/eapdf
. .venv/bin/activate
VALIDATOR_REQUEST_TIMEOUT=600 gunicorn --workers 2 --threads 4 --timeout 660 --bind 127.0.0.1:8876 validator_web:app
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

The templates use `/opt/eapdf` and its `.venv`. Install the checkout there, or replace `/opt/eapdf` in both service files with the actual absolute installation directory before copying them. Ensure the service account can traverse that directory. Configure the same retention environment in the application and cleanup services when changing the default. The hourly timer removes expired and abandoned job files even when nobody visits the application.

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
- `VALIDATOR_REQUEST_TIMEOUT` bounds synchronous generation/validation HTTP requests. The application default is 86,400 seconds; the supplied systemd deployment explicitly sets 600 seconds, below its 660-second Gunicorn and nginx timeouts. Direct Gunicorn launches should also set `VALIDATOR_REQUEST_TIMEOUT=600`. There is no asynchronous job queue. Subprocess defaults scale with input size: validation starts at 600 seconds, generation at 1,800 seconds, and both cap at 86,400 seconds. `VALIDATOR_JOB_TIMEOUT` and `GENERATOR_JOB_TIMEOUT` override these subprocess budgets; the remaining request budget always takes precedence. Change all three deployment timeouts together, leaving at least 60 seconds for cleanup. Gunicorn thread-worker liveness is not a replacement for the application deadline.
- Memory allowances default to half available host/container memory divided by maximum concurrent jobs (`VALIDATOR_JOB_MEMORY_MB`, `GENERATOR_JOB_MEMORY_MB` override). Generation passes its memory budget to the Java worker with native address-space headroom. Each captured stdout/stderr log is capped at 4 MiB. Expired jobs stop their local process groups and their own veraPDF containers, not other users' jobs.
- Reports and downloads use unguessable capabilities stored atomically on disk, shared across workers and restarts. Links expire after 24 hours (`VALIDATOR_RETENTION_SECONDS`). Request-triggered cleanup and the hourly timer remove expired files, allowing the request budget plus one timer interval for abandoned-job cleanup. Reports can contain private email information; responses use `Cache-Control: no-store`.
- Web generation writes into a separate `.web-jobs/<job-id>` directory under the selected the generated-document directory location. Cleanup never deletes ordinary local CLI archives. Uploaded inputs are deleted on completion, including failed partial saves. Child PDF temporary files remain inside the managed validation job, so abandoned workers do not leave permanent extracted emails.
- Forwarded headers are ignored unless the immediate peer is listed in `VALIDATOR_TRUSTED_PROXIES` (comma-separated IPs/CIDRs). The systemd template trusts only `127.0.0.1/32`; keep the upstream port loopback-only and have nginx replace forwarded headers. Configure the public host allowlist. Direct non-loopback access requires HTTPS and the authentication file.
- Navigation, forms, and download/report URLs include the deployment prefix only from a trusted proxy. This remains a shared trusted-team service; it provides no per-user job ownership.
- `VALIDATOR_MAX_OPEN_FILES` defaults to 16,384 per worker, capped by the inherited hard limit. The Python web/CLI and Java desktop/CLI/collection workers use the same setting. Restart existing web services to load changed worker code.
- `VALIDATOR_MAX_SCRATCH_BYTES` defaults to half available project-disk space divided by maximum concurrent jobs; `VALIDATOR_MAX_WORK_FILE_BYTES` defaults to half that per-job share. Explicit settings override these defaults. Scratch and report bytes are monitored during execution and after fast workers finish. Oversized work is stopped and reported as unavailable/incomplete. See [security limits](security/HARDENING.md) for decoder and CLI controls.

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
