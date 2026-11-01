"""Write only a local device credential to private TV storage, over standard ADB stdin."""
import argparse
import ipaddress
import json
from pathlib import Path
import re
import subprocess


def validate(raw):
    if set(raw) != {'host', 'port', 'product_type', 'serial', 'credential'}:
        raise ValueError('Only the five local-device fields are accepted')
    address = ipaddress.ip_address(raw['host'])
    networks = [ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if address.version != 4 or not any(address in n for n in networks):
        raise ValueError('A private IPv4 address is required')
    if raw['port'] != 1883 or str(raw['product_type']) != '897':
        raise ValueError('Only HushJet product 897 on local MQTT port 1883 is supported')
    if not re.fullmatch(r'[A-Z0-9-]{8,40}', raw['serial']):
        raise ValueError('Invalid local device identifier')
    if not re.fullmatch(r'[A-Za-z0-9+/=]{16,256}', raw['credential']):
        raise ValueError('Invalid local device credential')
    return raw


def provision(connection, device, adb='adb'):
    path = Path(connection)
    if path.stat().st_size > 4096:
        raise ValueError('Unexpected connection-file size')
    data = validate(json.loads(path.read_text()))
    result = subprocess.run(
        [adb, '-s', device, 'shell', 'content', 'write', '--uri',
         'content://org.tvair.dashboard.dyson/connection'],
        input=json.dumps(data).encode(), capture_output=True, timeout=30, check=True,
    )
    output = (result.stdout + result.stderr).decode(errors='replace').lower()
    if any(word in output for word in ('error', 'exception', 'denied', 'unsupported')):
        raise RuntimeError('TV refused private provisioning; connection file retained for retry')
    path.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connection', required=True, type=Path)
    parser.add_argument('--device', required=True, help='An already-authorized ADB target')
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    provision(args.connection, args.device, args.adb)
    print('Local device connection written to private TV storage; temporary credential file removed.')
