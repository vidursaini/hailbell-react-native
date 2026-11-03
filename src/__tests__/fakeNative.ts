// A stand-in for the native module: every function is a jest mock, and `emit` plays a native event.
type Listener = (event: any) => void;

export function createFakeNative() {
  const listeners = new Map<string, Set<Listener>>();
  let unread = 0;
  const fake = {
    get unread() {
      return unread;
    },
    set unread(value: number) {
      unread = value;
    },
    configure: jest.fn(),
    open: jest.fn(),
    close: jest.fn(),
    identify: jest.fn(),
    identifyWithFirebase: jest.fn(),
    provideFirebaseToken: jest.fn(),
    setMetadata: jest.fn(),
    track: jest.fn(),
    setPaywallPlacements: jest.fn(),
    setPaywallHandler: jest.fn(),
    paywallPurchased: jest.fn(),
    setPushToken: jest.fn((_token: string, _sandbox: boolean | null) => true),
    reset: jest.fn(),
    deleteData: jest.fn(async () => {}),
    getUnreadCount: jest.fn((): number => unread),
    setTeamPhoto: jest.fn(async (_uri: string | null) => true),
    setAsksForNotifications: jest.fn(),
    setDarkMode: jest.fn(),
    requestPushPermission: jest.fn(async (_provisional: boolean) => true),
    canNotify: jest.fn(async () => true),
    setTags: jest.fn(),
    setMarketing: jest.fn(),
    optOut: jest.fn(),
    setOffersChannel: jest.fn(),
    clearBadge: jest.fn(),
    setPushOpenHandler: jest.fn(),
    handleMessage: jest.fn(async (_data: Record<string, string>, _title: string | null, _body: string | null) => true),
    handleTap: jest.fn(async (_userInfo: Record<string, unknown>, _actionId: string | null): Promise<any> => null),
    addListener: jest.fn((name: string, listener: Listener) => {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name)!.add(listener);
      return { remove: () => listeners.get(name)?.delete(listener) };
    }),
    emit(name: string, event: unknown) {
      listeners.get(name)?.forEach((listener) => listener(event));
    },
    listenerCount(name: string) {
      return listeners.get(name)?.size ?? 0;
    },
  };
  return fake;
}

export type FakeNative = ReturnType<typeof createFakeNative>;

/** Loads a fresh copy of the package with `fake` as its native module (or none, when null). */
export function loadWith(fake: FakeNative | null) {
  jest.resetModules();
  jest.doMock('../NativeHailbell', () => ({
    getNative: () => fake,
    resetWarningForTests: () => {},
  }));
  return require('../index') as typeof import('../index');
}

/** Lets promise callbacks run. */
export const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
