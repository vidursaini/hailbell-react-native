import { Image, Linking, Platform } from 'react-native';

import { HailbellPush } from './HailbellPush';
import { getNative } from './NativeHailbell';
import { isHailbellData, readPayload, toUserInfo } from './payload';
import { deliver } from './taps';
import type {
  ConfigureOptions,
  FirebaseIdentifyOptions,
  IdentifyOptions,
  PaywallHandler,
  PushPayload,
  PushToken,
  Subscription,
  TeamPhoto,
  Value,
} from './types';
import { addUnreadCountListener, getUnreadCount } from './unread';
import { optional, stringMap } from './values';

let paywallHandler: PaywallHandler | null = null;
let paywallSubscription: { remove(): void } | null = null;
let firebaseToken: FirebaseIdentifyOptions['firebaseIdToken'] | null = null;
let tokenSubscription: { remove(): void } | null = null;
let teamPhoto: TeamPhoto = null;
let asksForNotifications = true;
let darkMode: boolean | null = null;
let shakeToReport = false;

function listenForTokenRequests() {
  const native = getNative();
  if (!native || tokenSubscription) return;
  tokenSubscription = native.addListener('onFirebaseTokenRequest', ({ requestId }) => {
    const provider = firebaseToken;
    if (!provider) {
      native.provideFirebaseToken(requestId, null);
      return;
    }
    Promise.resolve()
      .then(provider)
      .then(
        (token) => native.provideFirebaseToken(requestId, typeof token === 'string' && token ? token : null),
        (error) => {
          console.warn('[Hailbell] firebaseIdToken failed; this session starts without it.', error);
          native.provideFirebaseToken(requestId, null);
        },
      );
  });
}

function onPaywallEvent({ placement, requestId }: { placement: string; requestId: string }) {
  const handler = paywallHandler;
  if (!handler) return;
  let done = false;
  const purchased = () => {
    if (done) return;
    done = true;
    getNative()?.paywallPurchased(requestId);
  };
  try {
    handler(placement, purchased);
  } catch (error) {
    console.error('[Hailbell] onPaywall threw', error);
  }
}

function resolvePhoto(photo: TeamPhoto): string | null {
  if (photo === null || photo === undefined) return null;
  if (typeof photo === 'string') return photo;
  const source = Image.resolveAssetSource(photo as Parameters<typeof Image.resolveAssetSource>[0]);
  return source?.uri ?? null;
}

/**
 * In-app support chat. Replies come from Discord, Telegram or the Hailbell dashboard. The chat screen
 * is the native one from the iOS and Android SDKs.
 *
 *     Hailbell.configure({ appId: 'app_...' });
 *     Hailbell.open();
 */
