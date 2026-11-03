# Hailbell React Native SDK

In-app support chat and push for React Native and Expo apps on iOS 16+ and Android 8+. It wraps the native SDKs: the chat is the same SwiftUI and Compose screen that native apps show, and every call goes to the native call of the same name.

The step-by-step setup is in [GUIDE.md](GUIDE.md), covering the config plugin, push with expo-notifications or React Native Firebase, offers and store review. This page is the API reference.

```ts
import { Hailbell, HailbellPush, useHailbellUnread } from '@hailbell/react-native';

Hailbell.configure({ appId: 'app_...' });
Hailbell.open();
```

## Hailbell

| API | Use |
| --- | --- |
| `Hailbell.configure({ appId, baseUrl? })` | Call once at launch. With the config plugin's `appId`, the native side configures before JS runs and this does nothing. |
| `Hailbell.open()` / `Hailbell.close()` | Shows the native chat as a full-height sheet / closes it. `open` never stacks a second chat. |
| `Hailbell.identify({ userId, name, email, identityToken })` | Who the user is. Only `identityToken`, an HS256 JWT signed with the app's identity secret, links the chat across devices. |
| `Hailbell.identify({ name, email, firebaseIdToken })` | For Firebase Auth: `firebaseIdToken` returns the current ID token (or a promise of it). Hailbell calls it for every session. |
| `Hailbell.setMetadata(record)` | Context beside the chat, merged with earlier values. Numbers and booleans are sent as text; `null` is left out. |
| `Hailbell.track(event, properties?)` | A signal such as `paywall_decline` or `chat_opened`. |
| `Hailbell.setPaywallPlacements(list)` | The placements an offer can open. |
| `Hailbell.onPaywall = (placement, purchased) => {}` | Open the paywall, and call `purchased()` if they buy. `null` turns offers off. |
| `Hailbell.setPushToken(token, { sandbox? })` | The APNs token on iOS, the FCM token on Android. Takes a string or expo-notifications' `DevicePushToken`. Rejects Expo push tokens. |
| `Hailbell.handleNotification(payload): boolean` | For a tapped notification from any push library. Opens the chat for a reply, or opens a campaign's link (or hands it to `HailbellPush.onOpen`). False for other notifications. |
| `Hailbell.unreadCount` | Replies not yet read. |
| `Hailbell.addUnreadCountListener(fn)` | Calls `fn(count)` on changes; returns `{ remove() }`. |
| `useHailbellUnread()` | The unread count as a React hook, for a badge. |
| `Hailbell.reset()` | On sign-out: clears the identity and the cached chat. |
| `await Hailbell.deleteData()` | Deletes the user's chat, images and details from Hailbell. Rejects if the server can't be reached. |
| `Hailbell.teamPhoto` | `require('./photo.png')`, a URI, or `null` for the dashboard photo. |
| `Hailbell.darkMode` | `null` follows the phone; `true` or `false` fixes the chat's look. |
| `Hailbell.asksForNotifications` | Default true. Set false if your app asks for notification permission itself. |
| `Hailbell.push` | The same object as `HailbellPush`. |

## HailbellPush

| API | Use |
| --- | --- |
| `requestPermission({ provisional? }): Promise<boolean>` | Asks for notification permission (iOS, Android 13+); on iOS it also registers with APNs. |
| `canNotify(): Promise<boolean>` | Whether notifications are allowed. |
| `setTags(record)` | Key/value data for segments and `{{tag.x}}`; `null` removes a tag. |
| `setMarketing(enabled)` / `optOut()` | Promotional opt-in (saved and sent with every subscribe), or stop all pushes to this device. |
| `handleMessage(message): Promise<boolean>` | Pass every received push, foreground and background. On Android it shows campaign pushes and foreground replies. False for pushes that aren't Hailbell's. |
| `isHailbell(payload): boolean` | True for Hailbell pushes, in any library's shape. |
| `onOpen = (notification) => boolean` | iOS: a tapped campaign push. Return true when you routed its link; otherwise Hailbell opens it. |
| `clearBadge(appGroup?)` | iOS: resets the badge the notification service extension keeps. |
| `offersChannel` | Android: the app's own channel for campaign pushes. Set before `configure`, or with the plugin. |

