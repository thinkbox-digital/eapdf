# Public regressions

This directory contains redistributable tests independent of the intentionally
private root `tests/` corpus. Security fixtures are synthetic and use temporary
storage; no live services, credentials or private mail are required.

Run the validator/worker/web tests from the repository root:

```sh
python -m pip install --require-hashes -r requirements-web.lock
python -B -m unittest discover -s regression -v
```

Coverage includes authentication, host/proxy boundaries, worker timeout/crash
cleanup, adaptive memory/disk budgets, explicit resource overrides, warning retries,
stale-result rejection, admission/deadline enforcement, XML limits and invalid
or empty-page PDFs. This is an initial public subset, not comprehensive parity
with every local Python test.

For independent conversion acceptance, install `quality/requirements.txt`, run
`python -B -m unittest discover -s regression/quality -v`, then use
`scripts/check_conversion_quality.py` with built creator/validator JARs and a new
`--output /tmp/eapdf-acceptance` directory. See [corpus scope](quality/README.md).
The eight oracle tests deliberately remove recipients, body text, attachments,
original source bytes and pages, alter source hashes and damage rendering.

The local `scripts/quality_release_gate.py` also requires installed viewer/npm
and browser dependencies, Python web/oracle dependencies and WebDriver. It fails
if any required browser, Python, Java build or conversion check fails. GitHub
runs these as separate jobs so their failures remain distinguishable.
