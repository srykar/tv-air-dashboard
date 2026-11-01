import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('provision_local', Path(__file__).with_name('provision.py'))
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)


def example():
    return {'host': '192.168.1.50', 'port': 1883, 'product_type': '897',
            'serial': 'EXAMPLE-UNIT-01', 'credential': 'A' * 32}


class PrivateProvisioningTest(unittest.TestCase):
    def test_public_addresses_and_loopback_are_rejected(self):
        for host in ('8.8.8.8', '127.0.0.1', '::1'):
            with self.subTest(host=host), self.assertRaises(ValueError):
                provision.validate(dict(example(), host=host))

    def test_account_secrets_cannot_be_added_to_device_payload(self):
        for key in ('password', 'token', 'email', 'otp'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                provision.validate(dict(example(), **{key: 'not-a-real-secret'}))

    def test_credentials_travel_over_stdin_and_not_command_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'connection.json'
            path.write_text(json.dumps(example()))
            with patch.object(provision.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as run:
                provision.provision(path, 'example-target')
            args, kwargs = run.call_args
            self.assertNotIn(example()['credential'], ' '.join(args[0]))
            self.assertNotIn(example()['serial'], ' '.join(args[0]))
            self.assertEqual(json.loads(kwargs['input']), example())
            self.assertIn('content://org.tvair.dashboard.dyson/connection', args[0])
            self.assertFalse(path.exists())

    def test_refused_provider_keeps_the_private_file_for_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'connection.json'
            path.write_text(json.dumps(example()))
            with patch.object(provision.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'Permission denied', b'')):
                with self.assertRaises(RuntimeError):
                    provision.provision(path, 'example-target')
            self.assertTrue(path.exists())

    def test_source_has_no_default_device_address_or_account_country(self):
        source = Path(__file__).with_name('setup.py').read_text()
        self.assertIn('PURIFIER_HOST = None', source)
        self.assertNotIn('value="SG"', source)
        self.assertIn("parser.add_argument('--purifier-host', required=True", source)
        self.assertIn("parser.add_argument('--state-dir', required=True", source)


if __name__ == '__main__':
    unittest.main()
