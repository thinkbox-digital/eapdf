#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s [--check]\n' "$0" >&2
  exit 2
}

[[ $# -le 1 ]] || usage
mode=${1:-}
[[ -z "$mode" || "$mode" == --check ]] || usage
full=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
workflow=.github/workflows/desktop-viewer.yml

# Copy only shared sources. The edition marker and local build output stay local.
sources=(desktop-viewer/.gitignore desktop-viewer/README.md
         desktop-viewer/package.sh desktop-viewer/pom.xml desktop-viewer/src "$workflow")

for edition in lite secure; do
  checkout="$full/../eapdf-$edition"
  if [[ ! -f "$checkout/desktop-viewer/edition" ]] ||
     [[ $(<"$checkout/desktop-viewer/edition") != "$edition" ]]; then
    printf 'Missing %s checkout or desktop viewer edition marker at %s.\n' "$edition" "$checkout" >&2
    exit 1
  fi
done

cd "$full"
status=0
for edition in lite secure; do
  checkout="$full/../eapdf-$edition"
  if [[ "$mode" == --check ]]; then
    changes=$(rsync -acRni --delete --no-times --omit-dir-times --no-owner --no-group -- "${sources[@]}" "$checkout/")
    if [[ -n "$changes" ]]; then
      printf '%s viewer differs from Full:\n%s\n' "$edition" "$changes" >&2
      status=1
    fi
  else
    rsync -acRi --delete -- "${sources[@]}" "$checkout/"
  fi
done
exit "$status"
