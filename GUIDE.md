# Adding Hailbell to a React Native app

This guide takes a React Native app, with or without Expo, to a live support chat: push replies, offers, entry points and store review. The chat your customers see is the same native screen as in Swift and Kotlin apps. Plan on about an hour.

The API reference is in [README.md](README.md).

## How it works

```
React Native app
  └─ @hailbell/react-native (TypeScript)
       └─ native module ─┬─ iOS: the Hailbell Swift SDK (SwiftUI chat, APNs)
                         └─ Android: the Hailbell Kotlin SDK (Compose chat, FCM)
                                  │
                                  ▼  HTTPS + WebSocket
                         api.hailbell.com ── dashboard, Discord, Telegram
```

- The package doesn't draw the chat in JavaScript. `Hailbell.open()` shows the native chat from the iOS and Android SDKs, with the same ticks, typing dots, photos, review and offer cards, and its 14 languages.
- Every call in this guide (`configure`, `identify`, `setPushToken` and the rest) is the native SDK call of the same name, passed straight through.
- Replies to a closed chat arrive as push notifications: APNs on iOS, FCM on Android.

## 1. Create the app in the dashboard

Using a coding agent? With the [Hailbell skill](https://hailbell.com/docs#agents) and its MCP server connected, the agent makes the app and both platforms itself (step 2). You still sign up and upload the push keys (step 3).

1. **Sign up** at [app.hailbell.com](https://app.hailbell.com/?signup=1) with **Continue with Google** or an emailed sign-in link. The first sign-in makes an account with a 14 day free trial, no card needed, and the first-run setup creates your first app from an App Store search.
2. Give the app an **iOS** and an **Android** platform: on the app's page in **Settings**, use **Add iOS** or **Add Android** under **Platforms**. Each platform has its own **App ID** (`app_…`), and your app passes the one for the platform it runs on. App IDs are public, so they live in code. Set the iOS platform's **Bundle ID (for push)** under **Edit**.
3. Upload your push keys in **Settings › Push keys**:
   - **iOS:** in Apple Developer, **Certificates, Identifiers & Profiles › Keys**, make a key with **Apple Push Notifications service (APNs)** on, download the `.p8` file, and upload it with its **Key ID** and your **Team ID**. One key covers every app in that Apple team.
   - **Android:** in the Firebase console, **Project settings › Service accounts › Generate new private key**, and upload the JSON file. One file per Firebase project.

   Hailbell checks each key with Apple or Google, stores it encrypted and never shows it again.
4. In **Push › Setup**, enter the Android app's **Firebase project ID** (from `google-services.json`) and press **Save**. Both cards should say **Ready**. Hailbell also checks Firebase ID tokens against that project (signed-in users, below).
5. Optional: **Chat design** (accent for light and dark, title, team photo), **Reviews** (App Store ID and Play package name), **Offers**, Discord or Telegram, and AI replies.

## 2. Add the package

```sh
npx expo install @hailbell/react-native
```

Bare React Native apps use `npm install @hailbell/react-native`. The package is [@hailbell/react-native on npm](https://www.npmjs.com/package/@hailbell/react-native) and carries the native SDKs inside it, so there is nothing else to download.

Requirements:

- Expo SDK 54 or later (built and tested with SDK 58 and React Native 0.88), using a [development build](https://docs.expo.dev/develop/development-builds/introduction/) or EAS Build. **Expo Go can't load it.** In Expo Go every call does nothing and logs one warning.
- iOS 16 and Android 8 (API 26) or later. The config plugin raises the app's iOS deployment target and Android `minSdkVersion` when they are lower, and never lowers them.
- Bare React Native apps (no Expo) first run `npx install-expo-modules@latest`. That adds the small Expo modules core the package is built on. The rest of this guide applies unchanged.

The native module brings the Swift SDK into the app through CocoaPods, and the Kotlin SDK as a Gradle module with Compose, OkHttp and Coil. It has no Firebase dependency of its own. The SDK's privacy manifest is included and Xcode merges it into the app's report.

## 3. The config plugin

Add the plugin to `app.json` (or `app.config.ts`), then run `npx expo prebuild` (or build with EAS):

```json
{
  "expo": {
    "plugins": [
      ["@hailbell/react-native", {
        "ios": { "appId": "app_IOS_XXXXXXXX", "notificationService": true },
        "android": { "appId": "app_ANDROID_XXXXXXXX" }
      }]
    ]
  }
}
```

What it sets up:

| Option | Default | What it does |
| --- | --- | --- |
| `appId`, `ios.appId`, `android.appId` | none | Configures Hailbell natively at launch, before any JavaScript runs. A tapped reply on a cold start and a push that arrives while the app is closed then find the SDK ready. Use `appId` when both platforms share one, or the platform keys when they differ (the usual case). |
| `baseUrl` | `https://api.hailbell.com` | Another Hailbell server. |
| `ios.pushNotifications` | `true` | Adds the Push Notifications capability (`aps-environment`). |
| `ios.notificationService` | off | Adds a Notification Service Extension for images, buttons, badge increments and delivery receipts on campaign pushes (step 9). `true`, or `{ "appGroup": "group.com.yourcompany.yourapp" }` for badge increments. |
| `android.offersChannel` | `hailbell_offers` | The app's own channel for campaign pushes, if it already has one people know. |

Without the plugin's App ID, call `Hailbell.configure` from JavaScript (step 4). With it, that call does nothing, so it's safe to keep both.

## 4. Wrap it in one file

Keep every Hailbell call in one file, so emptying one string turns the chat off. `example/src/hailbell.ts` is a complete version of this file with expo-notifications.

```ts
// src/support/hailbell.ts
import { Hailbell } from '@hailbell/react-native';
import { Platform } from 'react-native';

/** Public, like the web widget's data-app-id. Set to '' to turn the chat off and hide its entry points. */
const APP_ID = Platform.select({ ios: 'app_IOS_XXXXXXXX', android: 'app_ANDROID_XXXXXXXX' }) ?? '';
export const isEnabled = APP_ID.length > 0;

export function configureHailbell() {
  if (!isEnabled) return;
  Hailbell.configure({ appId: APP_ID });

  Hailbell.setPaywallPlacements(['winback', 'support_offer']); // every placement an offer may open
  Hailbell.onPaywall = (placement, purchased) => {
    // Open the paywall (step 8), and call purchased() if they buy.
  };
}

/** Send whenever the plan changes. appVersion, os and locale are sent automatically. */
export function updateMetadata(plan: 'free' | 'pro', superwallUserId?: string) {
  Hailbell.setMetadata({ plan, superwallUserId }); // or revenueCatUserId
}

/** `source` names the button, so the dashboard shows where people find the chat. */
export function openSupport(source: string) {
  if (!isEnabled) return;
  Hailbell.track('chat_opened', { source });
  Hailbell.open();
}
```

Call `configureHailbell()` once, at the top level of your app's entry file or root layout, before anything else uses Hailbell.

| Call | What you get in the dashboard |
| --- | --- |
| `configure({ appId })` | The chat, with a session per install. The install id survives a reinstall on iOS (Keychain). |
| `setMetadata` | Customer chips (plan, app version) and pinned inbox columns. Values may be text, numbers or booleans; `null` values are left out. |
| `superwallUserId` or `revenueCatUserId` metadata | Spend, plan and how long they've subscribed, from the Superwall or RevenueCat webhook you connect in the app's **Revenue** settings. |
| `track` | Signals next to the chat, such as which button opened it or which paywall they declined. |
| `setPaywallPlacements` + `onPaywall` | **Send offer** lists these placements, a tap opens the paywall in the app, and purchases count per offer. |

### Who customers see on replies

The dashboard decides, per app, and your app can't change it: the SDK has no setting for the name or photo on replies, so an app can't sign as someone else. In the dashboard:

- **Chat design › Replies are signed**: **By each teammate** (their name and photo) or **As the team** (the chat title, else "<App> Support", and no personal photo).
- **Profile › In each app**: each teammate can use another name, their own photo or no photo in one app, and their profile everywhere else.

Changes show the next time the chat opens, with no app release. Earlier replies keep the name they were sent with and show the current photo. Agents can read and change the same over MCP (`get_reply_identity`, `update_reply_identity`) and REST (`/v1/reply-identity`).

``Hailbell.teamPhoto`` is deprecated. Upload the photo in the dashboard instead: the chat loads it from Hailbell and caches it, so it usually shows offline after the first load. A bundled photo now shows only until the chat first hears from Hailbell, and after that only while the dashboard has no photo of its own for this app. It never shows once the app signs replies as the team, turns photos off, or sets a photo (or no photo) for this app. The chat remembers the last answer, so an offline launch follows it too.

## 5. Entry points

The package presents the chat; the app decides where people open it. Use the same four entry points as the native apps:

1. **Profile row "Chat with us"**, the whole row tappable.
2. **Profile header button** with a headset icon and a red dot for unread replies:

   ```tsx
   import { useHailbellUnread } from '@hailbell/react-native';

   function SupportHeaderButton() {
     const unread = useHailbellUnread();
     return (
       <Pressable onPress={() => openSupport('profile_header')} accessibilityLabel="Chat with us">
         <Ionicons name="headset" size={22} />
         {unread > 0 && <View style={styles.redDot} />}
       </Pressable>
     );
   }
   ```

   Outside components, read `Hailbell.unreadCount` or subscribe with `Hailbell.addUnreadCountListener`.
3. **Home Screen quick action / app shortcut "Chat with us"**, for example with `expo-quick-actions`, calling `openSupport('quick_action')`.
4. **Support bar "Need help?"** for free users, 24 hours after their first closed paywall. Most buyers buy at the first paywall or within minutes of closing it, so waiting a day keeps them out of the chat. Check your own paywall data before picking the delay.

`Hailbell.open()` shows a full-height sheet: a page sheet on iOS, its own translucent activity on Android. It doesn't stack a second chat if one is open. `Hailbell.close()` closes it.

## 6. Signed-in users (optional)

Without a token, a chat belongs to the install. To follow a user across devices, your backend signs an HS256 JWT with the app's identity secret, with `sub` set to your user id and a short expiry:

```ts
Hailbell.identify({ identityToken: jwtFromYourBackend, name, email });
Hailbell.reset();            // on sign-out
await Hailbell.deleteData(); // in the delete-account flow, before deleting the account
```

**Firebase Auth** apps don't need a backend for this. Set the Firebase project in the dashboard's push settings, then pass a function that returns the current ID token. Hailbell calls it for every new session, because ID tokens last an hour:

```ts
import auth from '@react-native-firebase/auth';

const user = auth().currentUser;
if (user && !user.isAnonymous) {
  Hailbell.identify({ name: user.displayName, email: user.email, firebaseIdToken: () => auth().currentUser?.getIdToken() });
}
```

With the Firebase JS SDK, use `() => getAuth().currentUser?.getIdToken()`.

`identify({ userId, name, email })` without a token shows those details but never uses them to find a chat. To block anonymous chats, tick **Only signed-in users can chat**.

`deleteData()` is required for apps with account deletion (App Store guideline 5.1.1(v), Google Play's account deletion policy). It rejects when the server can't be reached: stop the deletion then, so a retry deletes both.

## 7. Push for replies

Replies to a closed chat arrive as push notifications. Hailbell needs the device token: the **APNs token on iOS** and the **FCM token on Android**. An Expo push token (`ExponentPushToken[…]`) doesn't work; `setPushToken` rejects it with a warning.

Set up push with the library your app already uses. On iOS, the package also handles some of this natively:

- When the app registers for remote notifications, the package passes the APNs token to Hailbell, so the token from JavaScript is a backup.
- At launch it sets a small notification delegate, unless the app's native code set one first. expo-notifications and React Native Firebase both keep it in their chain. It opens the chat when someone taps a reply, and counts and opens campaign pushes. That matters with React Native Firebase, which only reports pushes sent through Firebase to JavaScript. Hailbell sends through APNs directly.

### With expo-notifications

1. `npx expo install expo-notifications expo-task-manager`, add `"expo-notifications"` to the plugins, and set `android.googleServicesFile` to the Firebase project's `google-services.json`.
2. Ask for permission and send the device token:

   ```ts
   import * as Notifications from 'expo-notifications';
   import { Hailbell, HailbellPush } from '@hailbell/react-native';

   export async function registerForPush() {
     if (!(await HailbellPush.requestPermission())) return;
     Hailbell.setPushToken(await Notifications.getDevicePushTokenAsync()); // APNs on iOS, FCM on Android
   }
   Notifications.addPushTokenListener((token) => Hailbell.setPushToken(token));
   ```

3. Pass taps, and let Hailbell show its own pushes in the foreground. Put this at the top level of a file the app imports at startup:

   ```ts
   import * as TaskManager from 'expo-task-manager';
   import { Platform } from 'react-native';

   // Android: campaign pushes are data-only, so expo-notifications doesn't show them in the
   // background. This task hands them to Hailbell, which does.
   TaskManager.defineTask<Notifications.NotificationTaskPayload>('hailbell-push', async ({ data }) => {
     await HailbellPush.handleMessage(data);
   });
   Notifications.registerTaskAsync('hailbell-push');

   Notifications.setNotificationHandler({
     handleNotification: async (notification) => {
       if (Platform.OS === 'android' && HailbellPush.isHailbell(notification)) {
         await HailbellPush.handleMessage(notification); // shows it, unless the chat is on screen
         return { shouldShowBanner: false, shouldShowList: false, shouldPlaySound: false, shouldSetBadge: false };
       }
       return { shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false };
     },
   });

   Notifications.addNotificationResponseReceivedListener((response) => {
     if (Hailbell.handleNotification(response)) return;
     // your own notifications
   });
   const launched = Notifications.getLastNotificationResponse();
   if (launched) Hailbell.handleNotification(launched);
   ```

### With React Native Firebase

1. Install `@react-native-firebase/app` and `@react-native-firebase/messaging` and follow their Expo setup (`google-services.json`, `GoogleService-Info.plist`).
2. Send the right token for each platform:

   ```ts
   import messaging from '@react-native-firebase/messaging';
   import { Platform } from 'react-native';

   export async function registerForPush() {
     if (!(await HailbellPush.requestPermission())) return;
     const token = Platform.OS === 'ios' ? await messaging().getAPNSToken() : await messaging().getToken();
     Hailbell.setPushToken(token);
   }
   messaging().onTokenRefresh((token) => {
     if (Platform.OS === 'android') Hailbell.setPushToken(token);
   });
   ```

3. Hand every message to Hailbell, in the foreground and the background. The background handler goes in `index.js`, before `registerRootComponent` / `AppRegistry`:

   ```ts
   messaging().setBackgroundMessageHandler(async (message) => {
     if (await HailbellPush.handleMessage(message)) return;
     // your own data messages
   });
   messaging().onMessage(async (message) => {
     if (await HailbellPush.handleMessage(message)) return;
     // your own foreground messages
   });
   ```

4. Taps need no code. On Android, replies open the chat and campaign pushes open their link through the SDK. On iOS, the package's notification delegate does the same. To show replies as banners while the app is open on iOS, add `"messaging_ios_foreground_presentation_options": ["badge", "sound", "list", "banner"]` to `firebase.json`. That setting covers your Firebase pushes too.

Native iOS apps with Firebase sometimes set `FirebaseAppDelegateProxyEnabled = NO`. You don't need it here: the package reads the APNs token from the app delegate and from `getAPNSToken()`.

### Without a push library (iOS)

`await HailbellPush.requestPermission()` asks, registers with APNs, and the package sends the token and handles taps by itself. Android always needs one of the two libraries for its FCM token.

### How reply pushes behave

- They go out only when the customer's chat is not open, titled "<App> Support", and a tap opens the chat at the latest message.
- iOS builds installed from Xcode or `expo run:ios` register as sandbox; TestFlight, ad hoc and App Store builds as production. The package reads this from the build.
- On Android, Hailbell checks whether a customer deleted the app with a silent data message (`hb_check`). `HailbellPush.handleMessage` resolves false for it. Don't show a notification for data messages your app doesn't recognise.

## 8. Offers

An offer card in the chat opens one of your paywalls. Tell Hailbell which placements exist and open them in `onPaywall`. The chat stays open, and paywall SDKs that present natively appear on top of it:

```ts
// RevenueCat (react-native-purchases-ui)
Hailbell.onPaywall = async (placement, purchased) => {
  const offering = (await Purchases.getOfferings()).all[placement];
  const result = await RevenueCatUI.presentPaywall({ offering });
  if (result === PAYWALL_RESULT.PURCHASED || result === PAYWALL_RESULT.RESTORED) purchased();
};
```

With Superwall, register the placement with Superwall's React Native or Expo SDK, pass `entry_point: 'hailbell_chat'` as a parameter, and call `purchased()` when the paywall closes with a purchase. Whatever the SDK, `purchased()` must run only after a real purchase. If your paywall is a React screen, call `Hailbell.close()` before you navigate to it, or it opens under the chat.

Name the offers under **Settings › Offers** in the dashboard. Taps, "Not now" and purchases show in Discord and the dashboard.

## 9. Campaign pushes

Hailbell's own push campaigns (from the dashboard, the REST API or MCP) need nothing more for text, deep links and tap counts.

- **Images, buttons, badge increments and delivery receipts on iOS** need the notification service extension: set `"ios": { "notificationService": true }` in the plugin. Prebuild then adds a `HailbellNotificationExtension` target with bundle ID `<your bundle ID>.NotificationService`, and EAS Build signs it. For badge increments, use `{ "appGroup": "group.<your bundle ID>" }` and call `HailbellPush.clearBadge('group.<your bundle ID>')` when the app opens. An app can have only one notification service extension; if another library already adds one, leave this off.
- **Deep links:** on iOS, set `HailbellPush.onOpen = (n) => { router.push(...); return true; }` to route a link yourself. Return false or leave it unset, and Hailbell opens the link. On Android the link opens straight away through the app's intent filters; route it with React Native's `Linking`.
- **Android channel:** campaigns show on "Offers and updates" (`hailbell_offers`). If the app already has an offers channel, set `android.offersChannel` in the plugin and the same ID as **Android: offers channel ID** in the dashboard.
- **Offer pushes that open a paywall:** give the campaign a link like `yourapp://offer?source=support` and hand links your router doesn't handle to your paywall SDK (Superwall has a deep-link handler for this), where a campaign on the `deepLink_open` event can open the offer paywall.

### Marketing opt-in

New devices start opted out of marketing pushes. Add a switch, off by default, to the app's notification settings and pass it on:

```ts
HailbellPush.setMarketing(isOn);
```

The choice is saved and sent with every subscribe, so a new token keeps it. On Android you can instead read the offers channel's system setting when the app comes to the foreground, as in the Android guide. Transactional pushes and chat replies arrive either way.

## 10. Settings

```ts
Hailbell.darkMode = true;              // null (default) follows the phone; true for an always-dark app
Hailbell.asksForNotifications = false; // if your app asks for notification permission itself
```

## 11. What's automatic

The same as the native apps: text and up to 4 photos (system photo picker, no photo permission), read ticks, typing dots, the online dot, an offline cache of 100 messages, review and offer cards, the AI notice and "AI" label, and the chat's own words in 14 languages.

## 12. Store submission

The data Hailbell collects is the same as in a native app.

### App Store: App Privacy

Add these as **Linked to the user**, **not used for tracking**, with the purpose **App Functionality**. Skip any your app already declares.

| Data type | Why |
| --- | --- |
| Contact Info › Name, Email Address | Only if the app passes them to `identify` |
| User Content › Customer Support | Chat messages |
| User Content › Photos or Videos | Photos attached in the chat |
| Identifiers › User ID | A signed-in user id, `superwallUserId` or `revenueCatUserId` |
| Identifiers › Device ID | The chat's install id and the push token |
| Diagnostics › Other Diagnostic Data | App version, OS version, device model, language and time zone |
| Usage Data › Product Interaction | `track` signals, such as which button opened the chat |
| Purchases › Purchase History | Only if you connect the Superwall or RevenueCat webhook |

No new usage-description strings are needed: the photo picker runs out of process, and push uses the app's existing permission prompt.

### Google Play: Data safety

| Data type | Collected for |
| --- | --- |
| Personal info › Name, Email address | App functionality, only if passed to `identify` |
| Messages › Other in-app messages | App functionality (support chat) |
| Photos and videos › Photos | App functionality, only those the user attaches |
| App info and performance › Diagnostics | App version, Android version, device model, language and time zone |
| App activity › App interactions | `track` signals, such as which button opened the chat |
| Device or other IDs | The chat's install id and the FCM token |

Data is encrypted in transit and is deleted through the in-app account deletion (`deleteData`).

### Notes for App Review

> This version adds in-app support chat. Open it from Profile › Chat with us. Messages go to our support team, who reply in the chat and with a notification. When no one is available, an AI assistant may reply first; the chat says so before the first message and labels every AI reply. Photos are only sent when the user picks them with the system photo picker. Deleting the account in the app also deletes the chat from our servers. No sign-in is needed to use the chat.

Your privacy policy should mention customer support messages and name the AI provider that answers when AI replies are on.

## 13. Rollout checklist

- [ ] Dashboard: iOS and Android platforms, bundle ID, Firebase project, accent, team photo
- [ ] Package added; plugin in `app.json` with both App IDs; new development build
- [ ] `hailbell.ts` with `configure`, metadata, placements and `onPaywall`
- [ ] Entry points: Profile row, header button with `useHailbellUnread`, quick action, support bar
- [ ] Push: permission, `setPushToken` on both platforms, `handleMessage` in the foreground and background, taps
- [ ] `deleteData()` in account deletion, `reset()` on sign-out
- [ ] Tested on a device or simulator with a development build: anonymous message, reply push with the app closed, tap opens the chat, deletion
- [ ] Release builds: production push on TestFlight; Android release build with the Play package
- [ ] App Privacy and Data safety updated

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| "The native module isn't in this build" | Expo Go, or a build from before the package was added. Make a new development build. |
| `setPushToken` warns about an Expo push token | Use `getDevicePushTokenAsync()`, not `getExpoPushTokenAsync()`. |
| `setPushToken` warns about APNs on iOS | It got the FCM token. With React Native Firebase use `messaging().getAPNSToken()` on iOS. |
| No reply push on Android | No `google-services.json`, or the dashboard has a different Firebase project from the app's. |
| Campaign pushes don't show on Android with the app closed | The background handler (expo-notifications task or `setBackgroundMessageHandler`) isn't registered at the top level of a file loaded at startup. |
| The paywall opens under the chat | The paywall is a React screen: call `Hailbell.close()` first. |
| Pod install says HailbellReactNative needs a higher deployment target | Run prebuild again with the plugin, or set the iOS deployment target to 16 or later. |

The native SDKs ship inside the app, so a Hailbell SDK change reaches people with the next app release that includes the new package version. Server changes reach them right away.
