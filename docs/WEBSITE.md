# Maintain the documentation website

Public site: <https://thinkbox-digital.github.io/eapdf/>.
The site uses Material for MkDocs. GitHub continues to host source, issues,
Releases and Packages. Pages serves documentation only, not the web validator.

## Edit the source

- Edit `docs/manual/user-manual.html` for user-guide chapters; the site converts
  each chapter to Markdown during the build and reuses the screenshots. Follow
  `docs/manual/BUILD.txt` when updating the separate PDF/A manual.
- Edit docs/PROJECT_STATUS.md for current release/unreleased status and
  docs/ARCHITECTURE.md for architectural decisions. docs/README.md maps ownership.
- Edit the original Markdown files for reference and developer guides.
  `SOURCES` in `scripts/build_documentation.py` is the explicit publication list.
- Edit `docs/site/` for the website home, downloads and stylesheet.
- Edit `mkdocs.yml` for navigation and theme settings. Change
  `--eapdf-content-width` in `docs/site/stylesheets/extra.css` to adjust width.

Only these selected sources and manual screenshots enter the site.
The ignored local development log and docs/private archive never enter this list. Audit trees,
email fixtures, local build output and credentials are not copied. Links to other
repository documents lead to GitHub. Downloadable applications stay in Releases.
Fonts, search and theme assets are served locally; no analytics are configured.

## Build and preview

Use Python 3.12 and a temporary virtual environment:

```sh
python3 -m venv /tmp/eapdf-site-venv
PIP_USER=false /tmp/eapdf-site-venv/bin/pip install -r requirements-site.txt
/tmp/eapdf-site-venv/bin/python scripts/build_documentation.py --output /tmp/eapdf-site
python3 -m http.server 8000 --bind 127.0.0.1 --directory /tmp/eapdf-site
```

Open `http://127.0.0.1:8000`. The build is strict: MkDocs warnings fail the build.
Use the wrapper above so generated manual and reference pages are included.
Remove these task-owned temporary directories when finished.

## Publish

The **Documentation** Actions workflow builds pull requests without publishing.
A push to `main` builds and deploys using GitHub Pages' artifact workflow.
Maintainers can also run it manually. The deployment job alone has Pages and OIDC
write permissions; it runs only from `main`, never from pull requests.

Pages settings must use **GitHub Actions** as the build source. The `github-pages`
environment records deployments. A failed build does not replace the live site.
To roll back documentation, revert its source commit and let the workflow deploy.

Website dependencies are pinned in `requirements-site.txt`, independently of the
PDF manual dependencies. Review upstream changes, update pins, build strictly,
and check navigation, search, screenshots and narrow-screen layout before merging.
