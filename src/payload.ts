import type { PushPayload } from './types';

/** The parts of a push Hailbell needs, read from whichever shape a push library hands over. */
export interface ReadPayload {
  data: Record<string, unknown>;
  title?: string;
  body?: string;
  /** The tapped button, when the payload is a tap on one. */
  actionId?: string;
  /** The notification request's id, when the library gives one. */
  identifier?: string;
}

type Obj = Record<string, unknown>;

const isObject = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const text = (v: unknown): string | undefined => (typeof v === 'string' && v.length > 0 ? v : undefined);

/** Taps on the notification itself, rather than on one of its buttons, in each library's words. */
const DEFAULT_ACTIONS = new Set([
  'expo.modules.notifications.actions.DEFAULT',
  'com.apple.UNNotificationDefaultActionIdentifier',
  'default',
]);

function parseJson(v: unknown): Obj | undefined {
  if (typeof v !== 'string') return undefined;
  try {
    const parsed = JSON.parse(v);
    return isObject(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

/** True when a data map is a Hailbell push: a chat reply (`hailbell`) or a campaign (`hb`). */
export function isHailbellData(data: Obj | undefined): boolean {
  return !!data && (data.hailbell !== undefined || data.hb !== undefined);
}

/**
 * Reads a push from:
 * - React Native Firebase: `RemoteMessage` (`data`, `notification.title`, `notification.body`)
 * - expo-notifications: `Notification` (`request.content`, `request.trigger.payload` on iOS,
 *   `request.trigger.remoteMessage` on Android) and `NotificationResponse` (`notification`, `actionIdentifier`)
 * - an expo-notifications background task's `data` (`data`, `dataString`, `notification`)
 * - a bare data map or iOS `userInfo`
 */
export function readPayload(input: PushPayload): ReadPayload | null {
  if (!isObject(input)) return null;

  // expo-notifications NotificationResponse
  if (isObject(input.notification) && typeof input.actionIdentifier === 'string') {
    const inner = readPayload(input.notification);
    if (!inner) return null;
    const action = input.actionIdentifier;
    return DEFAULT_ACTIONS.has(action) ? inner : { ...inner, actionId: action };
  }

  // expo-notifications Notification
  if (isObject(input.request)) {
    const request = input.request;
    const content = isObject(request.content) ? request.content : {};
    const trigger = isObject(request.trigger) ? request.trigger : {};
    const remote = isObject(trigger.remoteMessage) ? trigger.remoteMessage : undefined;
    const remoteNote = remote && isObject(remote.notification) ? remote.notification : undefined;
    const candidates = [
      isObject(trigger.payload) ? trigger.payload : undefined,
      remote && isObject(remote.data) ? remote.data : undefined,
      isObject(content.data) ? content.data : undefined,
    ];
    const data = candidates.find(isHailbellData) ?? candidates.find(Boolean) ?? {};
    return {
      data,
      title: text(content.title) ?? text(remoteNote?.title),
      body: text(content.body) ?? text(remoteNote?.body),
      identifier: text(request.identifier),
    };
  }

  // React Native Firebase RemoteMessage, or an expo-notifications background task payload
  if ((isObject(input.data) || isObject(input.notification)) && !isHailbellData(input)) {
    const data = isObject(input.data) ? input.data : {};
    const note = isObject(input.notification) ? input.notification : undefined;
    // Background tasks on Android can carry the FCM data as a JSON string, or inside `notification`.
    const candidates = [data, parseJson(data.dataString), note && isObject(note.data) ? note.data : undefined];
    return {
      data: candidates.find(isHailbellData) ?? data,
      title: text(note?.title),
      body: text(note?.body),
      identifier: text(input.messageId),
    };
  }

  // A bare data map or userInfo
  return { data: input };
}

/** An Android FCM data map: every value a string, as FirebaseMessagingService delivers them. */
export function toStringMap(data: Obj): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(data)) {
    if (value === undefined || value === null) continue;
    out[key] = typeof value === 'string' ? value : JSON.stringify(value);
  }
  return out;
}

/** An iOS userInfo: `hb` as an object, as APNs delivers it, even when a library turned it into text. */
export function toUserInfo(data: Obj): Obj {
  const hb = data.hb;
  return typeof hb === 'string' ? { ...data, hb: parseJson(hb) ?? hb } : data;
}
