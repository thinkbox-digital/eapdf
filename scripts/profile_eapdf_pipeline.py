"""Sequential real-input profiling; private evidence stays under output/.

Requires psutil, Java 21 and built dist JARs. Does not change source inputs.
Run: python3 scripts/profile_eapdf_pipeline.py [--case eml-small ...]
"""
import argparse
import csv
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import psutil

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    'eml-small': 'eml_03_994.eml', 'eml-large': 'eml_23_25.eml',
    'msg-small': 'message.msg', 'msg-large': 'sediment.msg',
    'mbox-small': 'agileuml-dev.mbox', 'mbox-large': 'acceleo-dev.mbox',
    'pst-small': 'test.pst', 'gmail-small': None, 'gmail-large': None,
    'pst-large': 'pst_16_2005b_May-Jul_GovernF.pst',
}


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def save(path, value):
    pending = path.with_suffix('.pending')
    pending.write_text(json.dumps(value, indent=2) + '\n')
    pending.replace(path)


def measured(command, folder, env, progress):
    folder.mkdir(parents=True, exist_ok=True)
    recording = folder / 'jfr'
    recording.mkdir(exist_ok=True)
    environment = dict(env)
    environment['JAVA_TOOL_OPTIONS'] = f'-XX:StartFlightRecording=settings=profile,dumponexit=true,maxsize=512m,filename="{recording}/"'
    start = time.monotonic()
    peak = 0
    cpu = {}
    stages = {}
    with (folder / 'process.log').open('w') as log, (folder / 'samples.csv').open('w') as samples:
        writer = csv.writer(samples)
        writer.writerow(['seconds', 'stage', 'aggregate_rss_bytes', 'cpu_seconds', 'heap_used_bytes', 'stage_completed', 'stage_total'])
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        parent = psutil.Process(process.pid)
        try:
            while True:
                elapsed = time.monotonic() - start
                rss = 0
                try:
                    children = [parent] + parent.children(recursive=True)
                except psutil.NoSuchProcess:
                    children = []
                for child in children:
                    try:
                        rss += child.memory_info().rss
                        c = child.cpu_times()
                        cpu[child.pid] = c.user + c.system
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        pass
                try:
                    state = json.loads(progress.read_text())
                except (OSError, ValueError):
                    state = {}
                stage = state.get('stage', 'startup')
                row = stages.setdefault(stage, {'first_sample_seconds': elapsed, 'last_sample_seconds': elapsed, 'peak_rss_bytes': 0, 'peak_heap_bytes': 0})
                row['last_sample_seconds'] = elapsed
                row['peak_rss_bytes'] = max(row['peak_rss_bytes'], rss)
                row['peak_heap_bytes'] = max(row['peak_heap_bytes'], state.get('heap_used_bytes') or 0)
                peak = max(peak, rss)
                writer.writerow([round(elapsed, 3), stage, rss, round(sum(cpu.values()), 3), state.get('heap_used_bytes'), state.get('stage_completed'), state.get('stage_total')])
                samples.flush()
                if process.poll() is not None:
                    break
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    pass
        except BaseException:
            # Stop only this run's process group; never leave a detached validator behind.
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            except ProcessLookupError:
                pass
            save(folder/'interrupted.json', {'wall_seconds': time.monotonic()-start,
                 'sampled_peak_aggregate_rss_bytes': peak, 'exit_code': process.poll()})
            raise
    result = {'exit_code': process.returncode, 'wall_seconds': time.monotonic()-start,
              'sampled_peak_aggregate_rss_bytes': peak, 'sampled_cpu_seconds': sum(cpu.values()), 'stages': stages}
    save(folder / 'metrics.json', result)
    for recording in sorted(recording.glob('*.jfr')):
        with recording.with_suffix('.views.txt').open('w') as out:
            for view in ['hot-methods', 'allocation-by-site', 'gc']:
                out.write('\n' + view + '\n'); out.flush()
                subprocess.run(['jfr', 'view', '--width', '180', view, str(recording)], stdout=out, stderr=subprocess.STDOUT)
    return result


