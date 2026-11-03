// The plugin's decisions as plain functions on plain data, so they can be tested without a project.
import type { HailbellPluginOptions, NotificationServiceOptions } from './options';

/** The native SDKs' minimums: iOS 16 and Android 8 (API 26). */
export const IOS_DEPLOYMENT_TARGET = '16.0';
export const ANDROID_MIN_SDK = 26;

export const META = {
  appId: 'com.hailbell.APP_ID',
  baseUrl: 'com.hailbell.BASE_URL',
  offersChannel: 'com.hailbell.OFFERS_CHANNEL',
} as const;

/** True when version `value` is at least `minimum` ("16.4" >= "16.0"). */
export function versionAtLeast(value: string | undefined, minimum: string): boolean {
  if (!value) return false;
  const a = value.split('.').map(Number);
  const b = minimum.split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] ?? 0;
    const y = b[i] ?? 0;
    if (x !== y) return x > y;
  }
  return true;
}

/** Info.plist keys the iOS module reads at launch. Keys for options that are off are removed. */
export function applyInfoPlist(plist: Record<string, unknown>, options: HailbellPluginOptions) {
  const appId = options.ios?.appId ?? options.appId;
  if (appId) plist.HailbellAppId = appId;
  else delete plist.HailbellAppId;
  if (options.baseUrl) plist.HailbellBaseURL = options.baseUrl;
  else delete plist.HailbellBaseURL;
  return plist;
}

/** The Push Notifications capability, and the App Group the extension shares for badge counts. */
export function applyEntitlements(
  entitlements: Record<string, unknown>,
  options: HailbellPluginOptions,
  extension: NotificationServiceOptions | null,
) {
  if (options.ios?.pushNotifications !== false && !entitlements['aps-environment']) {
    // Xcode and EAS switch this to production for App Store and TestFlight builds.
    entitlements['aps-environment'] = 'development';
  }
  if (extension?.appGroup) {
    const existing = entitlements['com.apple.security.application-groups'];
    const groups = Array.isArray(existing) ? existing.filter((g): g is string => typeof g === 'string') : [];
    if (!groups.includes(extension.appGroup)) groups.push(extension.appGroup);
    entitlements['com.apple.security.application-groups'] = groups;
  }
  return entitlements;
}

/**
 * The deployment target for Podfile.properties.json: the current one, or the Podfile's fallback when
 * there is none, raised to iOS 16 if lower. Returns undefined when nothing needs to change.
 */
export function raisedDeploymentTarget(current: string | undefined, podfileDefault: string | undefined): string | undefined {
  return versionAtLeast(current ?? podfileDefault, IOS_DEPLOYMENT_TARGET) ? undefined : IOS_DEPLOYMENT_TARGET;
}

/** The fallback in the generated Podfile: `podfile_properties['ios.deploymentTarget'] || '16.4'`. */
export function podfileDefaultTarget(podfile: string): string | undefined {
  return podfile.match(/podfile_properties\['ios\.deploymentTarget'\]\s*\|\|\s*'([\d.]+)'/)?.[1];
}

/** React Native's default minSdk, from the `minSdk = "24"` line of its version catalog. */
export function catalogMinSdk(catalog: string): number | undefined {
  const match = catalog.match(/^minSdk\s*=\s*"(\d+)"/m);
  return match ? Number(match[1]) : undefined;
}

type GradleProperty = { type: 'property'; key: string; value: string } | { type: 'comment'; value: string } | { type: 'empty' };

/** Raises `android.minSdkVersion` to 26 when the app's, or React Native's default, is lower. Never lowers it. */
export function applyMinSdk(properties: GradleProperty[], reactNativeDefault: number | undefined) {
  const key = 'android.minSdkVersion';
  const item = properties.find((p): p is Extract<GradleProperty, { type: 'property' }> => p.type === 'property' && p.key === key);
  if (item) {
    if (Number(item.value) < ANDROID_MIN_SDK) item.value = String(ANDROID_MIN_SDK);
  } else if ((reactNativeDefault ?? 0) < ANDROID_MIN_SDK) {
    properties.push({ type: 'property', key, value: String(ANDROID_MIN_SDK) });
  }
  return properties;
}

/** The manifest meta-data the Android module reads in Application.onCreate, as [name, value or null to remove]. */
export function manifestMetadata(options: HailbellPluginOptions): [string, string | null][] {
  return [
    [META.appId, options.android?.appId ?? options.appId ?? null],
    [META.baseUrl, options.baseUrl ?? null],
    [META.offersChannel, options.android?.offersChannel ?? null],
  ];
}

type AppExtension = { targetName: string; bundleIdentifier: string; entitlements?: Record<string, unknown> };

/** Tells EAS Build about the extension, so it creates and signs its bundle ID too. */
export function applyEasExtension(extra: Record<string, any>, extension: NotificationServiceOptions) {
  const eas = (extra.eas ??= {});
  const build = (eas.build ??= {});
  const experimental = (build.experimental ??= {});
  const ios = (experimental.ios ??= {});
  const extensions: AppExtension[] = (ios.appExtensions ??= []);
  if (!extensions.some((e) => e.targetName === extension.targetName)) {
    extensions.push({
      targetName: extension.targetName,
      bundleIdentifier: extension.bundleIdentifier,
      ...(extension.appGroup ? { entitlements: { 'com.apple.security.application-groups': [extension.appGroup] } } : {}),
    });
  }
  return extra;
}
