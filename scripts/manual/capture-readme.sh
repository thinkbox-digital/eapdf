#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
viewer=${1:?Pass the Full Desktop Viewer JAR path}
output=${2:-"$root/docs/screenshots/readme-viewer-1m.png"}
archive="$root/examples/eml/eml_seating_archive_1m.pdf"
work=$(mktemp -d "${TMPDIR:-/tmp}/eapdf-readme-capture.XXXXXX")
trap 'rm -rf -- "$work"' EXIT

unzip -p "$viewer" META-INF/eapdf/javafx/linux.jar > "$work/javafx-graphics.jar"
cat > "$work/window.properties" <<'EOF'
width=1440
height=900
messagesDivider=0.22
filesDivider=0.80
zoom=Fit width
EOF

mkdir -p -- "$(dirname -- "$output")"
java -Dprism.order=sw -Duser.language=en -Duser.country=US \
  -Djava.io.tmpdir="$work" -Deapdf.viewer.settings="$work/window.properties" \
  -cp "$work/javafx-graphics.jar:$viewer" "$root/scripts/manual/CaptureReadme.java" "$archive" "$work/screenshot.png"
install -m 0644 -- "$work/screenshot.png" "$output"
