import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from test_preservation_safety import validator, web, ROOT
from validation_worker import docker_command, run_cli


class ValidationScopeTests(unittest.TestCase):
    def pdfa(self, status='PASS'):
        return dict(status=status, ran=True, compliant=status=='PASS', profile_allowed=True,
                    profile_name='PDF/A-2B validation profile')

    def test_legacy_pdfa_environment_cannot_bypass_email_checks(self):
        with patch.dict(os.environ, EAPDF_VALIDATION_SCOPE='pdfa'), patch.object(validator, 'run_verapdf', return_value=self.pdfa()), patch.object(validator, 'validate', side_effect=RuntimeError('email checks required')):
            with self.assertRaisesRegex(RuntimeError, 'email checks required'):
                validator.validate_one('finding_aid.pdf', write_html=False)

    def test_validation_form_has_no_scope_selector(self):
        with web.app.test_request_context('/'):
            self.assertNotIn('name="validation_scope"', web.render_page())

    def test_parser_and_docker_client_keep_separate_bounded_address_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            script=Path(directory)/'probe.py'
            script.write_text('import json,resource,subprocess,sys\nfrom validation_worker import docker_command\n'
                'probe="import json,resource;print(json.dumps(resource.getrlimit(resource.RLIMIT_AS)))"\n'
                'print(json.dumps({"parser":resource.getrlimit(resource.RLIMIT_AS),"client":json.loads(subprocess.check_output(docker_command([sys.executable,"-c",probe])))}))\n')
            with patch.dict(os.environ, PYTHONPATH=str(ROOT), VALIDATOR_JOB_MEMORY_MB='512', VALIDATOR_DOCKER_CLIENT_MEMORY_MB='4096'), patch('validation_worker.shutil.which', return_value=None):
                code, output, errors, reason=run_cli(script, [])
            self.assertIsNone(reason, errors)
            limits=json.loads(output)
            self.assertEqual([512*1024**2,4096*1024**2], limits['parser'])
            self.assertEqual([4096*1024**2]*2, limits['client'])


class ConfigurationBundleTests(unittest.TestCase):
    def test_only_allowlisted_public_files_and_deterministic_bytes(self):
        spec=importlib.util.spec_from_file_location('configuration_package', ROOT/'scripts/package_configuration.py')
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);config=root/'config';config.mkdir()
            for name in module.PUBLIC_FILES:
                path=config/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(name)
            for name in ['credentials.json','tokens.json','secret.p12']:(config/name).write_text('PRIVATE TEST DATA')
            first=root/'first.zip';second=root/'second.zip'
            module.package(first,config);module.package(second,config)
            self.assertEqual(first.read_bytes(),second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual({'config/'+name for name in module.PUBLIC_FILES},set(archive.namelist()))
                self.assertNotIn(b'PRIVATE TEST DATA',b''.join(archive.read(name) for name in archive.namelist()))
            (config/'README.md').unlink();(config/'README.md').symlink_to(root/'private')
            with self.assertRaises(ValueError):module.package(root/'bad.zip',config)
