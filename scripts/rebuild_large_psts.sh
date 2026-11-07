#!/usr/bin/env bash
# Recreate the seven real large PST archives and check each with the Java validator.
# Run again to resume; matching completed conversions/checks are reused.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
repo_dir=$PWD
run_dir="$repo_dir/output/large-pst-beta"
mkdir -p -- "$run_dir"
exec 9>"$run_dir/.run.lock"
flock -n 9 || { echo 'The large PST run is already running.' >&2; exit 1; }
for program in java python3 flock sha256sum; do
    command -v "$program" >/dev/null || { echo "Missing program: $program" >&2; exit 1; }
done
release_version=$(cat VERSION)
creator_jar="emailtopdfa-${release_version}.jar"
validator_jar="eapdf-validator-${release_version}.jar"
for jar in "$creator_jar" "$validator_jar"; do
    [[ -r "dist/$jar" ]] || { echo "Missing build: dist/$jar" >&2; exit 1; }
done
inputs=()
shopt -s nullglob
for number in 06 16 20 22 23 24 25; do
    matches=(in/pst_"$number"_*.pst)
    [[ ${#matches[@]} == 1 ]] || { echo "Expected one input matching in/pst_${number}_*.pst" >&2; exit 1; }
    inputs+=(--only "${matches[0]}")
done
# Freeze the JARs for this run, even if the installed builds change meanwhile.
work_dir=$(mktemp -d /tmp/eapdf-large-psts.XXXXXXXX)
trap 'rm -rf -- "$work_dir"' EXIT
cp -- "dist/$creator_jar" "dist/$validator_jar" "$work_dir/"
export EAPDF_GENERATOR_CLASSPATH="$work_dir/$creator_jar"
export EAPDF_VALIDATOR_JAR="$work_dir/$validator_jar"
export PYTHONDONTWRITEBYTECODE=1
# Keep the exact build identities with the durable run evidence.
sha256sum "$work_dir/$creator_jar" "$work_dir/$validator_jar" > "$run_dir/build-sha256.txt"
printf 'Output: %s\nFour rendering jobs; one archive at a time; full Java validation.\n' "$run_dir"
printf 'Progress files: %s/pst/*/conversion-*/progress.json\n' "$run_dir"
printf 'Validation progress: %s/pst/*/validation-*/*.progress.json\n' "$run_dir"
python3 -u scripts/run_real_world_corpus.py --workers 1 --output "$run_dir" "${inputs[@]}" 2>&1 | tee -a "$run_dir/run.log"
python3 - "$run_dir" <<'PY'
import json, sys
from pathlib import Path
folder = Path(sys.argv[1])
numbers = {'06', '16', '20', '22', '23', '24', '25'}
rows = [r for r in json.loads((folder / 'run-results.json').read_text())
        if r['input']['type'] == 'pst' and Path(r['input']['input']).name.split('_')[1] in numbers]
assert len(rows) == 7, 'The seven requested results were not recorded.'
summary = []
for row in rows:
    pdfs = row.get('pdfs', [])
    entry = {'input': row['input']['input'], 'status': row['status'],
             'conversion_exit': row.get('conversion', {}).get('exit_code'),
             'validation_finished': bool(pdfs) and all(p.get('validation_completed') for p in pdfs),
             'source_unchanged': row.get('source_unchanged'),
             'result': str(folder / 'pst' / row['input']['id'] / 'result.json')}
    summary.append(entry)
    print(f"{entry['input']}: {entry['status']} (validation finished: {entry['validation_finished']})")
(folder / 'large-pst-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print('Summary:', folder / 'large-pst-summary.json')
print('Exit 2 means at least one result needs review; see the summary and reports.')
# Completed checking can still report genuine source problems; never label those PASS.
sys.exit(0 if all(r['status'] == 'PASS' for r in rows) else 2)
PY
