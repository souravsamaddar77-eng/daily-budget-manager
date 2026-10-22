"""Dependency-free privacy/configuration checks. These do not compile Kotlin."""
from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
assert {x.get(ANDROID + 'name') for x in manifest.findall('uses-permission')} == {
    'android.permission.POST_NOTIFICATIONS'
}, 'Only notification posting is requested; no network, SMS, or contacts permissions'
services = manifest.findall('.//service')
assert len(services) == 1, 'Only the optional payment notification listener is expected'
listener = services[0]
assert listener.get(ANDROID + 'name') == '.notifications.PaymentNotificationListener'
assert listener.get(ANDROID + 'exported') == 'false'
assert listener.get(ANDROID + 'permission') == 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'
assert [x.get(ANDROID + 'name') for x in listener.findall('intent-filter/action')] == [
    'android.service.notification.NotificationListenerService'
]
app = manifest.find('application')
assert app.get(ANDROID + 'allowBackup') == 'false'
assert app.get(ANDROID + 'fullBackupContent') == '@xml/backup_rules'
assert app.get(ANDROID + 'dataExtractionRules') == '@xml/data_extraction_rules'
domains = {'root', 'file', 'database', 'sharedpref', 'external',
           'device_root', 'device_file', 'device_database', 'device_sharedpref'}
legacy = ET.parse(ROOT / 'app/src/main/res/xml/backup_rules.xml').getroot()
modern = ET.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
for rules in (legacy, modern.find('cloud-backup'), modern.find('device-transfer')):
    assert {x.get('domain') for x in rules.findall('exclude') if x.get('path') == '.'} >= domains
for path in (ROOT / 'app/src').rglob('*.xml'):
    ET.parse(path)
sources = list((ROOT / 'app/src/main/java').rglob('*.kt'))
assert sources, 'Application sources are missing'
for path in sources:
    source = path.read_text(encoding='utf-8')
    assert not re.search(r'fallbackToDestructiveMigration|android\.util\.Log|println\(', source), path
    if 'domain' in path.parts:
        assert not re.search(r'\b(Float|Double)\b|\.toDouble\(|\.toFloat\(', source), path
        assert 'import android.' not in source, path
wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == '3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98'
properties = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607' in properties
assert 'android.builder.sdkDownload=false' in (ROOT / 'gradle.properties').read_text()
print(f'PASS: {len(sources)} Kotlin files inspected; XML, permission surface, backup exclusions, money type guard, logging guard, wrapper integrity.')
print('This source audit is not Kotlin compilation, Android lint, unit execution, or device validation.')