def main():
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', action='append', choices=CASES)
    parser.add_argument('--output', type=Path, default=ROOT/'output'/datetime.now().strftime('profiling-%Y%m%d-%H%M%S'))
    parser.add_argument('--creator', type=Path, default=ROOT / 'dist' / ('emailtopdfa-' + (ROOT / 'VERSION').read_text().strip() + '.jar'))
    parser.add_argument('--validator', type=Path, default=ROOT / 'dist' / ('eapdf-validator-' + (ROOT / 'VERSION').read_text().strip() + '.jar'))
    parser.add_argument('--heap-mib', type=int, default=10240)
    parser.add_argument('--automatic-budget', action='store_true', help='Exercise the creator automatic worker budget; validator heap remains --heap-mib')
    parser.add_argument('--gmail-token-dir', type=Path, default=Path.home()/'.gmail_token_store')
    args = parser.parse_args()
    if args.heap_mib < 256:
        parser.error('--heap-mib must be at least 256')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    config = json.loads((ROOT/'config/eapdf_config.json').read_text())
    config['creator'] = 'EA-PDF profiling test organization'
    save(output/'config.json', config)
    env = dict(os.environ, EAPDF_CONFIG=str(output/'config.json'), EAPDF_WORKER_HEAP_BYTES=str(args.heap_mib*1024**2), EAPDF_JOB_TIMEOUT_SECONDS='21600')
    if args.automatic_budget:
        env.pop('EAPDF_WORKER_HEAP_BYTES', None)
    manifest = {'automatic_worker_budget': args.automatic_budget, 'creator_sha256': sha(args.creator), 'validator_sha256': sha(args.validator), 'config_sha256': sha(output/'config.json'),
                'harness_sha256': sha(Path(__file__)), 'heap_bytes': args.heap_mib*1024**2, 'worker_heap_bytes': 'automatic' if args.automatic_budget else args.heap_mib*1024**2, 'worker_cap': 4, 'default_message_limit_bytes': 64*1024**2, 'expected_archive_render_workers': '2 for qualifying smaller jobs; automatic otherwise' if args.automatic_budget else min(4, max(1, args.heap_mib//1024)), 'sample_interval_seconds': 1, 'jfr': 'profile, 512 MiB bounded per JVM',
                'note': 'Aggregate process RSS includes shared pages; CPU is sampled and may omit short-lived processes. Single runs, warm filesystem caches; no throughput guarantee.'}
    save(output/'manifest.json', manifest)
    for name in args.case or CASES:
        folder = output/name
        if (folder/'result.json').exists():
            print(name + ': existing evidence retained (choose a new --output to rerun)', flush=True); continue
        folder.mkdir(parents=True, exist_ok=True)
        pdfs = folder/'pdf'; pdfs.mkdir(exist_ok=True)
        result = {'case': name, 'state': 'RUNNING'}
        source = ROOT/'in'/CASES[name] if CASES[name] else None
        if source:
            result['source'] = {'path': str(source.relative_to(ROOT)), 'bytes': source.stat().st_size, 'sha256': sha(source)}
        save(folder/'running.json', result)
        print(name + ': creation started', flush=True)
        # Scratch is on disk to avoid charging large PST temporary data to tmpfs RAM.
        with tempfile.TemporaryDirectory(prefix='scratch-', dir=folder) as scratch:
            java = ['java', f'-Xmx{args.heap_mib}m', '-Djava.awt.headless=true', '-XX:ActiveProcessorCount=4', '-Djava.io.tmpdir='+scratch, '-cp', str(args.creator)]
            if source:
                command = java + ['eapdf.EmailToPDFA', '--jobs', '4', '--out', str(pdfs)]
                if name.startswith(('pst-', 'mbox-')):
                    command += ['--archive-profile', 'm', '--archive-output', str(pdfs/(name+'.pdf'))]
                command += [str(source)]
            else:
                credentials = Path(scratch)/'credentials.json'
                shutil.copy2(Path.home()/'credentials.json', credentials)
                tokens = Path(scratch)/'tokens'
                shutil.copytree(args.gmail_token_dir, tokens)
                command = java + ['eapdf.GmailDownloadToPdf', '--credentials', str(credentials), '--token-dir', str(tokens), '--limit', '10' if name.endswith('small') else '200', '--out-pdf', str(pdfs), '--archive-profile', 'm', '--archive-output', str(pdfs/(name+'.pdf'))]
            result['creation'] = measured(command, folder/'creation', env, pdfs/'progress.json')
        print(name + ': creation exit ' + str(result['creation']['exit_code']), flush=True)
        result['pdfs'] = []
        for pdf in sorted(pdfs.glob('*.pdf')):
            if pdf.stem.endswith('_finding_aid'):
                continue
            validation = folder/('validation-'+pdf.stem)
            validation.mkdir(exist_ok=True)
            report = validation/'report.json'
            with tempfile.TemporaryDirectory(prefix='scratch-', dir=validation) as scratch:
                command = ['java', f'-Xmx{args.heap_mib}m', '-XX:ActiveProcessorCount=4', '-Djava.io.tmpdir='+scratch, '-cp', str(args.validator), 'eapdf.JavaValidation', str(pdf), str(report)]
                metrics = measured(command, validation, env, report.with_name(report.name+'.progress.json'))
            row = {'path': str(pdf), 'bytes': pdf.stat().st_size, 'sha256': sha(pdf), 'validation': metrics}
            summary = report.with_name(report.name+'.summary.json')
            if summary.exists() or (report.exists() and report.stat().st_size < 128*1024**2):
                data = json.loads((summary if summary.exists() else report).read_text())
                row['summary'] = data.get('summary'); row['pdfa'] = data.get('pdfa')
                row['failure_codes'] = data.get('failure_codes', sorted({r['code'] for r in data.get('items', []) if r.get('level') == 'FAIL'}))
            result['pdfs'].append(row)
            print(name + ': validation exit ' + str(metrics['exit_code']), flush=True)
        result['source_unchanged'] = sha(source) == result['source']['sha256'] if source else None
        result['state'] = 'MEASURED' if result['creation']['exit_code'] == 0 and result['pdfs'] else 'CONVERSION_FAILED'
        save(folder/'result.json', result)
        (folder/'running.json').unlink()


if __name__ == '__main__':
    main()
