#!/usr/bin/env python3
"""Offline structural checks, NOT a Swift compiler or an iOS runtime test.

Uses Python's standard library only; may run on Windows, macOS or Linux.
Checks the actual OpenStep project graph, plists, asset metadata and scheme.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import plistlib
import re
import struct
import sys
import xml.etree.ElementTree as ET


TOKEN = re.compile(r'\s+|//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}()=;,]|[^\s{}()=;,"]+', re.S)


def code_for_privacy_scan(text: str) -> str:
    """Avoid reporting documentation and ordinary string literals as API use.

    This is only a conservative static privacy lint, not a complete Swift lexer.
    """
    return re.sub(r'""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', ' ', text, flags=re.S)


def parse_openstep(text: str) -> dict:
    """Parse the subset of OpenStep ASCII plists emitted in this project."""
    tokens = []
    cursor = 0
    for match in TOKEN.finditer(text):
        if match.start() != cursor:
            raise ValueError(f"Unrecognized project syntax at {cursor}")
        cursor = match.end()
        token = match.group()
        if token.isspace() or token.startswith('//') or token.startswith('/*'):
            continue
        tokens.append(token)
    if cursor != len(text):
        raise ValueError("Unrecognized trailing syntax")
    position = 0

    def consume(expected=None):
        nonlocal position
        if position >= len(tokens):
            raise ValueError(f"Unexpected end; expected {expected}")
        token = tokens[position]
        position += 1
        if expected is not None and token != expected:
            raise ValueError(f"Expected {expected}, got {token}")
        return token

    def value():
        token = consume()
        if token == '{':
            output = {}
            while position < len(tokens) and tokens[position] != '}':
                key = consume()
                if key.startswith('"'):
                    key = json.loads(key)
                consume('=')
                if key in output:
                    raise ValueError(f"Duplicate key {key}")
                output[key] = value()
                consume(';')
            consume('}')
            return output
        if token == '(':
            output = []
            while position < len(tokens) and tokens[position] != ')':
                output.append(value())
                if position < len(tokens) and tokens[position] == ',':
                    consume(',')
                elif position < len(tokens) and tokens[position] != ')':
                    raise ValueError('Expected array comma')
            consume(')')
            return output
        if token.startswith('"'):
            return json.loads(token)
        if token in ('}', ')', '=', ';', ','):
            raise ValueError(f"Unexpected punctuation {token}")
        return token

    result = value()
    if position != len(tokens) or not isinstance(result, dict):
        raise ValueError('Expected one complete root dictionary')
    return result


def png_metadata(path: Path) -> tuple[int, int, int]:
    blob = path.read_bytes()
    if blob[:8] != b'\x89PNG\r\n\x1a\n' or blob[12:16] != b'IHDR':
        raise ValueError('Not a PNG with an IHDR header')
    width, height, depth, color = struct.unpack('>IIBB', blob[16:26])
    if depth != 8:
        raise ValueError('App icon must use 8-bit channels')
    return width, height, color


def validate(root: Path) -> tuple[list[str], list[str]]:
    passed, failed = [], []

    def check(condition, message):
        (passed if condition else failed).append(message)

    project = parse_openstep((root / 'DreamShield.xcodeproj/project.pbxproj').read_text(encoding='utf-8'))
    objects = project['objects']
    check(project['objectVersion'] == '77', 'Xcode 16+ project format')
    check(all(re.fullmatch('[A-F0-9]{24}', key) for key in objects), 'All project object IDs are 24 hexadecimal characters')
    check(len(objects) == len(set(objects)), 'Unique project object IDs')
    check(project['rootObject'] in objects, 'Project root is present')
    missing_references = set()

    def scan(node):
        if isinstance(node, dict):
            for item in node.values():
                scan(item)
        elif isinstance(node, list):
            for item in node:
                scan(item)
        elif isinstance(node, str) and re.fullmatch('[A-F0-9]{24}', node) and node not in objects:
            missing_references.add(node)

    scan(project)
    check(not missing_references, 'All project object references resolve')
    targets = {entry['name']: entry for entry in objects.values() if entry.get('isa') == 'PBXNativeTarget'}
    check(set(targets) == {'DreamShield', 'DreamShieldTests'}, 'App and hosted XCTest targets exist')
    for name, folder in [('DreamShield', 'Sources'), ('DreamShieldTests', 'Tests')]:
        target = targets[name]
        groups = [objects[key] for key in target['fileSystemSynchronizedGroups']]
        check(any(group.get('path') == folder and group['isa'] == 'PBXFileSystemSynchronizedRootGroup' for group in groups), f'{name} synchronizes only its intended source folder')
        configuration_ids = objects[target['buildConfigurationList']]['buildConfigurations']
        check({objects[key]['name'] for key in configuration_ids} == {'Debug', 'Release'}, f'{name} has Debug and Release configurations')
        for key in configuration_ids:
            configuration = objects[key]
            settings = configuration['buildSettings']
            label = f"{name} / {configuration['name']}"
            check(settings.get('DEVELOPMENT_TEAM') == '', label + ' has no embedded signing identity')
            check(settings.get('TARGETED_DEVICE_FAMILY') == '1,2', label + ' supports iPhone and iPad')
            check(settings.get('SWIFT_VERSION') == '5.0', label + ' uses explicit Swift 5 language mode')
            check(settings.get('MARKETING_VERSION') == '0.1.1' and settings.get('CURRENT_PROJECT_VERSION') == '2', label + ' version is 0.1.1 (2)')
        if name == 'DreamShieldTests':
            check(len(target['dependencies']) == 1 and objects[target['dependencies'][0]]['target'] == 'A10000000000000000000001', 'XCTest target depends on app target')
    info = plistlib.loads((root / 'Info.plist').read_bytes())
    check(info.get('CFBundleDisplayName') == '梦盾助手', 'Chinese app display name')
    check(bool(info.get('NSLocalNetworkUsageDescription')), 'Local-network purpose text present')
    check(bool(info.get('NSPhotoLibraryAddUsageDescription')), 'Add-only photo-library purpose text present')
    forbidden = ('NSPhotoLibraryUsageDescription', 'NSCameraUsageDescription', 'NSMicrophoneUsageDescription', 'NSLocationWhenInUseUsageDescription', 'NSLocationAlwaysAndWhenInUseUsageDescription', 'UIBackgroundModes')
    check(not any(key in info for key in forbidden), 'No camera, microphone, location, full photo access or background entitlement declarations')
    check(info.get('NSAppTransportSecurity') == {'NSAllowsLocalNetworking': True}, 'ATS exception limited to local networking; no arbitrary loads')
    check(not info.get('UIRequiresFullScreen', False), 'iPad fullscreen is not forced')
    check(info.get('UIApplicationSceneManifest', {}).get('UIApplicationSupportsMultipleScenes') is False, 'Single app window matches the shared session lifecycle; iPad multitasking remains available')
    check(len(info.get('UISupportedInterfaceOrientations~ipad', [])) == 4, 'iPad supports all four orientations')
    privacy = plistlib.loads((root / 'Resources/PrivacyInfo.xcprivacy').read_bytes())
    check(privacy.get('NSPrivacyTracking') is False and privacy.get('NSPrivacyTrackingDomains') == [], 'Privacy manifest declares no tracking')
    check(privacy.get('NSPrivacyCollectedDataTypes') == [], 'Privacy manifest has no developer-collected data types')
    resource_phases = [objects[key] for key in targets['DreamShield']['buildPhases'] if objects[key]['isa'] == 'PBXResourcesBuildPhase']
    resource_paths = [objects[objects[key]['fileRef']]['path'] for phase in resource_phases for key in phase['files']]
    check(set(resource_paths) == {'Assets.xcassets', 'PrivacyInfo.xcprivacy'}, 'Only approved resources are explicitly bundled')
    for relative in ['Resources/Assets.xcassets/Contents.json', 'Resources/Assets.xcassets/AccentColor.colorset/Contents.json', 'Resources/Assets.xcassets/AppIcon.appiconset/Contents.json']:
        json.loads((root / relative).read_text(encoding='utf-8'))
        check(True, relative + ' is valid JSON')
    icon = root / 'Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'
    width, height, color = png_metadata(icon)
    check(width == height == 1024 and color == 2, 'App icon is opaque 1024 x 1024 RGB PNG')
    scheme = ET.parse(root / 'DreamShield.xcodeproj/xcshareddata/xcschemes/DreamShield.xcscheme').getroot()
    check(scheme.find(".//TestableReference/BuildableReference[@BlueprintName='DreamShieldTests']") is not None, 'Shared scheme includes XCTest target')
    test_action = scheme.find('TestAction')
    test_mode = scheme.find("TestAction/EnvironmentVariables/EnvironmentVariable[@key='DREAMSHIELD_TEST_MODE']")
    check(test_action is not None and test_action.attrib.get('shouldUseLaunchSchemeArgsEnv') == 'NO'
          and test_mode is not None and test_mode.attrib.get('value') == '1' and test_mode.attrib.get('isEnabled') == 'YES',
          'XCTest host uses isolated test-mode environment instead of normal launch credentials')
    check(scheme.find("LaunchAction/EnvironmentVariables/EnvironmentVariable[@key='DREAMSHIELD_TEST_MODE']") is None,
          'Normal app launch does not enable test mode')
    for reference in scheme.findall('.//BuildableReference'):
        check(reference.attrib['BlueprintIdentifier'] in objects, 'Scheme references an existing target')
    source_files = sorted((root / 'Sources').rglob('*.swift'))
    test_files = sorted((root / 'Tests').rglob('*.swift'))
    check((root / 'Sources/DreamShieldApp.swift').is_file(), 'SwiftUI app entry file exists')
    check(bool(source_files), 'Swift source files are present')
    check(bool(test_files), 'XCTest source files are present (not executed by this validator)')
    if source_files:
        swift = '\n'.join(file.read_text(encoding='utf-8') for file in source_files)
        check(len(re.findall(r'(?m)^\s*@main\b', swift)) == 1, 'Exactly one Swift @main entry point')
        uses_defaults = bool(re.search(r'\b(?:UserDefaults|AppStorage|SceneStorage)\b', code_for_privacy_scan(swift)))
        has_reason = any(item.get('NSPrivacyAccessedAPIType') == 'NSPrivacyAccessedAPICategoryUserDefaults' for item in privacy.get('NSPrivacyAccessedAPITypes', []))
        check(not uses_defaults or has_reason, 'UserDefaults use (if added) is covered by a required-reason declaration')
        check('NSAllowsArbitraryLoads' not in swift, 'Source does not manufacture an arbitrary ATS exemption')
    unexpected = [file.relative_to(root).as_posix() for folder in ('Sources', 'Resources', 'Tests') for file in (root / folder).rglob('*') if file.is_file() and file.suffix.lower() in {'.p12', '.p8', '.pem', '.key', '.mobileprovision', '.env'}]
    check(not unexpected, 'No signing secrets in bundled/source/test folders')
    return passed, failed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    options = parser.parse_args()
    try:
        passed, failed = validate(options.root.resolve())
    except (OSError, KeyError, ValueError, ET.ParseError, plistlib.InvalidFileException) as exc:
        print(f'STATIC CHECK ERROR: {exc}', file=sys.stderr)
        return 1
    for message in passed:
        print(f'PASS {message}')
    for message in failed:
        print(f'FAIL {message}', file=sys.stderr)
    print(f'\nStatic checks: {len(passed)} passed, {len(failed)} failed.')
    print('These are structural checks only. Swift compilation, XCTest and device behavior require Xcode on macOS.')
    return bool(failed)


if __name__ == '__main__':
    raise SystemExit(main())
