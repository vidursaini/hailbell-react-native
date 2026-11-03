import { useSyncExternalStore } from 'react';

import { getNative } from './NativeHailbell';
import type { Subscription } from './types';

type Listener = (count: number) => void;

const listeners = new Set<Listener>();
let count: number | null = null;
let nativeSubscription: { remove(): void } | null = null;

function current(): number {
  if (count === null) count = getNative()?.getUnreadCount() ?? 0;
  return count;
}

function start() {
  if (nativeSubscription) return;
  const native = getNative();
  if (!native) return;
  nativeSubscription = native.addListener('onUnreadCountChange', (event) => {
    if (event.count === count) return;
    count = event.count;
    listeners.forEach((listener) => listener(event.count));
  });
  // The count may have changed before the listener was added.
  count = native.getUnreadCount();
}

/** The number of replies the user hasn't read yet. */
export function getUnreadCount(): number {
  return current();
}

/** Calls `listener` with the new count whenever it changes. */
export function addUnreadCountListener(listener: Listener): Subscription {
  start();
  listeners.add(listener);
  return {
    remove() {
      listeners.delete(listener);
      if (listeners.size === 0 && nativeSubscription) {
        nativeSubscription.remove();
        nativeSubscription = null;
        count = null;
      }
    },
  };
}

function subscribe(onChange: () => void) {
  const subscription = addUnreadCountListener(onChange);
  return () => subscription.remove();
}

/**
 * Replies the user hasn't read yet, for a badge on your support button. The component re-renders
 * when the count changes.
 *
 *     const unread = useHailbellUnread();
 *     {unread > 0 && <View style={styles.dot} />}
 */
export function useHailbellUnread(): number {
  return useSyncExternalStore(subscribe, current, () => 0);
}
