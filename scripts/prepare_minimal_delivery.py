#!/usr/bin/env python3
"""Collect a signed native APK, the complete catalog, a portable local market and size accounting."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server/plugin-market'))
from manifest import CertificateVerifier, inspect_package, MarketError
from store import PackageStore, Client

def closure(ids, components):
    result, pending = set(), list(ids)
    while pending:
        identity = pending.pop()
        if identity in result: continue
        result.add(identity)
        pending.extend(item['id'] for item in components[identity]['dependencies'])
    return result

def prepare(apk, output):
    output.mkdir(parents=True, exist_ok=True)
    catalog = json.loads((ROOT / 'plugins/catalog.json').read_text(encoding='utf-8'))
    components = {item['id']:item for item in catalog['components']}
    expected = set(components)
    actual = {path.stem for path in (ROOT / 'dist/ahup').glob('*.ahup')}
    if actual != expected: raise ValueError('AHUP inventory differs from catalog: ' + str(actual ^ expected))
    certificate = ROOT / 'host/src/main/assets/ahup_trusted_certificate.der'
    verifier = CertificateVerifier([str(certificate)])
    shutil.copy2(certificate, output / 'trusted-certificate.der')
    shutil.copy2(apk, output / apk.name)
    shutil.copy2(ROOT / 'plugins/catalog.json', output / 'component-catalog.json')
    shutil.copy2(ROOT / 'docs/architecture/minimal-initialization-testing.md', output / 'README.md')
    service = output / 'market-service';service.mkdir(exist_ok=True)
    for name in ['app.py','manifest.py','store.py','host_updates.py']:
        shutil.copy2(ROOT / 'server/plugin-market' / name, service / name)
    store = PackageStore(output / 'market-store',verifier=verifier,required=catalog['required'])
    packages = {}
    for identity in sorted(expected):
        source = ROOT / 'dist/ahup' / (identity + '.ahup')
        record = inspect_package(source, verifier)
        manifest = record['manifest']
        if manifest['hostApiVersion'] != catalog['hostApiVersion'] or manifest['runtimeVersion'] != catalog['runtimeVersion']:
            raise ValueError('Stale component runtime: ' + identity)
        if manifest['versionCode'] != components[identity]['versionCode']:
            raise ValueError('Stale component version: ' + identity)
        shutil.copy2(source, output / source.name)
        try: store.publish(source)
        except MarketError as error:
            if error.code != 'version_exists':raise
            store.package(identity,manifest['versionCode'],record['sha256'])
        cache = sum(item['size'] for item in manifest['files'] if item['path'].endswith('.dex') or item['path']=='resources.apk')
        payload = sum(item['size'] for item in manifest['files'])
        with zipfile.ZipFile(source) as archive:
            metadata = sum(archive.getinfo(name).file_size for name in ('manifest.json','signature.sig'))
        packages[identity] = {'archiveBytes':record['size'],'payloadBytes':payload,'executionCacheBytes':cache,
                              'metadataBytes':metadata,'installedBytes':payload+metadata,
                              'versionCode':manifest['versionCode'],'sha256':record['sha256']}
    mandatory = closure(catalog['required'], components)
    all_features = closure([name for name,item in components.items() if item.get('userVisible',True)] + catalog['required'],components)
    def size(ids):
        data = {key:sum(packages[name][key] for name in ids) for key in ['archiveBytes','payloadBytes','metadataBytes','installedBytes','executionCacheBytes']}
        data['componentIds']=sorted(ids)
        data['installedAndExecutionCacheBytes']=data['installedBytes']+data['executionCacheBytes']
        return data
    with zipfile.ZipFile(apk) as archive:
        dex = sum(item.file_size for item in archive.infolist() if item.filename.startswith('classes') and item.filename.endswith('.dex'))
        if any(item.filename.endswith('.ahup') for item in archive.infolist()):raise ValueError('Native APK embeds AHUP')
    report = {'apk':apk.name,'bareApkBytes':apk.stat().st_size,'bareDexBytes':dex,
              'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'componentCount':len(packages),
              'firstRequiredDownload':size(mandatory),'allUserFeatures':size(all_features),
              'conditionalLegacyMigration':packages.get('storage_migration'),
              'rollbackAccounting':'Fresh installation has no previous version; upgrades retain previous/good version payloads separately.',
              'businessDataAccounting':'Existing account/business databases and user downloads are retained and are not included in payload size.',
              'packages':packages}
    (output/'size-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    client=Client({'hostApiVersion':catalog['hostApiVersion'],'runtimeVersion':catalog['runtimeVersion'],'sdk':36,'abis':['arm64-v8a']})
    (output/'market-catalog.json').write_text(json.dumps(store.catalog(client),ensure_ascii=False,indent=2),encoding='utf-8')
    flags=' '.join('--required '+name for name in catalog['required'])
    (output/'Start-TestMarket.ps1').write_text('''param([int]$Port = 8092)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid local port.' }
$taskServer = Join-Path $PSScriptRoot 'market-service/app.py'
$taskStore = Join-Path $PSScriptRoot 'market-store'
Write-Host "Local market: http://127.0.0.1:$Port"
Write-Host "Connect a device, then run: adb reverse tcp:$Port tcp:$Port"
& python $taskServer --store $taskStore --bind 127.0.0.1 --port $Port '''+flags+'\n',encoding='utf-8')
    checks=[]
    for path in sorted(output.iterdir()):
        if path.is_file() and path.name!='SHA256SUMS':checks.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name)
    (output/'SHA256SUMS').write_text('\n'.join(checks)+'\n',encoding='utf-8')
    print(json.dumps({key:report[key] for key in ['bareApkBytes','bareDexBytes','componentCount']},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();prepare(args.apk.resolve(),args.output.resolve())
