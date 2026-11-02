"""Reproducible real-input conversion/Java-validation checks, with resumable evidence."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import tempfile

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {'.eml', '.msg', '.pst', '.mbox'}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def execute(command, log, timeout, env=None):
    start = time.perf_counter()
    with log.open('w') as stream:
        process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=env)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            return {'exit_code': process.returncode, 'seconds': round(time.perf_counter()-start, 3), 'timeout': True}
    return {'exit_code': code, 'seconds': round(time.perf_counter()-start, 3), 'timeout': False}


def file_inventory(folder):
    rows = []
    for path in sorted(folder.rglob('*')):
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            continue
        with path.open('rb') as stream:
            head = stream.read(16)
        kind = path.suffix.lower()[1:]
        signature = 'outlook-pst' if head.startswith(b'!BDN') else 'ole-compound' if head.startswith(bytes.fromhex('d0cf11e0a1b11ae1')) else 'other'
        checksum = digest(path)
        slug = re.sub(r'[^A-Za-z0-9._-]+', '_', path.stem)[:90]
        ident = f'{kind}_{slug}_{hashlib.sha256(str(path.relative_to(ROOT)).encode()).hexdigest()[:8]}'
        rows.append({'id': ident, 'input': str(path.relative_to(ROOT)), 'type': kind,
                     'bytes': path.stat().st_size, 'sha256': checksum, 'signature': signature})
    return rows


def available_memory():
    available = 1024**3
    try:
        available = int(re.search(r'MemAvailable:\s+(\d+)', Path('/proc/meminfo').read_text()).group(1)) * 1024
    except (OSError, AttributeError):
        pass
    for limit, used in [('/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory.current'),
                        ('/sys/fs/cgroup/memory/memory.limit_in_bytes', '/sys/fs/cgroup/memory/memory.usage_in_bytes')]:
        try:
            available = min(available, max(0, int(Path(limit).read_text()) - int(Path(used).read_text())))
        except (OSError, ValueError):
            pass
    return available


def workload_timeout(size):
    return min(86400, max(1800, 600 + size // (128 * 1024)))


def source_warnings(log):
    counts = {}
    for line in log.splitlines():
        if line.startswith('warning:'):
            # Keep every original warning in the conversion log; count categories here.
            category = re.sub(r'\s*\[.*', '', line.split(':', 1)[1]).strip()
            counts[category] = counts.get(category, 0) + 1
    return counts


def run_case(item, destination, creator, validator, creator_sha, validator_sha, force=False, concurrent=1):
    folder = destination / item['type'] / item['id']
    folder.mkdir(parents=True, exist_ok=True)
    evidence = folder / 'result.json'
    prior = json.loads(evidence.read_text()) if evidence.is_file() else {}
    processing_environment = {name: value for name, value in sorted(os.environ.items())
                              if name.startswith('EAPDF_MAX_') or name in
                              {'EAPDF_JOB_TIMEOUT_SECONDS', 'EAPDF_WORKER_HEAP_BYTES',
                               'EAPDF_WORKER_ADDRESS_BYTES'}}
    result = {'input': item, 'creator_sha256': creator_sha, 'validator_sha256': validator_sha,
              'processing_environment': processing_environment}
    pdf_folder = folder / ('conversion-' + creator_sha[:12])
    reusable = (not force and prior.get('input') == item and prior.get('creator_sha256') == creator_sha
                and prior.get('processing_environment', {}) == processing_environment
                and prior.get('conversion', {}).get('exit_code') == 0 and prior.get('pdfs')
                and all(Path(row['file']).is_file() and digest(Path(row['file'])) == row['sha256'] for row in prior['pdfs']))
    validation_reusable = (reusable and prior.get('validator_sha256') == validator_sha
        and all(row.get('validation_completed') and Path(row.get('report', '')).is_file()
                and digest(Path(row['report'])) == row.get('report_sha256') for row in prior['pdfs']))
    if validation_reusable:
        prior['conversion_reused'] = True
        prior['validation_reused'] = True
        prior['source_unchanged'] = digest(ROOT / item['input']) == item['sha256']
        evidence.write_text(json.dumps(prior, indent=2) + '\n')
        return prior
    if not reusable:
        pdf_folder = Path(tempfile.mkdtemp(prefix='conversion-' + creator_sha[:12] + '-', dir=folder))
        command = ['java', '-Djava.awt.headless=true', '-XX:ActiveProcessorCount=4',
                   '-Djava.io.tmpdir=' + str(pdf_folder), '-cp', str(creator),
                   'eapdf.EmailToPDFA', '--jobs', '4', '--out', str(pdf_folder)]
        environment = dict(os.environ)
        if concurrent > 1 and 'EAPDF_WORKER_HEAP_BYTES' not in environment:
            environment['EAPDF_WORKER_HEAP_BYTES'] = str(max(256 * 1024**2, available_memory() // (2 * concurrent)))
        result['worker_environment'] = {
            key: '<redacted>' if key == 'EAPDF_SIGNING_PASSWORD' else value
            for key, value in environment.items() if key.startswith('EAPDF_')}
        if item['type'] in ('pst', 'mbox'):
            command += ['--archive-profile', 'm', '--archive-output', str(pdf_folder / (item['id'] + '.pdf'))]
        command.append(str(ROOT / item['input']))
        result['command'] = command
        result['conversion_log'] = str(pdf_folder / 'conversion.log')
        result['conversion'] = execute(command, Path(result['conversion_log']), workload_timeout(item['bytes']), environment)
        log = Path(result['conversion_log']).read_text(errors='replace')
        # A valid single-message mailbox uses the existing standalone path.
        if result['conversion']['exit_code'] != 0 and 'require at least two emails' in log:
            fallback = command[:command.index('--archive-profile')] + [command[-1]]
            result['command'] = fallback
            result['conversion_log'] = str(pdf_folder / 'conversion-single.log')
            result['conversion'] = execute(fallback, Path(result['conversion_log']), workload_timeout(item['bytes']), environment)
            log = Path(result['conversion_log']).read_text(errors='replace')
        result['source_warnings'] = source_warnings(log)
        if result['conversion']['exit_code'] != 0:
            result['status'] = 'CONVERSION_FAILED'
            result['errors'] = [line for line in log.splitlines() if re.search(r'^error:|^Exception in thread|^Caused by:|^fatal:', line, re.I)][-12:] or log.splitlines()[-12:]
            evidence.write_text(json.dumps(result, indent=2)+'\n')
            return result
        files = sorted(path for path in pdf_folder.glob('*.pdf') if not path.stem.endswith('_finding_aid'))
        if not files:
            result['status'] = 'NO_EMAIL_OUTPUT'
            result['errors'] = log.splitlines()[-8:]
            evidence.write_text(json.dumps(result, indent=2)+'\n')
            return result
        result['pdfs'] = [{'file': str(path), 'sha256': digest(path)} for path in files]
    else:
        result.update(command=prior['command'], conversion=prior['conversion'], pdfs=prior['pdfs'], conversion_reused=True)
        result['conversion_log'] = prior.get('conversion_log', str(folder/'conversion.log'))
        result['source_warnings'] = prior.get('source_warnings', {})
    result['status'] = 'PENDING_VALIDATION'
    evidence.write_text(json.dumps(result, indent=2)+'\n')
    validation_folder = Path(tempfile.mkdtemp(prefix='validation-' + validator_sha[:12] + '-', dir=folder))
    for index, pdf in enumerate(result['pdfs'], 1):
        report = validation_folder / f'java-{index:04d}.json'
        heap = max(256, (available_memory() // 3 * 2) // (concurrent * 1024**2))
        # Some upstream Java temp factories cache java.io.tmpdir at JVM startup.
        # Keep that entire startup directory private and remove it even on timeout.
        with tempfile.TemporaryDirectory(prefix='scratch-', dir=validation_folder) as scratch:
            command = ['java', f'-Xmx{heap}m', '-XX:ActiveProcessorCount=4',
                       '-Djava.io.tmpdir=' + scratch, '-cp', str(validator),
                       'eapdf.JavaValidation', pdf['file'], str(report)]
            outcome = execute(command, validation_folder / f'validation-{index:04d}.log', workload_timeout(Path(pdf['file']).stat().st_size))
        pdf['validation'] = outcome
        pdf['report'] = str(report)
        progress = report.with_name(report.name + '.progress.json')
        pdf['validation_completed'] = progress.is_file() and json.loads(progress.read_text()).get('stage') == 'Finished'
        if report.is_file(): pdf['report_sha256'] = digest(report)
        if report.is_file():
            brief = report.with_name(report.name + '.summary.json')
            if not brief.is_file() and report.stat().st_size > 128 * 1024**2:
                raise ValueError('This validator did not produce a compact summary for its large report. Full report retained: ' + str(report))
            actual = json.loads((brief if brief.is_file() else report).read_text())
            pdf['status'] = actual['summary']['status']
            pdf['pdfa'] = actual['pdfa']['status']
            pdf['failures'] = actual.get('failure_codes', sorted({row['code'] for row in actual.get('items', []) if row['level'] == 'FAIL'}))
            pdf['unavailable'] = actual.get('unavailable_examples', [row for row in actual.get('items', []) if row['code'] == 'VALIDATION_INCOMPLETE'])
        else:
            pdf['status'] = 'VALIDATOR_ERROR'
    result['status'] = 'PASS' if all(pdf['status'] == 'PASS' and pdf.get('pdfa') == 'PASS'
                                   and pdf['validation']['exit_code'] == 0 for pdf in result['pdfs']) else ('VALIDATION_FAILED' if any(pdf['status'] == 'FAIL' for pdf in result['pdfs']) else 'INCOMPLETE')
    result['source_unchanged'] = digest(ROOT / item['input']) == item['sha256']
    if not result['source_unchanged']:
        result['status'] = 'SOURCE_CHANGED_DURING_RUN'
    evidence.write_text(json.dumps(result, indent=2)+'\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT/'in')
    parser.add_argument('--output', type=Path, default=ROOT/'output/real-world-2026-10-27')
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--only', action='append', default=[], help='Relative input path; repeat to select several cases')
    parser.add_argument('--force', action='store_true', help='Reconvert rather than reuse matching creator outputs')
    args = parser.parse_args()
    args.input = args.input.resolve()
    args.output = args.output.resolve()
    if args.workers < 1:
        parser.error('--workers must be positive')
    cpu_count = len(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else (os.cpu_count() or 1)
    args.workers = min(args.workers, max(1, cpu_count // 2))
    if Path('/proc/meminfo').is_file():
        available = int(re.search(r'MemAvailable:\s+(\d+)', Path('/proc/meminfo').read_text()).group(1)) * 1024
        args.workers = min(args.workers, max(1, available // (4 * 1024**3)))
    args.output.mkdir(parents=True, exist_ok=True)
    rows = file_inventory(args.input)
    inventory = rows
    (args.output/'inventory.json').write_text(json.dumps(rows, indent=2)+'\n')
    excluded = [{'input': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size,
                 'reason': 'Not a supported standalone email input type; left unchanged.'}
                for path in sorted(args.input.rglob('*'))
                if path.is_file() and path.suffix.lower() not in EXTENSIONS]
    (args.output/'unsupported-input-files.json').write_text(json.dumps(excluded, indent=2)+'\n')
    selected = set(args.only)
    unknown = selected - {row['input'] for row in rows}
    if unknown:
        parser.error('Unknown --only inputs: ' + ', '.join(sorted(unknown)))
    if args.only:
        rows = [row for row in rows if row['input'] in args.only]
        if not rows:
            parser.error('--only did not match any input')
    rows.sort(key=lambda row: (row['bytes'], row['input']))
    creator = Path(os.environ.get('EAPDF_GENERATOR_CLASSPATH', ROOT/'dist/emailtopdfa.jar')).resolve()
    validator = Path(os.environ.get('EAPDF_VALIDATOR_JAR', ROOT/'dist/eapdf-validator.jar')).resolve()
    creator_sha, validator_sha = digest(creator), digest(validator)
    current = {row['input']: {'input': row, 'status': 'NOT_RUN', 'creator_sha256': creator_sha, 'validator_sha256': validator_sha} for row in inventory}
    for row in inventory:
        path = args.output / row['type'] / row['id'] / 'result.json'
        if path.is_file():
            prior = json.loads(path.read_text())
            if prior.get('input') == row and prior.get('creator_sha256') == creator_sha and prior.get('validator_sha256') == validator_sha:
                current[row['input']] = prior
    results = []
    print(f'Running {len(rows)} input files; {args.workers} concurrent cases; 4 creator workers per conversion; large inputs run alone; Java validation.', flush=True)
    batches = [(args.workers, [row for row in rows if row['bytes'] < 128 * 1024**2])]
    batches += [(1, [row]) for row in rows if row['bytes'] >= 128 * 1024**2]
    for concurrency, batch in batches:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            tasks = {pool.submit(run_case, row, args.output, creator, validator, creator_sha, validator_sha, args.force, concurrency): row for row in batch}
            for task in as_completed(tasks):
                item = tasks[task]
                try:
                    result = task.result()
                except Exception as error:
                    result = {'input': item, 'status': 'HARNESS_ERROR', 'error': str(error),
                              'creator_sha256': creator_sha, 'validator_sha256': validator_sha}
                    folder = args.output / item['type'] / item['id']
                    folder.mkdir(parents=True, exist_ok=True)
                    (folder/'result.json').write_text(json.dumps(result, indent=2)+'\n')
                results.append(result)
                current[item['input']] = result
                (args.output/'run-results.json').write_text(json.dumps(list(current.values()), indent=2)+'\n')
                print(f"{len(results)}/{len(rows)} {item['input']}: {result['status']}", flush=True)
    print('Results:', args.output/'run-results.json', flush=True)


if __name__ == '__main__':
    main()
