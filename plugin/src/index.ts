import fs from 'fs';
import path from 'path';

import {
  AndroidConfig,
  createRunOncePlugin,
  withAndroidManifest,
  withDangerousMod,
  withEntitlementsPlist,
  withGradleProperties,
  withInfoPlist,
  withPodfileProperties,
  withXcodeProject,
  type ConfigPlugin,
} from 'expo/config-plugins';

import { addExtensionTarget, extensionFiles } from './notificationService';
import { notificationServiceOptions, type HailbellPluginOptions } from './options';
import {
  IOS_DEPLOYMENT_TARGET,
  applyEasExtension,
  applyEntitlements,
  applyInfoPlist,
  applyMinSdk,
  catalogMinSdk,
  manifestMetadata,
  podfileDefaultTarget,
  raisedDeploymentTarget,
  versionAtLeast,
} from './settings';

export type { HailbellPluginOptions } from './options';

const pkg = require('../../package.json');

function readFile(file: string): string | undefined {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return undefined;
  }
}

/** The app target's deployment target, at least iOS 16, for the extension to match. */
// The `xcode` package's project object has no type definitions.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function appDeploymentTarget(project: any): string {
  let best = IOS_DEPLOYMENT_TARGET;
  const configurations = project.pbxXCBuildConfigurationSection();
  for (const key of Object.keys(configurations)) {
    const settings = configurations[key]?.buildSettings;
    if (!settings?.IPHONEOS_DEPLOYMENT_TARGET || !settings.PRODUCT_BUNDLE_IDENTIFIER) continue;
    const value = String(settings.IPHONEOS_DEPLOYMENT_TARGET).replace(/"/g, '');
    if (versionAtLeast(value, best)) best = value;
  }
  return best;
}

const withHailbellIos: ConfigPlugin<HailbellPluginOptions> = (config, options) => {
  const extension = notificationServiceOptions(options, config.ios?.bundleIdentifier);

  config = withInfoPlist(config, (c) => {
    applyInfoPlist(c.modResults, options);
    return c;
  });

  config = withEntitlementsPlist(config, (c) => {
    applyEntitlements(c.modResults, options, extension);
    return c;
  });

  // The Swift SDK needs iOS 16. Raise the app's deployment target when it is lower; never lower it.
  config = withPodfileProperties(config, (c) => {
    const podfile = readFile(path.join(c.modRequest.platformProjectRoot, 'Podfile'));
    const raised = raisedDeploymentTarget(c.modResults['ios.deploymentTarget'], podfile && podfileDefaultTarget(podfile));
    if (raised) c.modResults['ios.deploymentTarget'] = raised;
    return c;
  });
  config = withXcodeProject(config, (c) => {
    const configurations = c.modResults.pbxXCBuildConfigurationSection();
    for (const key of Object.keys(configurations)) {
      const settings = configurations[key]?.buildSettings;
      const current = settings?.IPHONEOS_DEPLOYMENT_TARGET;
      if (current && !versionAtLeast(String(current).replace(/"/g, ''), IOS_DEPLOYMENT_TARGET)) {
        settings.IPHONEOS_DEPLOYMENT_TARGET = IOS_DEPLOYMENT_TARGET;
      }
    }
    return c;
  });

  if (!extension) return config;

  const version = config.version ?? '1.0.0';
  const buildNumber = config.ios?.buildNumber ?? '1';

  config = withDangerousMod(config, [
    'ios',
    async (c) => {
      const folder = path.join(c.modRequest.platformProjectRoot, extension.targetName);
      fs.mkdirSync(folder, { recursive: true });
      for (const [name, contents] of extensionFiles(extension, { version, buildNumber, baseUrl: options.baseUrl })) {
        fs.writeFileSync(path.join(folder, name), contents);
      }
      return c;
    },
  ]);

  config = withXcodeProject(config, (c) => {
    addExtensionTarget(c.modResults, extension, {
      deploymentTarget: appDeploymentTarget(c.modResults),
      developmentTeam: c.ios?.appleTeamId,
      version,
      buildNumber,
    });
    return c;
  });

  config.extra = applyEasExtension(config.extra ?? {}, extension);
  return config;
};

const withHailbellAndroid: ConfigPlugin<HailbellPluginOptions> = (config, options) => {
  config = withAndroidManifest(config, (c) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(c.modResults);
    for (const [name, value] of manifestMetadata(options)) {
      if (value) AndroidConfig.Manifest.addMetaDataItemToMainApplication(app, name, value);
      else AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(app, name);
    }
    return c;
  });

  // The Android SDK needs API 26. Raise the app's minSdk when it is lower; never lower it.
  config = withGradleProperties(config, (c) => {
    let catalog: string | undefined;
    try {
      const rn = path.dirname(require.resolve('react-native/package.json', { paths: [c.modRequest.projectRoot] }));
      catalog = readFile(path.join(rn, 'gradle', 'libs.versions.toml'));
    } catch {
      catalog = undefined;
    }
    applyMinSdk(c.modResults, catalog ? catalogMinSdk(catalog) : undefined);
    return c;
  });

  return config;
};

const withHailbell: ConfigPlugin<HailbellPluginOptions | void> = (config, options) => {
  const o = options ?? {};
  config = withHailbellIos(config, o);
  config = withHailbellAndroid(config, o);
  return config;
};

export default createRunOncePlugin(withHailbell, pkg.name, pkg.version) as ConfigPlugin<HailbellPluginOptions | void>;
