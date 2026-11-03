import fs from 'fs';
import path from 'path';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const xcode = require('xcode');

import { addExtensionTarget, extensionFiles, hasTarget, infoPlist, serviceSource } from '../src/notificationService';

const fixture = path.join(__dirname, 'fixtures', 'expo58.pbxproj');
const options = { targetName: 'HailbellNotificationExtension', bundleIdentifier: 'com.example.app.NotificationService' };
const settings = { deploymentTarget: '16.4', version: '2.1.0', buildNumber: '42', developmentTeam: 'ABCDE12345' };

/** The target object by name, quoted or not. */
function targetNamed(project: any, name: string) {
  const targets = project.pbxNativeTargetSection();
  const key = Object.keys(targets).find((k) => typeof targets[k] === 'object' && String(targets[k].name).replace(/"/g, '') === name);
  return { key: key!, target: key ? targets[key] : null };
}

function load() {
  const project = xcode.project(fixture);
  project.parseSync();
  return project;
}

/** Writes the project and parses it again, as Xcode would read it. */
function roundTrip(project: any) {
  const file = path.join(fs.mkdtempSync(path.join(require('os').tmpdir(), 'hb-')), 'project.pbxproj');
  fs.writeFileSync(file, project.writeSync());
  const again = xcode.project(file);
  again.parseSync();
  return again;
}

describe('addExtensionTarget', () => {
  it('adds an app extension target with its sources and privacy manifest', () => {
    const project = load();
    expect(addExtensionTarget(project, options, settings)).toBe(true);
    const parsed = roundTrip(project);
    const { key, target } = targetNamed(parsed, 'HailbellNotificationExtension');
    expect(target.productType).toBe('"com.apple.product-type.app-extension"');
    const files = (phase: { files: { comment: string }[] }) => phase.files.map((f) => f.comment.replace(/"/g, '')).sort();
    expect(files(parsed.pbxSourcesBuildPhaseObj(key))).toEqual([
      'HailbellNotificationService.swift in Sources',
      'NotificationService.swift in Sources',
    ]);
    expect(files(parsed.pbxResourcesBuildPhaseObj(key))).toEqual(['PrivacyInfo.xcprivacy in Resources']);
  });

  it('embeds the extension in the app, right after its Resources phase', () => {
    const parsed = roundTrip((() => {
      const p = load();
      addExtensionTarget(p, options, settings);
      return p;
    })());
    const app = parsed.getFirstTarget().firstTarget;
    const names = app.buildPhases.map((p: { comment: string }) => p.comment);
    expect(names.indexOf('Embed Foundation Extensions')).toBe(names.indexOf('Resources') + 1);
    expect(names.indexOf('Embed Foundation Extensions')).toBeLessThan(names.indexOf('Bundle React Native code and images'));

    const embedId = app.buildPhases.find((p: { comment: string }) => p.comment === 'Embed Foundation Extensions').value;
    const embed = parsed.hash.project.objects.PBXCopyFilesBuildPhase[embedId];
    expect(embed.dstSubfolderSpec).toBe(13); // PlugIns
    expect(embed.files.map((f: { comment: string }) => f.comment)).toEqual([expect.stringContaining('HailbellNotificationExtension.appex')]);
    expect(app.dependencies).toHaveLength(1);
  });

  it('sets the build settings for both configurations', () => {
    const project = load();
    addExtensionTarget(project, { ...options, appGroup: 'group.com.example.app' }, settings);
    const parsed = roundTrip(project);
    const { target } = targetNamed(parsed, 'HailbellNotificationExtension');
    const list = parsed.pbxXCConfigurationList()[target.buildConfigurationList];
    const configs = list.buildConfigurations.map((c: { value: string }) => parsed.pbxXCBuildConfigurationSection()[c.value]);
    expect(configs.map((c: { name: string }) => c.name).sort()).toEqual(['Debug', 'Release']);
    for (const config of configs) {
      expect(config.buildSettings).toMatchObject({
        INFOPLIST_FILE: 'HailbellNotificationExtension/Info.plist',
        PRODUCT_BUNDLE_IDENTIFIER: '"com.example.app.NotificationService"',
        IPHONEOS_DEPLOYMENT_TARGET: '16.4',
        MARKETING_VERSION: '2.1.0',
        CURRENT_PROJECT_VERSION: 42,
        DEVELOPMENT_TEAM: 'ABCDE12345',
        CODE_SIGN_ENTITLEMENTS: 'HailbellNotificationExtension/HailbellNotificationExtension.entitlements',
        APPLICATION_EXTENSION_API_ONLY: 'YES',
      });
    }
  });

  it('does nothing on a second prebuild', () => {
    const project = load();
    expect(hasTarget(project, 'HailbellNotificationExtension')).toBe(false);
    addExtensionTarget(project, options, settings);
    expect(hasTarget(roundTrip(project), 'HailbellNotificationExtension')).toBe(true);
    const before = project.writeSync();
    expect(addExtensionTarget(project, options, settings)).toBe(false);
    expect(project.writeSync()).toBe(before);
  });

  it('leaves the app target settings alone', () => {
    const project = load();
    const before = JSON.stringify(project.getFirstTarget().firstTarget.buildConfigurationList);
    addExtensionTarget(project, options, settings);
    expect(JSON.stringify(project.getFirstTarget().firstTarget.buildConfigurationList)).toBe(before);
  });
});

describe('extension files', () => {
  it('subclasses the SDK class, with the App Group when there is one', () => {
    expect(serviceSource(options)).toContain('final class NotificationService: HailbellNotificationService {}');
    expect(serviceSource({ ...options, appGroup: 'group.x' })).toContain('override var appGroup: String? { "group.x" }');
  });

  it("writes an Info.plist with the app's version and the service extension point", () => {
    const plist = infoPlist({ version: '2.1.0', buildNumber: '42' });
    expect(plist).toContain('<string>com.apple.usernotifications.service</string>');
    expect(plist).toContain('<string>$(PRODUCT_MODULE_NAME).NotificationService</string>');
    expect(plist).toContain('<string>2.1.0</string>');
    expect(plist).toContain('<string>42</string>');
    expect(plist).not.toContain('HailbellAPI');
    expect(infoPlist({ version: '1', buildNumber: '1', baseUrl: 'https://hb.example.com?a=1&b=2' })).toContain(
      '<string>https://hb.example.com?a=1&amp;b=2</string>',
    );
  });

  it('copies the Swift SDK extension class unchanged', () => {
    const files = Object.fromEntries(extensionFiles(options, { version: '1', buildNumber: '1' }));
    const source = fs.readFileSync(path.join(__dirname, '..', '..', '..', 'swift', 'Sources', 'HailbellNotificationService', 'HailbellNotificationService.swift'));
    expect(Buffer.from(files['HailbellNotificationService.swift'] as Buffer).equals(source)).toBe(true);
    expect(Object.keys(files).sort()).toEqual(['HailbellNotificationService.swift', 'Info.plist', 'NotificationService.swift', 'PrivacyInfo.xcprivacy']);
    const withGroup = Object.fromEntries(extensionFiles({ ...options, appGroup: 'group.x' }, { version: '1', buildNumber: '1' }));
    expect(withGroup['HailbellNotificationExtension.entitlements']).toContain('<string>group.x</string>');
  });
});
