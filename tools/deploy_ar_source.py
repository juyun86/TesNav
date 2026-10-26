"""Install only the isolated read-only source. Never restart driving processes."""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
SSH_OPTIONS = ['-o','BatchMode=yes','-o','ConnectTimeout=6','-o','StrictHostKeyChecking=yes',
               '-o','HostKeyAlias=192.168.8.101','-i','C:/Users/panda/.ssh/id_ed25519_comma']
HOST = 'comma@192.168.0.12'

def remote(script):
    result = subprocess.run(['ssh',*SSH_OPTIONS,HOST,'/usr/local/venv/bin/python -B -'],
                            input=script,text=True,encoding='utf-8',capture_output=True,timeout=45)
    if result.returncode:
        raise RuntimeError(result.stderr[-1800:])
    return result.stdout

if __name__ == '__main__':
    names = ['c3_ar_source.py','ar_lane_projection.py','tenengkai-ar-source.service']
    hashes = {n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in names}
    print(remote('''
from pathlib import Path
import shutil,socket,json,subprocess,os,secrets
b=Path('/data/tenengkai-ar-source')
assert not b.exists(), 'Existing installation must be reviewed, never overwritten blindly'
s=socket.socket();assert s.connect_ex(('127.0.0.1',8082))!=0;s.close()
old=Path('/data/openpilot/tools/c3_ar_test')
assert (old/'c3_webd.py').is_file() and (old/'deps').is_dir()
b.mkdir(mode=0o700)
shutil.copy2(old/'c3_webd.py',b/'c3_webd.py')
shutil.copytree(old/'deps',b/'deps',ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
(b/'stream.token').write_text(secrets.token_hex(24));os.chmod(b/'stream.token',0o600)
print(json.dumps({'isolatedDirectoryCreated':True,'oldPrototypeUnchanged':True}))
'''))
    for name in names:
        subprocess.run(['scp',*SSH_OPTIONS,str(ROOT/name),HOST+':/data/tenengkai-ar-source/'+name],
                       check=True,capture_output=True)
    print(remote('''
from pathlib import Path
import hashlib,json,subprocess,ast
b=Path('/data/tenengkai-ar-source')
expected=EXPECTED
for n,h in expected.items():assert hashlib.sha256((b/n).read_bytes()).hexdigest()==h
for n in ('c3_ar_source.py','ar_lane_projection.py'):ast.parse((b/n).read_text())
unit=Path.home()/'.config/systemd/user/tenengkai-ar-source.service'
assert not unit.exists()
unit.parent.mkdir(parents=True,exist_ok=True);unit.symlink_to(b/unit.name)
before=subprocess.check_output(['loginctl','show-user','comma','-p','Linger'],text=True).strip()
subprocess.run(['sudo','-n','loginctl','enable-linger','comma'],check=True)
subprocess.run(['systemctl','--user','daemon-reload'],check=True)
subprocess.run(['systemctl','--user','enable','--now',unit.name],check=True)
result={'hashes':expected,'lingerBefore':before,'unit':str(unit),'controlFilesChanged':False}
(b/'installation.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
'''.replace('EXPECTED',repr(hashes))))
