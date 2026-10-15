#!/usr/bin/env python3
"""Generate class-specific SDK ABI retention from actual consumer DEX, never whole-library keep rules."""
import argparse
import json
from pathlib import Path
from audit_plugin_bundle import definitions, read_apk, SYSTEM_PREFIXES

def generate(root, output):
    catalog = json.loads((root / 'plugins/catalog.json').read_text(encoding='utf-8'))
    referenced, owned, counts = set(), set(), {}
    for component in catalog['components']:
        if component['id'] == 'shared_runtime':
            continue
        name = component['id']
        apk = root / 'plugins' / name / 'build/outputs/apk/release' / (name + '-release-unsigned.apk')
        if not apk.is_file():
            raise ValueError('Build every consumer before shrinking the SDK: ' + name)
        classes, dexes = definitions(read_apk(apk))
        owned.update(classes)
        counts[name] = len(classes)
        for dex in dexes:
            for descriptor in dex.type_refs:
                descriptor = descriptor.lstrip('[')
                if descriptor.startswith('L') and not descriptor.startswith(SYSTEM_PREFIXES):
                    referenced.add(descriptor)
    retained = sorted(referenced - owned)
    rules = ['# Generated from actual consumer DEX. Do not replace with package-wide SDK keeps.',
             '-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions',
             '-keep class com.ahu.ahutong.runtime.SharedRuntimeEntry { *; }']
    rules += ['-keep class ' + descriptor[1:-1].replace('/', '.') + ' { *; }' for descriptor in retained]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(rules) + '\n', encoding='utf-8')
    output.with_suffix('.json').write_text(json.dumps({'consumers':counts,'retainedTypes':len(retained),
        'reflectionPolicy':'Retain class-specific public ABI, including overrides and fields; AAR consumer rules retain native/reflection internals.',
        'explicitKotlinReflectDependency':False},ensure_ascii=False,indent=2),encoding='utf-8')
    print('SDK ABI retention:', len(retained), 'types from', len(counts), 'consumers')

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();generate(args.root.resolve(),args.output)