export const Hailbell = {
  /**
   * Call once at launch, before anything else. With the config plugin's `appId`, the native side
   * has already configured at launch and this call does nothing.
   */
  configure(options: ConfigureOptions) {
    if (!options?.appId) {
      console.warn('[Hailbell] configure needs an appId.');
      return;
    }
    getNative()?.configure(options.appId, optional(options.baseUrl));
  },

  /** Opens the chat: a full-height sheet over the app. */
  open() {
    getNative()?.open();
  },

  /** Closes the chat if it is open, for example before showing a paywall drawn in React Native. */
  close() {
    getNative()?.close();
  },

  /**
   * Tells Hailbell who the user is.
   *
   * - `{ userId, name, email, identityToken }`: pass `identityToken` (an HS256 JWT from your backend,
   *   signed with the app's identity secret) to link the chat to the account across devices. Without
   *   it the name and email are shown to you, but the chat stays tied to this install.
   * - `{ name, email, firebaseIdToken }`: for Firebase Auth. Hailbell verifies the ID token, uses the
   *   Firebase uid as the user id, and asks for a fresh token for each session.
   *
   * Call `reset()` on sign-out.
   */
  identify(options: IdentifyOptions | FirebaseIdentifyOptions = {}) {
    const native = getNative();
    if (!native) return;
    if ('firebaseIdToken' in options && typeof options.firebaseIdToken === 'function') {
      firebaseToken = options.firebaseIdToken;
      listenForTokenRequests();
      native.identifyWithFirebase(optional(options.name), optional(options.email));
      return;
    }
    firebaseToken = null;
    const o = options as IdentifyOptions;
    native.identify(optional(o.userId), optional(o.name), optional(o.email), optional(o.identityToken));
  },

  /**
   * Extra context shown next to the conversation, merged with what you sent before:
   * `{ plan: 'pro', superwallUserId }`. `appVersion`, `os` and `locale` are sent automatically.
   */
  setMetadata(metadata: Record<string, Value | null | undefined>) {
    getNative()?.setMetadata(stringMap(metadata));
  },

  /** Records a signal such as `paywall_decline` or `chat_opened`. Signals show next to the chat. */
  track(event: string, properties?: Record<string, Value | null | undefined>) {
    getNative()?.track(event, properties ? stringMap(properties) : null);
  },

  /**
   * The name of the screen the user is on, such as `Settings`. The last 10 are sent with a bug
   * report, so you can see how they got there. Nothing else is done with them.
   */
  trackScreen(name: string) {
    getNative()?.trackScreen(String(name ?? ''));
  },

  /**
   * Opens the bug report screen, for a "Report a problem" button. It takes a screenshot of the
   * current screen first, which the user can mark up or remove before sending. The report lands in
   * their chat, tagged as a bug report, with the app version, device, OS, locale and last screens.
   */
  reportBug() {
    getNative()?.reportBug();
  },

  /** Shaking the device opens the bug report screen. Off by default. */
  get shakeToReport(): boolean {
    return shakeToReport;
  },
  set shakeToReport(enabled: boolean) {
    shakeToReport = Boolean(enabled);
    getNative()?.setShakeToReport(shakeToReport);
  },

  /** The paywall placements an offer can open, so you can pick them by name in the dashboard and Discord. */
  setPaywallPlacements(placements: string[]) {
    getNative()?.setPaywallPlacements(placements);
  },

  /**
   * Opens a paywall when the user taps an offer you sent in the chat. Call `purchased()` if they buy,
   * so you can see which offers worked. The chat stays open: a paywall SDK that presents natively
   * (Superwall, RevenueCat's `presentPaywall`) shows on top of it. Call `Hailbell.close()` first if
   * your paywall is a React screen.
   */
  get onPaywall(): PaywallHandler | null {
    return paywallHandler;
  },
  set onPaywall(handler: PaywallHandler | null) {
    paywallHandler = handler;
    const native = getNative();
    if (!native) return;
    if (handler && !paywallSubscription) {
      paywallSubscription = native.addListener('onPaywall', onPaywallEvent);
    } else if (!handler && paywallSubscription) {
      paywallSubscription.remove();
      paywallSubscription = null;
    }
    native.setPaywallHandler(!!handler);
  },

  /**
   * The push token, at launch and whenever it changes. On iOS pass the APNs device token, on Android
   * the FCM registration token:
   *
   * - expo-notifications: `(await Notifications.getDevicePushTokenAsync())` on both platforms.
   * - React Native Firebase: `messaging().getAPNSToken()` on iOS, `messaging().getToken()` on Android.
   *
   * On iOS, when the app registers for remote notifications, Hailbell also picks the token up by itself.
   * Leave `sandbox` out: it is read from the build's provisioning profile.
   */
  setPushToken(token: PushToken | null | undefined, options: { sandbox?: boolean } = {}) {
    const value = typeof token === 'string' ? token : token?.data;
    if (typeof value !== 'string' || value.length === 0) return;
    if (value.startsWith('ExponentPushToken') || value.startsWith('ExpoPushToken')) {
      console.warn(
        '[Hailbell] setPushToken got an Expo push token. Pass the device token from getDevicePushTokenAsync() instead.',
      );
      return;
    }
    const native = getNative();
    if (native && !native.setPushToken(value, options.sandbox ?? null)) {
      console.warn(
        Platform.OS === 'ios'
          ? '[Hailbell] setPushToken needs the APNs device token on iOS (hex), such as messaging().getAPNSToken(), not the FCM token.'
          : '[Hailbell] setPushToken needs the FCM registration token on Android.',
      );
    }
  },

  /**
   * Call when the user taps a notification your push library reports. Returns true when it was
   * Hailbell's: a chat reply opens the chat, and a campaign push opens its link or goes to
   * `Hailbell.push.onOpen`. Returns false for other notifications; handle those yourself.
   */
  handleNotification(payload: PushPayload): boolean {
    const read = readPayload(payload);
    if (!read || !isHailbellData(read.data)) return false;
    const native = getNative();
    if (!native) return true;
    if (Platform.OS === 'android' && read.data.hailbell === undefined) {
      // Android shows campaign pushes itself and opens them through the SDK; a library only reports
      // one in an unusual setup. Open its link so the tap still goes somewhere.
      const hb = typeof read.data.hb === 'string' ? safeJson(read.data.hb) : read.data.hb;
      const url = hb && typeof hb === 'object' ? (hb as { u?: unknown }).u : undefined;
      if (typeof url === 'string' && url) Linking.openURL(url).catch(() => {});
      return true;
    }
    native
      .handleTap(toUserInfo(read.data), read.actionId ?? null)
      .then(deliver)
      .catch((error) => console.warn('[Hailbell] handleNotification failed', error));
    return true;
  },

  /** Replies the user hasn't read yet. For a badge in a component, use `useHailbellUnread()`. */
  get unreadCount(): number {
    return getUnreadCount();
  },

  /** Calls `listener` with the new unread count whenever it changes. */
  addUnreadCountListener(listener: (count: number) => void): Subscription {
    return addUnreadCountListener(listener);
  },

  /** On sign-out: clears the identity and the cached chat. The next session is this install's anonymous chat. */
  reset() {
    firebaseToken = null;
    getNative()?.reset();
  },

  /**
   * Deletes this user's chat, images and details from Hailbell, for example when they delete their
   * account in your app, then starts a fresh anonymous chat. Rejects if the server can't be reached:
   * stop the account deletion then, so a retry deletes both.
   */
  async deleteData(): Promise<void> {
    const native = getNative();
    if (!native) return;
    await native.deleteData();
    firebaseToken = null;
  },

  /**
   * Deprecated: set the photo in the dashboard instead (Profile, per app), which you can change
   * without an app release. A photo bundled here (`require('./assets/support.png')` or a URI) shows
   * only until the chat first hears from Hailbell, and after that only while the dashboard has no
   * photo of its own for this app. It never shows once the app signs replies as the team, turns
   * photos off, or picks a photo (or no photo) for this app.
   */
  get teamPhoto(): TeamPhoto {
    return teamPhoto;
  },
  set teamPhoto(photo: TeamPhoto) {
    teamPhoto = photo;
    const uri = resolvePhoto(photo);
    getNative()
      ?.setTeamPhoto(uri)
      .then((loaded) => {
        if (!loaded) console.warn(`[Hailbell] Couldn't load the team photo from ${uri}.`);
      })
      .catch(() => {});
  },

  /**
   * After the customer's first message, the chat offers to turn on notifications if they're off, so
   * they see the reply. Set to false if your app asks for notification permission itself.
   */
  get asksForNotifications(): boolean {
    return asksForNotifications;
  },
  set asksForNotifications(asks: boolean) {
    asksForNotifications = asks;
    getNative()?.setAsksForNotifications(asks);
  },

  /**
   * Whether the chat is dark. Null (the default) follows the phone's setting. Set it when your app
   * has its own fixed look, so the chat matches the app: an always-dark app sets true.
   */
  get darkMode(): boolean | null {
    return darkMode;
  },
  set darkMode(dark: boolean | null) {
    darkMode = dark ?? null;
    getNative()?.setDarkMode(darkMode);
  },

  /** Push notifications. See `HailbellPush`. */
  push: HailbellPush,
};

function safeJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

export type HailbellType = typeof Hailbell;