Every payload argument takes what a push library hands you: a React Native Firebase `RemoteMessage`, an expo-notifications `Notification`, `NotificationResponse` or background task payload, or a raw data map.

## Config plugin

```json
["@hailbell/react-native", {
  "appId": "app_...",
  "baseUrl": "https://api.hailbell.com",
  "ios": { "appId": "app_...", "pushNotifications": true, "notificationService": { "appGroup": "group.com.yourcompany.yourapp" } },
  "android": { "appId": "app_...", "offersChannel": "offers" }
}]
```

| Option | Writes |
| --- | --- |
| `appId`, `ios.appId`, `baseUrl` | `HailbellAppId` and `HailbellBaseURL` in Info.plist |
| `appId`, `android.appId`, `baseUrl`, `android.offersChannel` | `com.hailbell.APP_ID`, `BASE_URL` and `OFFERS_CHANNEL` meta-data in the manifest |
| `ios.pushNotifications` (default true) | `aps-environment` in the entitlements |
| `ios.notificationService` | A `HailbellNotificationExtension` target running the Swift SDK's `HailbellNotificationService`, the App Group on both targets, and the EAS `appExtensions` entry |
| always | iOS deployment target and Android `minSdkVersion` raised to 16.0 and 26 when lower, never lowered |

## How it's built

**Expo Modules API.** The native side is an [Expo module](https://docs.expo.dev/modules/overview/) in Swift and Kotlin, chosen over a Turbo Module because:

- The native SDKs are Swift and Kotlin, and so is an Expo module. A Turbo Module needs a codegen spec and an Objective-C++ layer on iOS around Swift code.
- Expo modules can hook into the app delegate and `Application.onCreate` without the app editing native code. The package uses that to configure Hailbell at launch, pick up the APNs token and set the notification delegate, which apps on native SDKs do by hand.
- The config plugin, autolinking and New Architecture support come with it, and it works the same in Expo and, after `npx install-expo-modules`, in bare React Native apps.

**One copy of the native code.** The package compiles the Swift and Kotlin SDKs themselves. `scripts/sync-native.mjs` copies `sdk/swift/Sources` into `ios/Vendor`, `sdk/android/hailbell/src/main` into `android/src/vendor`, and the notification service class into `plugin/vendor`, and writes the Android dependencies from the SDK's `build.gradle.kts`. The copies are git-ignored and made on `npm install` and before `npm pack`, so a published package carries them. A test fails when the copies differ from the SDKs.

**Threads.** JS calls arrive on React Native's threads, so the native side queues each one on the main thread in order, where the SDKs run. Events (unread count, offer taps, Firebase token requests, campaign taps) go back to JS through the module's event emitter.

## Files

| File | What it holds |
| --- | --- |
| `src/Hailbell.ts`, `src/HailbellPush.ts` | The public API |
| `src/payload.ts` | Reads pushes from React Native Firebase, expo-notifications and raw data maps |
| `src/unread.ts`, `src/taps.ts` | The unread store and hook; routing for tapped campaign pushes |
| `src/NativeHailbell.ts` | The native module's types, and the no-op fallback for Expo Go and web |
| `ios/HailbellModule.swift`, `ios/HailbellBridge.swift` | The iOS module over the Swift SDK |
| `ios/HailbellAppDelegate.swift`, `ios/HailbellNotificationDelegate.swift` | Launch configuration, the APNs token, and taps on Hailbell pushes |
| `android/src/main/java/com/hailbell/reactnative/` | The Android module over the Kotlin SDK, and launch configuration |
| `plugin/src/` | The config plugin |
| `scripts/sync-native.mjs` | Copies the native SDKs into the package |
| `example/` | An Expo app with every call and expo-notifications push |

## Development

```sh
cd sdk/react-native
npm install            # also copies the native SDKs and builds build/ and plugin/build/
npm test               # Jest: the JS layer, the config plugin and the native copies
npm run typecheck
cd example && npm install && npx expo run:android   # or run:ios on a Mac
```

After changing `sdk/swift` or `sdk/android`, run `npm run sync-native` and rebuild the example.
