"""One-session, loopback-only MyDyson setup. Account secrets stay in memory.

Protocol reference: libdyson-wg/libdyson-neon (cloud/account.py and cloud/utils.py).
Only the purifier's local credential is saved, in a private temporary directory.
"""
import argparse
import base64
import ipaddress
import html
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib import request, parse, error

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7

PRIVATE = None
PURIFIER_HOST = None
PORT = 8766
HOST = 'https://appapi.cp.dyson.com'
CAP = secrets.token_urlsafe(32)
STATE = {'step': 'email', 'message': '', 'started': time.time()}


def save_private(name, value):
    path = PRIVATE / name
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(value, f)
    os.chmod(path, 0o600)


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Unexpected API redirect')


def api(path, data=None, token=None):
    headers = {'User-Agent': 'android client', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/json'
    req = request.Request(HOST + path, headers=headers,
                          data=None if data is None else json.dumps(data).encode())
    with request.build_opener(NoRedirect()).open(req, timeout=25) as response:
        result = response.read(1_048_577)
        if len(result) > 1_048_576:
            raise ValueError('API response too large')
        return json.loads(result)


def decode_credential(value):
    decoder = Cipher(algorithms.AES(bytes(range(1, 33))), modes.CBC(bytes(16))).decryptor()
    raw = decoder.update(base64.b64decode(value, validate=True)) + decoder.finalize()
    unpad = PKCS7(128).unpadder()
    return json.loads(unpad.update(raw) + unpad.finalize())['apPasswordHash']


def utf(value):
    raw = value.encode('utf-8')
    return struct.pack('!H', len(raw)) + raw


def packet(kind, payload):
    length = len(payload)
    prefix = bytearray([kind])
    while True:
        digit, length = length % 128, length // 128
        prefix.append(digit | (128 if length else 0))
        if not length:
            break
    return bytes(prefix) + payload


def exact(sock, length):
    result = bytearray()
    while len(result) < length:
        part = sock.recv(length - len(result))
        if not part:
            raise ConnectionError('Purifier disconnected')
        result.extend(part)
    return bytes(result)


def receive(sock):
    kind = exact(sock, 1)[0]
    length = 0
    for i in range(4):
        digit = exact(sock, 1)[0]
        length += (digit & 127) << (7 * i)
        if not digit & 128:
            break
    else:
        raise ValueError('Invalid MQTT length')
    if length > 32768:
        raise ValueError('MQTT message too large')
    return kind, exact(sock, length)


def sensor_value(data, key, fallback):
    value = data.get(key, data.get(fallback))
    if isinstance(value, list):
        value = value[-1]
    if not isinstance(value, str) or not value.isdecimal():
        return None
    value = int(value)
    return value if 0 <= value <= 2000 else None


def read_sensors(config):
    """Read-only status requests. No fan or configuration commands."""
    topic = config['product_type'] + '/' + config['serial']
    with socket.create_connection((config['host'], 1883), timeout=5) as sock:
        sock.settimeout(12)
        connect = utf('MQIsdp') + bytes([3, 0xC2, 0, 30])
        connect += utf('tvsetup' + secrets.token_hex(4)) + utf(config['serial']) + utf(config['credential'])
        sock.sendall(packet(0x10, connect))
        kind, payload = receive(sock)
        if kind != 0x20 or len(payload) != 2 or payload[1] != 0:
            raise ValueError('Purifier refused the device credential')
        sock.sendall(packet(0x82, b'\x00\x01' + utf(topic + '/status/current') + b'\x00'))
        kind, payload = receive(sock)
        if kind != 0x90 or len(payload) < 3 or payload[2] == 0x80:
            raise ValueError('Purifier refused sensor subscription')
        msg = {'msg': 'REQUEST-PRODUCT-ENVIRONMENT-CURRENT-SENSOR-DATA',
               'time': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
        sock.sendall(packet(0x30, utf(topic + '/command') + json.dumps(msg).encode()))
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            kind, payload = receive(sock)
            if kind >> 4 != 3 or kind & 1:  # ignore retained sensor snapshots
                continue
            size = int.from_bytes(payload[:2], 'big')
            if payload[2:2 + size].decode() != topic + '/status/current':
                continue
            offset = 2 + size + (2 if kind & 6 else 0)
            raw = json.loads(payload[offset:])
            if raw.get('msg') != 'ENVIRONMENTAL-CURRENT-SENSOR-DATA':
                continue
            data = raw.get('data', {})
            fine, coarse = sensor_value(data, 'p25r', 'pm25'), sensor_value(data, 'p10r', 'pm10')
            if fine is None or coarse is None:
                raise ValueError('Purifier sensors are warming up or unavailable')
            sock.sendall(b'\xe0\x00')
            return {'pm25': fine, 'pm10': coarse, 'received_at': int(time.time())}
    raise TimeoutError('No fresh sensor response')


def page():
    message = html.escape(STATE['message'])
    intro = '<h1>Connect your Dyson HushJet</h1><p>This private setup runs on this computer. Sign-in details go to Dyson over an encrypted connection. Your password and login code are not saved or sent to chat.</p>'
    if STATE['step'] == 'email':
        fields = '<label>MyDyson email<input name="email" type="email" autocomplete="username" required autofocus></label><label>Account country<input name="country" maxlength="2" placeholder="Two-letter country code" required></label><button>Send login code</button>'
    elif STATE['step'] == 'verify':
        fields = '<p>Check your email for Dyson’s login code.</p><label>MyDyson password<input name="password" type="password" autocomplete="current-password" required autofocus></label><label>Email login code<input name="otp" inputmode="numeric" autocomplete="one-time-code" required></label><button>Connect purifier</button>'
    else:
        fields = '<h2>Dyson connected</h2><p>The local device connection is ready. You can close this tab and return to setup.</p>'
    return ('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Private Dyson setup</title><style>body{font:18px system-ui;background:#101820;color:#edf4fa;max-width:550px;margin:9vh auto;padding:24px;line-height:1.55}h1{font-size:30px}label{display:block;margin:22px 0}input{display:block;box-sizing:border-box;width:100%;padding:13px;margin-top:6px;font:inherit;border-radius:8px;border:1px solid #7c8a98}button{font:inherit;padding:13px 22px;background:#b4edc9;border:0;border-radius:8px;cursor:pointer}.message{color:#ffe2a8}</style>' + intro + '<p class="message">' + message + '</p><form method="post"><input type="hidden" name="csrf" value="' + CAP + '">' + fields + '</form></html>').encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never log submitted account details or URL capabilities.

    def valid(self):
        return (self.path == '/' + CAP and self.headers.get('Host') in
                (f'localhost:{PORT}', f'127.0.0.1:{PORT}') and time.time() - STATE['started'] < 3600)

    def render(self):
        content = page()
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(content)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Referrer-Policy', 'same-origin')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        if not self.valid():
            self.send_error(404)
            return
        self.render()

    def do_POST(self):
        if not self.valid() or self.headers.get('Origin') not in (f'http://localhost:{PORT}', f'http://127.0.0.1:{PORT}'):
            save_private('status.json', {'stage': 'local_form_blocked', 'valid_path_and_host': self.valid(), 'origin_missing': self.headers.get('Origin') is None, 'origin_null': self.headers.get('Origin') == 'null'})
            self.send_error(403)
            return
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length <= 8192:
            self.send_error(413)
            return
        form = parse.parse_qs(self.rfile.read(length).decode())
        if form.get('csrf') != [CAP]:
            self.send_error(403)
            return
        try:
            if STATE['step'] == 'email':
                email, country = form['email'][0].strip(), form['country'][0].strip().upper()
                if len(email) > 254 or len(country) != 2 or not country.isalpha():
                    raise ValueError('Check the email and two-letter account country')
                api('/v1/provisioningservice/application/Android/version')
                query = parse.urlencode({'country': country})
                status = api('/v3/userregistration/email/userstatus?' + query, {'email': email})
                if status.get('accountStatus') != 'ACTIVE':
                    raise ValueError('No active Dyson account found for that email and country')
                response = api('/v3/userregistration/email/auth?' + query + '&culture=en-US', {'email': email})
                STATE.update(email=email, challenge=response['challengeId'], step='verify', message='Login code sent. This page will never ask you to share it outside this local form.')
                save_private('status.json', {'stage': 'awaiting_login_code'})
            elif STATE['step'] == 'verify':
                result = api('/v3/userregistration/email/verify', {'email': STATE['email'], 'password': form['password'][0], 'otpCode': form['otp'][0].strip(), 'challengeId': STATE['challenge']})
                if result.get('tokenType') != 'Bearer' or not result.get('token'):
                    raise ValueError('Dyson did not return a valid login session')
                devices = api('/v2/provisioningservice/manifest', token=result['token'])
                matches = [d for d in devices if str(d.get('ProductType')) == '897' and d.get('LocalCredentials')]
                if len(matches) != 1:
                    raise ValueError('Expected one HushJet in this account; retry setup for device selection')
                device = matches[0]
                config = {'host': PURIFIER_HOST, 'port': 1883, 'product_type': '897', 'serial': device['Serial'], 'credential': decode_credential(device['LocalCredentials'])}
                save_private('connection.json', config)
                STATE.clear()
                STATE.update(step='done', message='', started=time.time())
                save_private('status.json', {'stage': 'credentials_ready'})
                try:
                    readings = read_sensors(config)
                    save_private('status.json', {'stage': 'sensor_verified', **readings})
                    STATE['message'] = 'Fresh indoor readings received: PM2.5 ' + str(readings['pm25']) + ', PM10 ' + str(readings['pm10']) + ' µg/m³.'
                except (OSError, ValueError, KeyError):
                    STATE['message'] = 'Device connection details obtained. The TV connection still needs a live sensor check.'
        except error.HTTPError as exc:
            STATE['message'] = 'Dyson could not complete this step (HTTP ' + str(exc.code) + '). Check your details; if the error continues, retry setup.'
            save_private('status.json', {'stage': 'login_error', 'http_status': exc.code})
        except (OSError, ValueError, KeyError, TypeError):
            STATE['message'] = 'Could not complete this step. Check your details and internet connection, or retry setup. Nothing was sent to chat.'
            save_private('status.json', {'stage': 'setup_error'})
        finally:
            form.clear()
        self.render()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Local, private HushJet setup; no built-in device address.')
    parser.add_argument('--purifier-host', required=True, help='Your purifier private IPv4 address')
    parser.add_argument('--state-dir', required=True, type=Path, help='A private directory outside this checkout')
    args = parser.parse_args()
    address = ipaddress.ip_address(args.purifier_host)
    private_networks = [ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if address.version != 4 or not any(address in n for n in private_networks):
        parser.error('Use an RFC1918 private IPv4 address for the purifier.')
    PURIFIER_HOST = str(address)
    PRIVATE = args.state_dir.expanduser().resolve()
    if Path(__file__).resolve().parents[1] == PRIVATE or Path(__file__).resolve().parents[1] in PRIVATE.parents:
        parser.error('Keep the private setup directory outside the source checkout.')
    PRIVATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(PRIVATE, 0o700)
    save_private('status.json', {'stage': 'awaiting_email'})
    save_private('browser.json', {'url': f'http://localhost:{PORT}/{CAP}'})
    print(f'Open this private local URL in your browser: http://localhost:{PORT}/{CAP}', flush=True)
    server = HTTPServer(('127.0.0.1', PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        STATE.clear()
        for name in ('connection.json', 'status.json', 'browser.json'):
            (PRIVATE / name).unlink(missing_ok=True)
        print('Private setup session closed; temporary device connection files removed.', flush=True)
