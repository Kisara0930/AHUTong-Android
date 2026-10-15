import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class NativeComponentBoundaries(unittest.TestCase):
    def test_outer_host_abi_uses_only_android_jdk_and_owned_types(self):
        files = list((ROOT / 'core/host-api/src/main/java').rglob('*.java'))
        self.assertTrue(files)
        for path in files:
            text = path.read_text(encoding='utf-8')
            for imported in re.findall(r'^import ([^;]+);', text, re.M):
                self.assertTrue(imported.startswith(('java.', 'android.', 'com.ahu.ahutong.hostapi.')), str(path) + ': ' + imported)
            self.assertNotRegex(text, r'@Composable|okhttp3\.|kotlinx\.|kotlin\.')

    def test_native_apk_has_no_shared_runtime_dependency_or_embedded_packages(self):
        text = (ROOT / 'host/build.gradle.kts').read_text(encoding='utf-8')
        self.assertEqual(set(re.findall(r'implementation\(project\("([^"]+)"\)\)', text)), {':core:host-api', ':bootstrap-kernel'})
        self.assertNotIn('org.jetbrains.kotlin', text)
        self.assertNotIn('bootstrapAssets', text)
        self.assertNotIn('generated/bootstrapAssets', text)
        rules = (ROOT / 'host/proguard-rules.pro').read_text(encoding='utf-8')
        self.assertNotRegex(rules, r'-keep class (androidx|kotlin|okhttp3|kotlinx)\.\*\*')

    def test_component_catalog_is_complete_acyclic_and_has_unique_resource_ids(self):
        catalog = json.loads((ROOT / 'plugins/catalog.json').read_text(encoding='utf-8'))
        components = {item['id']: item for item in catalog['components']}
        declared = set(re.findall(r'include\s*\(\s*":plugins:([a-z][a-z0-9_]*)"\s*\)', (ROOT / 'settings.gradle.kts').read_text(encoding='utf-8')))
        self.assertEqual(declared, set(components))
        self.assertIn('shared_runtime', components)
        self.assertIn('ui_runtime', components)
        self.assertIn('camera_runtime', components)
        self.assertIn('content_runtime', components)
        allocations = [int(item['resourceId'], 16) for item in components.values()]
        self.assertEqual(len(allocations), len(set(allocations)))
        self.assertNotIn(0x7f, allocations)
        complete = set()
        def visit(name, active):
            self.assertNotIn(name, active)
            if name in complete: return
            active = active | {name}
            for dependency in components[name]['dependencies']:
                target = components[dependency['id']]
                self.assertGreaterEqual(target['versionCode'], dependency['minVersion'])
                self.assertLessEqual(target['versionCode'], dependency.get('maxVersion', 2**31-1))
                visit(dependency['id'], active)
            complete.add(name)
        for name in components: visit(name, set())
        for component in components.values():
            if component['id'] != 'shared_runtime':
                self.assertTrue(any(dep['id'] == 'shared_runtime' for dep in component['dependencies']))

    def test_full_extended_icon_binary_is_not_linked(self):
        for directory in ['core', 'runtime', 'plugins', 'host']:
            for path in (ROOT / directory).rglob('build.gradle.kts'):
                if 'build' not in path.relative_to(ROOT).parts:
                    self.assertNotIn('libs.androidx.material.icons.extended', path.read_text(encoding='utf-8'))

if __name__ == '__main__': unittest.main()
