import fs from 'fs';
import path from 'path';

import { notificationServiceOptions } from '../src/options';
import {
  applyEasExtension,
  applyEntitlements,
  applyInfoPlist,
  applyMinSdk,
  catalogMinSdk,
  manifestMetadata,
  podfileDefaultTarget,
  raisedDeploymentTarget,
  versionAtLeast,
} from '../src/settings';

describe('versionAtLeast', () => {
  it('compares dotted versions', () => {
    expect(versionAtLeast('16.4', '16.0')).toBe(true);
    expect(versionAtLeast('16', '16.0')).toBe(true);
    expect(versionAtLeast('15.1', '16.0')).toBe(false);
    expect(versionAtLeast('17.0.1', '17.0')).toBe(true);
    expect(versionAtLeast(undefined, '16.0')).toBe(false);
  });
});

describe('iOS deployment target', () => {
  const podfile = fs.readFileSync(path.join(__dirname, 'fixtures', 'Podfile'), 'utf8');

  it("reads the generated Podfile's fallback", () => {
    expect(podfileDefaultTarget(podfile)).toBe('16.4');
  });

  it('leaves a target of iOS 16 or later alone, including the Podfile default', () => {
    expect(raisedDeploymentTarget(undefined, '16.4')).toBeUndefined();
    expect(raisedDeploymentTarget('17.0', undefined)).toBeUndefined();
  });

  it('raises a lower target to iOS 16', () => {
    expect(raisedDeploymentTarget('15.1', '16.4')).toBe('16.0');
    expect(raisedDeploymentTarget(undefined, '15.1')).toBe('16.0');
    expect(raisedDeploymentTarget(undefined, undefined)).toBe('16.0');
  });
});

describe('Android minSdk', () => {
  it("reads React Native's default", () => {
    expect(catalogMinSdk('[versions]\nminSdk = "24"\ncompileSdk = "37"\n')).toBe(24);
    expect(catalogMinSdk('[versions]\n')).toBeUndefined();
  });

  it('adds 26 when the default is lower', () => {
    expect(applyMinSdk([], 24)).toEqual([{ type: 'property', key: 'android.minSdkVersion', value: '26' }]);
    expect(applyMinSdk([], undefined)).toHaveLength(1);
  });

  it('never lowers it', () => {
    expect(applyMinSdk([], 28)).toEqual([]);
    const props = [{ type: 'property' as const, key: 'android.minSdkVersion', value: '29' }];
    expect(applyMinSdk(props, 24)[0]).toEqual({ type: 'property', key: 'android.minSdkVersion', value: '29' });
  });

  it('raises a lower value the app set', () => {
    const props = [{ type: 'property' as const, key: 'android.minSdkVersion', value: '23' }];
    expect(applyMinSdk(props, 24)[0]).toMatchObject({ value: '26' });
  });
});

describe('Info.plist and entitlements', () => {
  it('writes the App ID and server for the iOS module, preferring the iOS App ID', () => {
    expect(applyInfoPlist({}, { appId: 'app_1', baseUrl: 'https://hb.example.com' })).toEqual({
      HailbellAppId: 'app_1',
      HailbellBaseURL: 'https://hb.example.com',
    });
    expect(applyInfoPlist({}, { appId: 'app_1', ios: { appId: 'app_ios' } })).toEqual({ HailbellAppId: 'app_ios' });
  });

  it('removes keys left over from an earlier configuration', () => {
    expect(applyInfoPlist({ HailbellAppId: 'old', HailbellBaseURL: 'old', Other: 1 }, {})).toEqual({ Other: 1 });
  });

  it('adds the push capability unless turned off or already there', () => {
    expect(applyEntitlements({}, {}, null)).toEqual({ 'aps-environment': 'development' });
    expect(applyEntitlements({ 'aps-environment': 'production' }, {}, null)).toEqual({ 'aps-environment': 'production' });
    expect(applyEntitlements({}, { ios: { pushNotifications: false } }, null)).toEqual({});
  });

  it("adds the extension's App Group once", () => {
    const extension = { targetName: 'X', bundleIdentifier: 'b', appGroup: 'group.com.example.app' };
    const entitlements = applyEntitlements({ 'com.apple.security.application-groups': ['group.other'] }, {}, extension);
    applyEntitlements(entitlements, {}, extension);
    expect(entitlements['com.apple.security.application-groups']).toEqual(['group.other', 'group.com.example.app']);
  });
});

describe('Android manifest meta-data', () => {
  it('sets the App ID, server and offers channel, preferring the Android App ID', () => {
    expect(manifestMetadata({ appId: 'app_1', android: { appId: 'app_android', offersChannel: 'promotions' } })).toEqual([
      ['com.hailbell.APP_ID', 'app_android'],
      ['com.hailbell.BASE_URL', null],
      ['com.hailbell.OFFERS_CHANNEL', 'promotions'],
    ]);
  });
});

describe('notification service extension options', () => {
  it('is off unless asked for', () => {
    expect(notificationServiceOptions({}, 'com.example.app')).toBeNull();
    expect(notificationServiceOptions({ ios: { notificationService: false } }, 'com.example.app')).toBeNull();
  });

  it('defaults the target name and bundle ID', () => {
    expect(notificationServiceOptions({ ios: { notificationService: true } }, 'com.example.app')).toEqual({
      targetName: 'HailbellNotificationExtension',
      bundleIdentifier: 'com.example.app.NotificationService',
      appGroup: undefined,
    });
  });

  it('takes a custom target, bundle ID and App Group', () => {
    const options = { ios: { notificationService: { targetName: 'Rich', bundleIdentifier: 'com.x.rich', appGroup: 'group.x' } } };
    expect(notificationServiceOptions(options, 'com.example.app')).toEqual({
      targetName: 'Rich',
      bundleIdentifier: 'com.x.rich',
      appGroup: 'group.x',
    });
  });

  it('refuses a target name Xcode cannot use, and a missing bundle ID', () => {
    expect(() => notificationServiceOptions({ ios: { notificationService: { targetName: 'My Ext' } } }, 'com.x')).toThrow();
    expect(() => notificationServiceOptions({ ios: { notificationService: true } }, undefined)).toThrow(/bundleIdentifier/);
  });

  it('registers the extension with EAS once', () => {
    const extension = { targetName: 'HailbellNotificationExtension', bundleIdentifier: 'com.x.NotificationService', appGroup: 'group.x' };
    const extra = applyEasExtension(applyEasExtension({ other: true }, extension), extension);
    expect(extra.other).toBe(true);
    expect(extra.eas.build.experimental.ios.appExtensions).toEqual([
      {
        targetName: 'HailbellNotificationExtension',
        bundleIdentifier: 'com.x.NotificationService',
        entitlements: { 'com.apple.security.application-groups': ['group.x'] },
      },
    ]);
  });
});
