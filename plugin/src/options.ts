/** Options for the `@hailbell/react-native` config plugin in app.json / app.config.ts. */
export interface HailbellPluginOptions {
  /**
   * The App ID from the dashboard. With it, the native side configures Hailbell at launch, before
   * any JS runs, as the native SDKs recommend. Use a different App ID per platform with `ios.appId`
   * and `android.appId`.
   */
  appId?: string;
  /** Another Hailbell server. Defaults to `https://api.hailbell.com`. */
  baseUrl?: string;
  ios?: {
    /** The iOS platform's App ID, if it differs from `appId`. */
    appId?: string;
    /**
     * Adds the Push Notifications capability (`aps-environment`). Default true. Turn off only if the
     * app gets it some other way.
     */
    pushNotifications?: boolean;
    /**
     * Adds a Notification Service Extension running `HailbellNotificationService`, for images,
     * buttons, badge increments and delivery receipts on Hailbell campaign pushes. Chat replies work
     * without it. An app can only have one notification service extension.
     */
    notificationService?:
      | boolean
      | {
          /** An App Group shared by the app and the extension, for badge increments. */
          appGroup?: string;
          /** The extension's target name. Default `HailbellNotificationExtension`. */
          targetName?: string;
          /** The extension's bundle ID. Default: the app's bundle ID plus `.NotificationService`. */
          bundleIdentifier?: string;
        };
  };
  android?: {
    /** The Android platform's App ID, if it differs from `appId`. */
    appId?: string;
    /**
     * The app's own channel for campaign pushes, when it already has one people know. Enter the same
     * ID as "Android: offers channel ID" in the dashboard's push settings.
     */
    offersChannel?: string;
  };
}

export interface NotificationServiceOptions {
  targetName: string;
  bundleIdentifier: string;
  appGroup?: string;
}

export const DEFAULT_TARGET = 'HailbellNotificationExtension';

/** The extension's settings, or null when it is off. */
export function notificationServiceOptions(
  options: HailbellPluginOptions,
  appBundleIdentifier: string | undefined,
): NotificationServiceOptions | null {
  const setting = options.ios?.notificationService;
  if (!setting) return null;
  const custom = typeof setting === 'object' ? setting : {};
  const targetName = custom.targetName || DEFAULT_TARGET;
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(targetName)) {
    throw new Error(`@hailbell/react-native: ios.notificationService.targetName "${targetName}" must be letters, digits and _.`);
  }
  const bundleIdentifier = custom.bundleIdentifier || (appBundleIdentifier ? `${appBundleIdentifier}.NotificationService` : '');
  if (!bundleIdentifier) {
    throw new Error('@hailbell/react-native: set ios.bundleIdentifier in the app config to add the notification service extension.');
  }
  return { targetName, bundleIdentifier, appGroup: custom.appGroup };
}
