import type { ImageSourcePropType } from 'react-native';

export interface ConfigureOptions {
  /** The platform's App ID from the dashboard (`app_...`). It is public, so it can live in code. */
  appId: string;
  /** Another Hailbell server. Defaults to `https://api.hailbell.com`. */
  baseUrl?: string;
}

/** `identify` with your own sign-in. Only `identityToken` links the chat to the account across devices. */
export interface IdentifyOptions {
  userId?: string | null;
  name?: string | null;
  email?: string | null;
  /** An HS256 JWT from your backend, signed with the app's identity secret. */
  identityToken?: string | null;
}

/** `identify` for apps on Firebase Auth. */
export interface FirebaseIdentifyOptions {
  name?: string | null;
  email?: string | null;
  /**
   * Returns the signed-in user's current Firebase ID token, for example
   * `() => auth().currentUser?.getIdToken()`. Hailbell calls it for every new session, because ID
   * tokens last an hour.
   */
  firebaseIdToken: () => Promise<string | null | undefined> | string | null | undefined;
}

/** A value for metadata, signal properties and tags. Numbers and booleans are sent as text. */
export type Value = string | number | boolean;

/** Opens a paywall for an offer tapped in the chat. Call `purchased()` if the user buys. */
export type PaywallHandler = (placement: string, purchased: () => void) => void;

/** The team photo: a bundled image (`require('./photo.png')`), a URI, or null for the dashboard photo. */
export type TeamPhoto = ImageSourcePropType | string | null;

/**
 * A push token. On iOS, the APNs device token; on Android, the FCM registration token. Pass the
 * string, or the `DevicePushToken` object from expo-notifications' `getDevicePushTokenAsync()`.
 */
export type PushToken = string | { type?: string; data: string };

/** A tapped Hailbell campaign push, as `Hailbell.push.onOpen` sees it. */
export interface HailbellNotification {
  id: string;
  /** The link to open: the tapped button's, or the notification's. */
  url?: string;
  imageUrl?: string;
  buttons: { id: string; text: string; url?: string }[];
  /** The `data` object sent with the notification. */
  data: Record<string, unknown>;
  /** The id of the tapped button, if a button was tapped. */
  actionId?: string;
}

/** Return true when you handled the link yourself; otherwise Hailbell opens it. */
export type OpenHandler = (notification: HailbellNotification) => boolean | void;

/**
 * Anything a push library hands you: a React Native Firebase `RemoteMessage`, an expo-notifications
 * `Notification` or `NotificationResponse`, the payload of an expo-notifications background task, or
 * the raw data map.
 */
export type PushPayload = unknown;

export interface Subscription {
  remove(): void;
}
