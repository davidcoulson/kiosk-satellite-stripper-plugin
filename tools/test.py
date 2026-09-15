#!/usr/bin/env python3
"""Run device-independent tests: URL resolution, status parsing, manifest contract."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


sources = [
    *sorted((root / 'sdk/src').rglob('*.java')),
    *sorted((root / 'src').rglob('*.java')),
    *sorted((root / 'test').rglob('*.java')),
]
with tempfile.TemporaryDirectory(prefix='stripper-plugin-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-d', directory, *map(str, sources)], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'StripperUrlTest'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'StripperStatusTest'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory,
                    'me.jxl.kiosk.plugins.stripper.StripperEntitiesTest'], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'ManifestContractTest'], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)
