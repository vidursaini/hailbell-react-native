import { Linking } from 'react-native';

import { getNative, type NativeTap } from './NativeHailbell';
import type { HailbellNotification, OpenHandler } from './types';

let openHandler: OpenHandler | null = null;
let openSubscription: { remove(): void } | null = null;

export function getOpenHandler(): OpenHandler | null {
  return openHandler;
}

export function setOpenHandler(handler: OpenHandler | null) {
  openHandler = handler;
  const native = getNative();
  if (!native) return;
  if (handler && !openSubscription) {
    // Taps the native notification delegate received (React Native Firebase apps, cold starts).
    openSubscription = native.addListener('onPushOpen', (tap) => deliver(tap));
  } else if (!handler && openSubscription) {
    openSubscription.remove();
    openSubscription = null;
  }
  native.setPushOpenHandler(!!handler);
}

/** Hands a tapped campaign to `onOpen`, or opens its link, as the native SDK does. */
export function deliver(tap: NativeTap | null) {
  if (!tap || tap.kind !== 'campaign') return;
  const notification: HailbellNotification = {
    id: tap.id,
    url: tap.url,
    imageUrl: tap.imageUrl,
    buttons: tap.buttons ?? [],
    data: tap.data ?? {},
    actionId: tap.actionId,
  };
  let handled = false;
  if (openHandler) {
    try {
      handled = openHandler(notification) === true;
    } catch (error) {
      console.error('[Hailbell] push.onOpen threw', error);
    }
  }
  if (!handled && notification.url) {
    Linking.openURL(notification.url).catch((error) => console.warn(`[Hailbell] Couldn't open ${notification.url}`, error));
  }
}
