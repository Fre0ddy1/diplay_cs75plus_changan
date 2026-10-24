#!/usr/bin/env python3
"""Fail when an upstream merge drops a required customization integration point."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
checks={
    'settings.gradle.kts': ['include(":vehicle-customization-api", ":changan-hud")','customizations/api','customizations/changan-hud'],
    'shared/build.gradle': ["api project(':vehicle-customization-api')"],
    'common/build.gradle.kts': ['implementation(project(":changan-hud"))'],
    'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt': [
        'VehicleCustomizations.onSessionStarted(context.applicationContext)',
        'VehicleCustomizations.onSessionStarted(appContext)',
        'VehicleCustomizations.onSessionEnded()',
        'VehicleCustomizations.onNavigationFrame(frame.messageId, frame.payload)',
    ],
    'common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt': ['VehicleCustomizations.onSessionEnded()'],
    'common/src/main/java/com/shilapi/xcertplay/InCallClusterBridge.kt': ['VehicleCustomizations.onProjectionNavigationState(state)'],
    'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt': ['VehicleCustomizations.settings(this)'],
    'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt': ['VehicleCustomizations.diagnosticListener = routeTraceListener'],
}
failures=[]
for name,needles in checks.items():
    path=ROOT/name
    text=path.read_text(encoding='utf-8') if path.exists() else ''
    for needle in needles:
        if needle not in text:
            failures.append(name+': missing '+needle)
controller=(ROOT/'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt').read_text(encoding='utf-8')
if controller.count('VehicleCustomizations.onSessionEnded()')!=2:
    failures.append('Controller must notify session end and final close exactly once each')
android='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(ROOT/'customizations/changan-hud/src/main/AndroidManifest.xml').getroot()
provider=manifest.find('application/provider')
if provider is None or provider.get(android+'exported')!='false' or provider.get(android+'name')!='com.freddyq.diplay.changan.ChanganCustomizationInitializer' or provider.get(android+'authorities')!='${applicationId}.customizations.changan':
    failures.append('Missing private Changan module initializer provider')
for module in ['shared','common']:
    for path in (ROOT/module/'src/main').rglob('*.kt'):
        if 'ChanganHudBridge' in path.read_text(encoding='utf-8'):
            failures.append(str(path.relative_to(ROOT))+': vendor implementation leaked into core')
for path in (ROOT/'customizations/changan-hud/src/main/java').rglob('*.kt'):
    if 'com.shilapi.xcertplay.hud.' in path.read_text(encoding='utf-8'):
        failures.append(str(path.relative_to(ROOT))+': bypassed upstream navigation adapter')
if failures:
    raise SystemExit('Customization integration needs review:\n'+'\n'.join(failures))
print('Customization integration check passed.')
