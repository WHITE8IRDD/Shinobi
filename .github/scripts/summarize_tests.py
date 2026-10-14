"""Expose small test summaries via GitHub annotations, including on restricted clients."""
import pathlib
import sys
import xml.etree.ElementTree as ET

count = failures = skipped = 0
for path in pathlib.Path(sys.argv[1]).rglob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    count += int(root.get('tests', 0))
    failures += int(root.get('failures', 0)) + int(root.get('errors', 0))
    skipped += int(root.get('skipped', 0))
    for case in root.iter('testcase'):
        for error in list(case.findall('failure')) + list(case.findall('error')):
            message = f'{case.get("classname")}.{case.get("name")}: {error.text or error.get("message", "")}'[:8000]
            message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error::' + message)
print(f'::notice::Tests: {count}; failures/errors: {failures}; skipped: {skipped}')
