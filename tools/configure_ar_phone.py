"""One-time USB pairing. Keep the video credential out of console and command lines."""
import re
import subprocess
import xml.etree.ElementTree as ET
from deploy_ar_source import remote

ADB = ['D:/C3/.android-sdk/platform-tools/adb.exe','-s','3VU6R21914002522']
PACKAGE = 'com.garan.tesnav.tenengkai'
if __name__ == '__main__':
    token=remote("from pathlib import Path\nprint(Path('/data/tenengkai-ar-source/stream.token').read_text())").strip()
    assert re.fullmatch('[a-f0-9]{48}',token)
    # Run only after installation, before the app reads these preferences.
    old=subprocess.run(ADB+['exec-out','run-as',PACKAGE,'cat','shared_prefs/c3-ar.xml'],capture_output=True)
    if b'No such file or directory' in old.stdout or b'No such file or directory' in old.stderr:
        root=ET.Element('map')
    else:
        assert old.returncode==0
        root=ET.fromstring(old.stdout)
    for name,value in [('endpoint','http://192.168.0.12:8082/frames'),('token',token)]:
        node=next((n for n in root if n.attrib.get('name')==name),None)
        if node is None:node=ET.SubElement(root,'string',{'name':name})
        node.text=value
    data=ET.tostring(root,encoding='utf-8',xml_declaration=True)
    subprocess.run(ADB+['shell','run-as',PACKAGE,'mkdir','-p','shared_prefs'],check=True,capture_output=True)
    result=subprocess.run(ADB+['shell',f"run-as {PACKAGE} sh -c 'umask 077; cat > shared_prefs/c3-ar.xml'"],
                          input=data,capture_output=True)
    assert result.returncode==0, 'Private preference provisioning failed'
    check=subprocess.run(ADB+['exec-out','run-as',PACKAGE,'cat','shared_prefs/c3-ar.xml'],capture_output=True,check=True)
    assert check.stdout==data
    print('Private direct-source pairing verified; credential not printed')
