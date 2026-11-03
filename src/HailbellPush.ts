import { Platform } from 'react-native';

import { getNative } from './NativeHailbell';
import { isHailbellData, readPayload, toStringMap } from './payload';
import { getOpenHandler, setOpenHandler } from './taps';
import type { OpenHandler, PushPayload, Value } from './types';
import { stringMap } from './values';

let offersChannel = 'hailbell_offers';

/**
 * Push notifications sent from the Hailbell dashboard, the REST API or an AI agent. The same calls as
 * `Hailbell.push` on iOS and `HailbellPush` on Android.
 */
export const HailbellPush = {
  /**
   * Asks for permission to show notifications (on Android 13 and later, and on iOS), then registers
   * with APNs on iOS. Resolves with whether notifications are allowed.
   */
  async requestPermission(options: { provisional?: boolean } = {}): Promise<boolean> {
    return (await getNative()?.requestPushPermission(options.provisional === true)) ?? false;
  },

  /** Whether the user allows notifications from the app. */
  async canNotify(): Promise<boolean> {
    return (await getNative()?.canNotify()) ?? false;
  },

  /**
   * Key/value data about the user, for segments and `{{tag.x}}`: `{ plan: 'pro', cuisine: 'thai' }`.
   * A null value removes the tag.
   */
  setTags(tags: Record<string, Value | null | undefined>) {
    const remove = Object.keys(tags).filter((key) => tags[key] === null);
    getNative()?.setTags(stringMap(tags), remove);
  },

  /**
   * Promotional pushes on or off. Transactional ones (receipts, replies) still arrive. New devices
   * start opted out. The choice is saved and sent with every subscribe, so a new token keeps it.
   */
  setMarketing(enabled: boolean) {
    getNative()?.setMarketing(enabled);
  },

  /** Stops Hailbell pushes to this device until the next `Hailbell.setPushToken`. */
  optOut() {
    getNative()?.optOut();
  },

  /** iOS: clears the app icon badge, and the count the notification service extension keeps in `appGroup`. */
  clearBadge(appGroup?: string) {
    getNative()?.clearBadge(appGroup ?? null);
  },

  /**
   * Android: the channel campaign pushes show on, when the app already has an offers channel. Set it
   * before `Hailbell.configure`, or with the config plugin's `android.offersChannel`, and enter the
   * same ID in the dashboard's push settings.
   */
  get offersChannel(): string {
    return offersChannel;
  },
  set offersChannel(channel: string) {
    offersChannel = channel;
    getNative()?.setOffersChannel(channel);
  },

  /**
   * iOS: called when the user taps a Hailbell campaign push or one of its buttons. Return true when
   * you handled the link yourself; otherwise Hailbell opens it (deep links and web pages alike).
   * On Android the link opens straight away; route it with React Native's `Linking`.
   */
  get onOpen(): OpenHandler | null {
    return getOpenHandler();
  },
  set onOpen(handler: OpenHandler | null) {
    setOpenHandler(handler);
  },

  /** True when the push came from Hailbell: a chat reply or a campaign. */
  isHailbell(payload: PushPayload): boolean {
    return isHailbellData(readPayload(payload)?.data);
  },

  /**
   * Call for every push your app receives while it runs, and from your background message handler.
   * On Android it shows a reply that arrived with the app open (unless the chat is on screen) and
   * every campaign push, which are data-only. On iOS the system shows pushes, so this only checks.
   * Resolves false for pushes that are not Hailbell's; handle those yourself.
   */
  async handleMessage(message: PushPayload): Promise<boolean> {
    const payload = readPayload(message);
    if (!payload || !isHailbellData(payload.data)) return false;
    const native = getNative();
    if (!native) return true;
    const data = Platform.OS === 'android' ? toStringMap(payload.data) : payload.data;
    return native.handleMessage(data as Record<string, string>, payload.title ?? null, payload.body ?? null);
  },
};

export type HailbellPushType = typeof HailbellPush;
