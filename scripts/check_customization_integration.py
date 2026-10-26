#!/usr/bin/env python3
"""Fail when an upstream merge drops a required customization integration point."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
checks={
    'settings.gradle.kts': ['include(":vehicle-customization-api", ":changan-hud")','customizations/api','customizations/changan-hud','include(":changan-projection")','customizations/changan-projection'],
    'shared/build.gradle': ["api project(':vehicle-customization-api')"],
    'common/build.gradle.kts': ['implementation(project(":changan-hud"))','implementation(project(":changan-projection"))'],
    'shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt': [
        'VehicleCustomizations.onSessionStarted(context.applicationContext)',
        'VehicleCustomizations.onSessionStarted(appContext)',
        'VehicleCustomizations.onSessionEnded()',
        'VehicleCustomizations.onNavigationFrame(frame.messageId, frame.payload)',
    ],
    'common/src/main/java/com/shilapi/xcertplay/DiPlaySessionService.kt': ['VehicleCustomizations.onSessionEnded()'],
    'common/src/main/java/com/shilapi/xcertplay/InCallClusterBridge.kt': ['VehicleCustomizations.onProjectionNavigationState(state)','VehicleCustomizations.onProjectionStarted(ctx, automatic, projectionControl)','VehicleCustomizations.onProjectionLayoutRequested(layout)','VehicleCustomizations.onProjectionReleased()','fun adoptObservedProjection','VehicleCustomizations.onProjectionResumed(ctx, scoped)','bound = app.bindService'],
    'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt': ['VehicleCustomizations.settings(this)'],
    'common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt': ['if (!customCardContentCache)', 'CARDS_DISABLED || !customCardContentCache','CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(context))'],
    'customizations/changan-hud/src/main/java/com/freddyq/diplay/changan/ChanganHudBridge.kt': ['ChanganHudConflictObserver(app)','session.recoverFromExternalReset','conflicts.stop()'],
    'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt': ['VehicleCustomizations.diagnosticListener = routeTraceListener','VehicleCustomizations.onProjectionManualControl()','VehicleCustomizations.shouldActivateProjectionChannel()','InCallClusterBridge.openProjection(this, automatic = true)','InCallClusterBridge.resumeAutomaticProjection(this, autoProjectToken)','InCallClusterBridge.observeCallback(this, presentation)','InCallClusterBridge.detachAutomaticProjection(autoProjectToken)'],
    'common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt': ['fun saveClusterContent', 'overlaySettingsListener?.invoke()'],
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
app_manifest=ET.parse(ROOT/'mobile/src/main/AndroidManifest.xml').getroot().find('application')
if app_manifest is None or app_manifest.get(android+'label')!='@string/cs75plus_app_name':
    failures.append('Missing CS75PLUS distribution application label')
branding=ET.parse(ROOT/'mobile/src/main/res/values/strings_cs75plus.xml').getroot().find("string[@name='cs75plus_app_name']")
if branding is None or branding.text!='diplay-cs75plus' or branding.get('translatable')!='false':
    failures.append('Missing stable CS75PLUS brand resource')
for variant_manifest in (ROOT/'mobile/src').glob('*/AndroidManifest.xml'):
    if variant_manifest.parent.name=='main':
        continue
    variant_app=ET.parse(variant_manifest).getroot().find('application')
    if variant_app is not None and variant_app.get(android+'label') not in (None, '@string/cs75plus_app_name'):
        failures.append(str(variant_manifest.relative_to(ROOT))+': overrides the CS75PLUS application label')
manifest=ET.parse(ROOT/'customizations/changan-hud/src/main/AndroidManifest.xml').getroot()
provider=manifest.find('application/provider')
if provider is None or provider.get(android+'exported')!='false' or provider.get(android+'name')!='com.freddyq.diplay.changan.ChanganCustomizationInitializer' or provider.get(android+'authorities')!='${applicationId}.customizations.changan':
    failures.append('Missing private Changan module initializer provider')
projection_manifest=ET.parse(ROOT/'customizations/changan-projection/src/main/AndroidManifest.xml').getroot()
projection_provider=projection_manifest.find('application/provider')
if projection_provider is None or projection_provider.get(android+'exported')!='false' or projection_provider.get(android+'name')!='com.freddyq.diplay.changan.projection.ChanganProjectionInitializer' or projection_provider.get(android+'authorities')!='${applicationId}.customizations.changan-projection':
    failures.append('Missing private Changan projection initializer provider')
for module in ['shared','common']:
    for path in (ROOT/module/'src/main').rglob('*.kt'):
        if 'ChanganHudBridge' in path.read_text(encoding='utf-8'):
            failures.append(str(path.relative_to(ROOT))+': vendor implementation leaked into core')
        if 'ChanganProjectionBridge' in path.read_text(encoding='utf-8'):
            failures.append(str(path.relative_to(ROOT))+': projection policy leaked into core')
for path in (ROOT/'customizations/changan-hud/src/main/java').rglob('*.kt'):
    if 'com.shilapi.xcertplay.hud.' in path.read_text(encoding='utf-8'):
        failures.append(str(path.relative_to(ROOT))+': bypassed upstream navigation adapter')
for path in (ROOT/'customizations/changan-projection/src/main/java').rglob('*.kt'):
    if any(x in path.read_text(encoding='utf-8') for x in ['android.os.Binder','android.car.','com.shilapi.xcertplay.']):
        failures.append(str(path.relative_to(ROOT))+': projection policy bypassed generic control endpoint')
if failures:
    raise SystemExit('Customization integration needs review:\n'+'\n'.join(failures))
print('Customization integration check passed.')
