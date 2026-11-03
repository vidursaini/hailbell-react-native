import { NativeModule, requireOptionalNativeModule } from 'expo';

/** A tap as the native side reports it. */
export type NativeTap =
  | { kind: 'chat' }
  | { kind: 'duplicate' }
  | {
      kind: 'campaign';
      id: string;
      url?: string;
      imageUrl?: string;
      actionId?: string;
      data?: Record<string, unknown>;
      buttons?: { id: string; text: string; url?: string }[];
    };

export type NativeEvents = {
  onUnreadCountChange: (event: { count: number }) => void;
  onPaywall: (event: { placement: string; requestId: string }) => void;
  onFirebaseTokenRequest: (event: { requestId: string }) => void;
  onPushOpen: (event: NativeTap) => void;
};

export declare class HailbellNativeModule extends NativeModule<NativeEvents> {
  configure(appId: string, baseUrl: string | null): void;
  open(): void;
  close(): void;
  identify(userId: string | null, name: string | null, email: string | null, identityToken: string | null): void;
  identifyWithFirebase(name: string | null, email: string | null): void;
  provideFirebaseToken(requestId: string, token: string | null): void;
  setMetadata(metadata: Record<string, string>): void;
  track(event: string, properties: Record<string, string> | null): void;
  setPaywallPlacements(placements: string[]): void;
  setPaywallHandler(enabled: boolean): void;
  paywallPurchased(requestId: string): void;
  /** False when the token can't be the platform's (an FCM token on iOS, an APNs token on Android). */
  setPushToken(token: string, sandbox: boolean | null): boolean;
  reset(): void;
  deleteData(): Promise<void>;
  getUnreadCount(): number;
  /** False when the image couldn't be loaded. */
  setTeamPhoto(uri: string | null): Promise<boolean>;
  setAsksForNotifications(asks: boolean): void;
  setDarkMode(dark: boolean | null): void;

  requestPushPermission(provisional: boolean): Promise<boolean>;
  canNotify(): Promise<boolean>;
  setTags(set: Record<string, string>, remove: string[]): void;
  setMarketing(enabled: boolean): void;
  optOut(): void;
  setOffersChannel(channel: string): void;
  clearBadge(appGroup: string | null): void;
  setPushOpenHandler(enabled: boolean): void;
  handleMessage(data: Record<string, string>, title: string | null, body: string | null): Promise<boolean>;
  handleTap(userInfo: Record<string, unknown>, actionId: string | null): Promise<NativeTap | null>;
}

let warned = false;

/**
 * The native module, or null when the app was built without it: Expo Go, web, or a build from before
 * the package was added. Every call then does nothing, after one warning.
 */
export function getNative(): HailbellNativeModule | null {
  const native = requireOptionalNativeModule<HailbellNativeModule>('Hailbell');
  if (!native && !warned) {
    warned = true;
    console.warn(
      "[Hailbell] The native module isn't in this build, so Hailbell does nothing. Expo Go can't load it: " +
        'make a development build (npx expo run:ios / run:android, or EAS Build) after adding @hailbell/react-native.',
    );
  }
  return native;
}

/** For tests: show the missing-module warning again. */
export function resetWarningForTests() {
  warned = false;
}
