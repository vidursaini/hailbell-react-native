// Every Hailbell call in one file, as the native setup guides recommend: emptying APP_ID turns the
// chat off. This example uses expo-notifications for push. GUIDE.md shows
// the same setup with React Native Firebase.
import { Hailbell, HailbellPush } from '@hailbell/react-native';
import * as Notifications from 'expo-notifications';
import * as TaskManager from 'expo-task-manager';
import { Alert, Platform } from 'react-native';

/** Public, like the web widget's data-app-id. Keep it equal to the plugin's appId in app.json. */
export const APP_ID = 'app_REPLACE_ME';
export const isEnabled = APP_ID.length > 0 && APP_ID !== 'app_REPLACE_ME';

const BACKGROUND_TASK = 'hailbell-background-push';

// Android: Hailbell's campaign pushes are data-only, so expo-notifications doesn't show them while
// the app is in the background. This task hands them to Hailbell, which shows them. It must be
// defined at the top level of a file the app imports at startup.
TaskManager.defineTask<Notifications.NotificationTaskPayload>(BACKGROUND_TASK, async ({ data }) => {
  await HailbellPush.handleMessage(data);
});

// In the foreground, Hailbell decides how its pushes show: on Android it shows them itself (and not
// while the chat is open); on iOS they show as banners. Other pushes keep this app's own rules.
Notifications.setNotificationHandler({
  handleNotification: async (notification) => {
    if (HailbellPush.isHailbell(notification) && Platform.OS === 'android') {
      await HailbellPush.handleMessage(notification);
      return { shouldShowBanner: false, shouldShowList: false, shouldPlaySound: false, shouldSetBadge: false };
    }
    return { shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false };
  },
});

export function configureHailbell() {
  if (!isEnabled) return;
  Hailbell.configure({ appId: APP_ID });
  Hailbell.teamPhoto = require('../assets/icon.png');
  Hailbell.setMetadata({ plan: 'free', appVariant: 'example' });

  // Offers: every placement an offer may open. Replace the alert with your paywall SDK, for example
  // Superwall's registerPlacement or RevenueCat's presentPaywall.
  Hailbell.setPaywallPlacements(['support_offer', 'winback']);
  Hailbell.onPaywall = (placement, purchased) => {
    Alert.alert('Paywall', `The offer opened the "${placement}" placement.`, [
      { text: 'Close', style: 'cancel' },
      { text: 'Buy', onPress: purchased },
    ]);
  };

  // iOS: campaign pushes whose link the app routes itself.
  HailbellPush.onOpen = (notification) => {
    console.log('Hailbell push opened', notification.id, notification.url);
    return false; // let Hailbell open the link
  };

  Notifications.registerTaskAsync(BACKGROUND_TASK).catch(() => {});

  // Taps on notifications that expo-notifications reports: chat replies open the chat.
  Notifications.addNotificationResponseReceivedListener((response) => {
    Hailbell.handleNotification(response);
  });
  // A tap that launched the app.
  const launched = Notifications.getLastNotificationResponse();
  if (launched) Hailbell.handleNotification(launched);

  // Keep the token current. The first one is sent by registerForPush below.
  Notifications.addPushTokenListener((token) => Hailbell.setPushToken(token));
}

/** Asks for notification permission and passes the device token to Hailbell. */
export async function registerForPush(): Promise<string> {
  const allowed = await HailbellPush.requestPermission();
  if (!allowed) return 'Notifications are off.';
  try {
    const token = await Notifications.getDevicePushTokenAsync();
    Hailbell.setPushToken(token);
    return `Token sent (${token.type}).`;
  } catch (error) {
    // Android needs the app's google-services.json (app.json android.googleServicesFile) for FCM.
    return `No push token: ${String(error)}`;
  }
}

/** Shows a fake campaign push through the SDK, to check notification look and taps on Android. */
export async function showTestCampaign() {
  const shown = await HailbellPush.handleMessage({
    data: {
      hb: JSON.stringify({ n: 'ntf_example', u: 'hailbellexample://offer', r: 0 }),
      hb_a: JSON.stringify({ t: 'Test campaign', b: 'Shown by HailbellPush.handleMessage' }),
    },
  });
  if (!shown) Alert.alert('Not shown', 'The payload was not recognised as a Hailbell push.');
}
