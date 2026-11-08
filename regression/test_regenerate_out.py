"""Regeneration planning/publication checks, isolated from the real out directory."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'scripts'))
import regenerate_out as runner

class RegenerationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='eapdf-regeneration-test-', dir='/tmp')
        self.root = Path(self.temp.name)
        (self.root/'in').mkdir()
        self.patch = patch.object(runner, 'ROOT', self.root)
        self.patch.start()
    def tearDown(self):
        self.patch.stop()
        self.temp.cleanup()
    def source(self, name):
        path=self.root/'in'/name
        path.write_text('source '+name)
        return path
    def test_every_source_covered_by_every_applicable_profile(self):
        paths=[self.source(f'{i}.{kind}') for kind in runner.KINDS for i in range(3)]
        selected,tasks=runner.tasks_for(paths,'everything')
        self.assertEqual(set(paths),set(selected))
        self.assertEqual(len(tasks),len({t['id'] for t in tasks}))
        for path in paths:
            self.assertEqual({'1s','1si','1m','1mi','1c','1ci'},
                             {t['profile'] for t in tasks if path in t['sources']})
        sample,_=runner.tasks_for(paths,'sample')
        self.assertEqual(8,len(sample))
    def test_clear_does_not_follow_links(self):
        outside=self.root/'preserved';outside.mkdir();(outside/'keep').write_text('keep')
        (self.root/'out').symlink_to(outside,target_is_directory=True)
        with self.assertRaises(RuntimeError):runner.clear_output(self.root)
        (self.root/'out').unlink();(self.root/'out').mkdir()
        (self.root/'out'/'link').symlink_to(outside,target_is_directory=True)
        (self.root/'out'/'old.pdf').write_text('old')
        runner.clear_output(self.root)
        self.assertEqual('keep',(outside/'keep').read_text())
        self.assertEqual(set(runner.KINDS),{p.name for p in (self.root/'out').iterdir()})
    def test_failed_publication_preserves_previous_file_and_cleans_temporary_copy(self):
        original=self.source('source.eml')
        output=self.root/'out';output.mkdir()
        destination=output/'ready.pdf';destination.write_bytes(b'previous')
        with patch.object(runner.shutil,'copystat',side_effect=OSError('copy failed')):
            with self.assertRaises(OSError):runner.publish_pdf(original,destination)
        self.assertEqual(b'previous',destination.read_bytes())
        self.assertEqual([destination],list(output.iterdir()))
    def test_running_jobs_use_immutable_jar_snapshots(self):
        evidence=self.root/'evidence';evidence.mkdir()
        creator=self.root/'creator-1.jar';creator.write_bytes(b'creator version one')
        validator=self.root/'validator-1.jar';validator.write_bytes(b'validator version one')
        args=SimpleNamespace(creator_jar=creator,validator_jar=validator)
        runner.snapshot_jars(args,evidence)
        creator.write_bytes(b'new creator');validator.unlink()
        self.assertEqual(b'creator version one',args.creator_jar.read_bytes())
        self.assertEqual(b'validator version one',args.validator_jar.read_bytes())
        self.assertEqual('creator-1.jar',args.creator_jar.name)
        self.assertEqual({'creator','validator'},set(json.loads((evidence/'runtime-jars.json').read_text())))
    def test_isolated_publication_reports_and_original_hashes(self):
        source=self.source('space name.eml')
        runner.clear_output(self.root)
        evidence=self.root/'evidence';evidence.mkdir()
        workspace=self.root/'workspace';workspace.mkdir()
        args=SimpleNamespace(jobs=2,creator_jar=Path('creator.jar'),validator_jar=Path('validator.jar'),timeout=60)
        config=self.root/'config.json';config.write_text('{}')
        calls=[]
        def execute(command,log,timeout,env):
            calls.append((command,env))
            log.write_text('ok')
            if '-jar' in command:
                out=Path(command[command.index('--out')+1])
                (out/'one.pdf').write_bytes(b'%PDF-test')
                (out/'two.pdf').write_bytes(b'%PDF-test-two')
                (out/'progress.json').write_text('{}')
            else:
                published=list((self.root/'out/eml').glob('*.pdf'))
                self.assertEqual(2,len(published),'all converted PDFs must be visible before validation begins')
                progress=json.loads(next(evidence.rglob('result.json')).read_text())
                self.assertEqual('VALIDATING',progress['status'])
                self.assertEqual(2,len(progress['documents']))
                self.assertEqual(2,len(list((self.root/'out/eml').iterdir())), 'no incomplete publication files remain')
                Path(command[-1]).write_text(json.dumps({'summary':{'status':'PASS'}}))
            return {'exit_code':0,'timeout':False,'seconds':0.1}
        _,tasks=runner.tasks_for([source],'everything')
        with patch.object(runner,'execute',execute):
            result=runner.run_task(tasks[0],args,evidence,workspace,config,{str(source):runner.digest(source)},2*1024**3)
        self.assertEqual('PASS',result['status'])
        pdf=next((self.root/'out/eml').iterdir())
        self.assertTrue(pdf.name.endswith('_1s.pdf'))
        self.assertEqual(2,len(list((self.root/'out').rglob('*.*'))))
        self.assertTrue(list(evidence.rglob('progress.json')))
        self.assertTrue(list(evidence.rglob('*.validation.json')))
        self.assertEqual('1',calls[0][1]['VALIDATOR_CHILD_PROCESSES'])
        self.assertEqual(str(2*1024**3),calls[0][1]['EAPDF_WORKER_HEAP_BYTES'])
        self.assertEqual('source space name.eml',source.read_text())

if __name__=='__main__':unittest.main()
