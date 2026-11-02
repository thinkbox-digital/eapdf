# Improvement evidence

Compact notes, source snapshots, current V85 results and creator verification receipts are included in Git. Active regression inputs are in `tests/fixtures/` and `src/test/resources/`.

The October24 cleanup backed up10,408 historical full reports, duplicate PDFs, ZIP snapshots and large run dumps. Every archived member was checked against its SHA-256 before cleanup. Of those files,10,385 were ignored/untracked and removed;23 selected for Git were kept. No source, active fixture, protected comparison PDF, target or debug file was removed.

The local backup is `archive/development-evidence-2026-10-24.tar.gz` (about672MB). It is ignored and is not part of the GitHub source update. `cleanup-backup-manifest.json` records its hash and all file hashes; `cleanup-request.json` records the reviewed removal set and outcome. Historical links to full reports may require this local archive. Ordinary Java/Python tests pass from a clean staged export without those reports.

Restore a single archived artifact from the repository root with:

```bash
tar -xzf archive/development-evidence-2026-10-24.tar.gz --keep-old-files docs/improvement/<archived-path>
```

Saved PASS results apply to their recorded bytes and tool budgets. Retained MSG, isolated attachment and large-file resource limits remain documented in `../DEVELOPMENT_STATUS.md`. Frozen validator source, rules and the pinned ISO profile are unchanged.
