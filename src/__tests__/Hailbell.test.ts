import { Image, Linking, Platform } from 'react-native';

import { createFakeNative, flush, loadWith, type FakeNative } from './fakeNative';

let native: FakeNative;
let api: ReturnType<typeof loadWith>;
const os = Platform.OS;

beforeEach(() => {
  native = createFakeNative();
  api = loadWith(native);
  jest.spyOn(console, 'warn').mockImplementation(() => {});
  jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  jest.restoreAllMocks();
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
});

function onPlatform(name: 'ios' | 'android') {
  Object.defineProperty(Platform, 'OS', { value: name, configurable: true });
}

describe('configure and the chat screen', () => {
  it('passes the App ID and an optional server', () => {
    api.Hailbell.configure({ appId: 'app_1' });
    api.Hailbell.configure({ appId: 'app_1', baseUrl: 'https://hb.example.com' });
    expect(native.configure.mock.calls).toEqual([
      ['app_1', null],
      ['app_1', 'https://hb.example.com'],
    ]);
  });

  it('refuses an empty App ID', () => {
    api.Hailbell.configure({ appId: '' });
    expect(native.configure).not.toHaveBeenCalled();
    expect(console.warn).toHaveBeenCalled();
  });

  it('opens and closes the native chat', () => {
    api.Hailbell.open();
    api.Hailbell.close();
    expect(native.open).toHaveBeenCalledTimes(1);
    expect(native.close).toHaveBeenCalledTimes(1);
  });

  it('is the default export too', () => {
    expect(api.default).toBe(api.Hailbell);
    expect(api.Hailbell.push).toBe(api.HailbellPush);
  });
});

describe('identify', () => {
  it('sends the signed-in user, with empty values as null', () => {
    api.Hailbell.identify({ userId: 'u1', name: 'Ana', email: '', identityToken: 'jwt' });
    expect(native.identify).toHaveBeenCalledWith('u1', 'Ana', null, 'jwt');
  });

  it('asks the Firebase token function each time the SDK starts a session', async () => {
    const token = jest.fn().mockResolvedValueOnce('id-token-1').mockResolvedValueOnce('id-token-2');
    api.Hailbell.identify({ name: 'Ana', firebaseIdToken: token });
    expect(native.identifyWithFirebase).toHaveBeenCalledWith('Ana', null);

    native.emit('onFirebaseTokenRequest', { requestId: 'r1' });
    native.emit('onFirebaseTokenRequest', { requestId: 'r2' });
    await flush();
    expect(native.provideFirebaseToken.mock.calls).toEqual([
      ['r1', 'id-token-1'],
      ['r2', 'id-token-2'],
    ]);
  });

  it('answers null when the token function fails or returns nothing', async () => {
    api.Hailbell.identify({ firebaseIdToken: () => Promise.reject(new Error('signed out')) });
    native.emit('onFirebaseTokenRequest', { requestId: 'r1' });
    await flush();
    api.Hailbell.identify({ firebaseIdToken: () => undefined });
    native.emit('onFirebaseTokenRequest', { requestId: 'r2' });
    await flush();
    expect(native.provideFirebaseToken.mock.calls).toEqual([
      ['r1', null],
      ['r2', null],
    ]);
  });

  it('stops answering with a Firebase token after reset or a plain identify', async () => {
    const token = jest.fn(() => 'id-token');
    api.Hailbell.identify({ firebaseIdToken: token });
    api.Hailbell.reset();
    native.emit('onFirebaseTokenRequest', { requestId: 'r1' });
    await flush();
    expect(token).not.toHaveBeenCalled();
    expect(native.provideFirebaseToken).toHaveBeenCalledWith('r1', null);
    expect(native.reset).toHaveBeenCalled();
  });
});

describe('metadata, signals and placements', () => {
  it('sends metadata as text and leaves out empty values', () => {
    api.Hailbell.setMetadata({ plan: 'pro', seats: 3, trial: false, superwallUserId: null });
    expect(native.setMetadata).toHaveBeenCalledWith({ plan: 'pro', seats: '3', trial: 'false' });
  });

  it('tracks signals with or without properties', () => {
    api.Hailbell.track('chat_opened', { source: 'profile' });
    api.Hailbell.track('paywall_decline');
    expect(native.track.mock.calls).toEqual([
      ['chat_opened', { source: 'profile' }],
      ['paywall_decline', null],
    ]);
  });

  it('reports placements', () => {
    api.Hailbell.setPaywallPlacements(['winback', 'support_offer']);
    expect(native.setPaywallPlacements).toHaveBeenCalledWith(['winback', 'support_offer']);
  });
});

describe('onPaywall', () => {
  it('opens the paywall for a tapped offer and reports one purchase', () => {
    const handler = jest.fn((_placement: string, purchased: () => void) => {
      purchased();
      purchased();
    });
    api.Hailbell.onPaywall = handler;
    expect(native.setPaywallHandler).toHaveBeenLastCalledWith(true);

    native.emit('onPaywall', { placement: 'winback', requestId: 'p1' });
    expect(handler).toHaveBeenCalledWith('winback', expect.any(Function));
    expect(native.paywallPurchased).toHaveBeenCalledTimes(1);
    expect(native.paywallPurchased).toHaveBeenCalledWith('p1');
  });

  it('turns offers off again with null', () => {
    api.Hailbell.onPaywall = () => {};
    api.Hailbell.onPaywall = null;
    expect(native.setPaywallHandler).toHaveBeenLastCalledWith(false);
    expect(native.listenerCount('onPaywall')).toBe(0);
    expect(api.Hailbell.onPaywall).toBeNull();
  });

  it('keeps one listener when the handler is replaced', () => {
    api.Hailbell.onPaywall = () => {};
    api.Hailbell.onPaywall = () => {};
    expect(native.listenerCount('onPaywall')).toBe(1);
  });

  it('survives a handler that throws', () => {
    api.Hailbell.onPaywall = () => {
      throw new Error('paywall SDK not ready');
    };
    expect(() => native.emit('onPaywall', { placement: 'x', requestId: 'p' })).not.toThrow();
    expect(console.error).toHaveBeenCalled();
  });
});

describe('setPushToken', () => {
  it('takes a token string', () => {
    api.Hailbell.setPushToken('a1b2c3');
    expect(native.setPushToken).toHaveBeenCalledWith('a1b2c3', null);
  });

  it("takes expo-notifications' device token object and an explicit sandbox", () => {
    api.Hailbell.setPushToken({ type: 'ios', data: 'deadbeef' }, { sandbox: true });
    expect(native.setPushToken).toHaveBeenCalledWith('deadbeef', true);
  });

  it('refuses an Expo push token, which Hailbell cannot send to', () => {
    api.Hailbell.setPushToken('ExponentPushToken[abc]');
    expect(native.setPushToken).not.toHaveBeenCalled();
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('getDevicePushTokenAsync'));
  });

  it('warns when the native side rejects the token for the platform', () => {
    native.setPushToken.mockReturnValueOnce(false);
    api.Hailbell.setPushToken('fcm:token');
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('APNs'));
  });

  it('ignores empty tokens', () => {
    api.Hailbell.setPushToken(null);
    api.Hailbell.setPushToken('');
    expect(native.setPushToken).not.toHaveBeenCalled();
  });
});

describe('handleNotification', () => {
  it('opens the chat for a tapped reply', () => {
    native.handleTap.mockResolvedValueOnce({ kind: 'chat' });
    const response = {
      actionIdentifier: 'expo.modules.notifications.actions.DEFAULT',
      notification: { request: { identifier: 'r', content: {}, trigger: { payload: { hailbell: { conversationId: 'c1' } } } } },
    };
    expect(api.Hailbell.handleNotification(response)).toBe(true);
    expect(native.handleTap).toHaveBeenCalledWith({ hailbell: { conversationId: 'c1' } }, null);
  });

  it('returns false for other notifications', () => {
    expect(api.Hailbell.handleNotification({ data: { type: 'daily_verse' } })).toBe(false);
    expect(native.handleTap).not.toHaveBeenCalled();
  });

  it('gives a tapped campaign to onOpen', async () => {
    const onOpen = jest.fn(() => true);
    api.HailbellPush.onOpen = onOpen;
    native.handleTap.mockResolvedValueOnce({ kind: 'campaign', id: 'ntf_1', url: 'myapp://offer', actionId: 'b1' });
    const open = jest.spyOn(Linking, 'openURL');

    api.Hailbell.handleNotification({ data: { hb: { n: 'ntf_1' } } });
    await flush();
    expect(onOpen).toHaveBeenCalledWith({ id: 'ntf_1', url: 'myapp://offer', actionId: 'b1', imageUrl: undefined, buttons: [], data: {} });
    expect(open).not.toHaveBeenCalled();
  });

  it('opens the link when onOpen leaves it, or when there is no onOpen', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    native.handleTap.mockResolvedValue({ kind: 'campaign', id: 'ntf_1', url: 'https://example.com/sale' });

    api.Hailbell.handleNotification({ data: { hb: { n: 'ntf_1' } } });
    await flush();
    api.HailbellPush.onOpen = () => false;
    api.Hailbell.handleNotification({ data: { hb: { n: 'ntf_1' } } });
    await flush();
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('does nothing more for a tap the native delegate already handled', async () => {
    const open = jest.spyOn(Linking, 'openURL');
    native.handleTap.mockResolvedValueOnce({ kind: 'duplicate' });
    api.Hailbell.handleNotification({ data: { hb: { n: 'ntf_1', u: 'https://example.com' } } });
    await flush();
    expect(open).not.toHaveBeenCalled();
  });

  it('sends campaign data to iOS as the object APNs delivers', () => {
    api.Hailbell.handleNotification({ data: { hb: '{"n":"ntf_1"}' } });
    expect(native.handleTap).toHaveBeenCalledWith({ hb: { n: 'ntf_1' } }, null);
  });

  it('on Android opens the chat for a reply a push library showed', () => {
    onPlatform('android');
    api.Hailbell.handleNotification({ data: { hailbell: '1' } });
    expect(native.handleTap).toHaveBeenCalledWith({ hailbell: '1' }, null);
  });
});

describe('push opens from the native notification delegate', () => {
  it('reach onOpen while it is set', () => {
    const onOpen = jest.fn(() => true);
    api.HailbellPush.onOpen = onOpen;
    expect(native.setPushOpenHandler).toHaveBeenLastCalledWith(true);
    native.emit('onPushOpen', { kind: 'campaign', id: 'ntf_2', url: 'myapp://x' });
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 'ntf_2' }));

    api.HailbellPush.onOpen = null;
    expect(native.setPushOpenHandler).toHaveBeenLastCalledWith(false);
    expect(native.listenerCount('onPushOpen')).toBe(0);
  });
});

describe('deleteData', () => {
  it('resolves when the server deleted the data', async () => {
    await expect(api.Hailbell.deleteData()).resolves.toBeUndefined();
  });

  it('rejects when the server could not be reached', async () => {
    native.deleteData.mockRejectedValueOnce(new Error('offline'));
    await expect(api.Hailbell.deleteData()).rejects.toThrow('offline');
  });
});

describe('settings', () => {
  it('loads a bundled team photo from its resolved URI', async () => {
    jest.spyOn(Image, 'resolveAssetSource').mockReturnValue({ uri: 'http://localhost:8081/assets/photo.png', width: 1, height: 1, scale: 2 });
    api.Hailbell.teamPhoto = 42;
    expect(native.setTeamPhoto).toHaveBeenCalledWith('http://localhost:8081/assets/photo.png');
    expect(api.Hailbell.teamPhoto).toBe(42);
  });

  it('takes a URI, and null for the dashboard photo', () => {
    api.Hailbell.teamPhoto = 'https://example.com/me.jpg';
    api.Hailbell.teamPhoto = null;
    expect(native.setTeamPhoto.mock.calls).toEqual([['https://example.com/me.jpg'], [null]]);
  });

  it('warns when the photo could not be loaded', async () => {
    native.setTeamPhoto.mockResolvedValueOnce(false);
    api.Hailbell.teamPhoto = 'file:///missing.png';
    await flush();
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('team photo'));
  });

  it('sets darkMode and asksForNotifications', () => {
    api.Hailbell.darkMode = true;
    api.Hailbell.darkMode = null;
    api.Hailbell.asksForNotifications = false;
    expect(native.setDarkMode.mock.calls).toEqual([[true], [null]]);
    expect(native.setAsksForNotifications).toHaveBeenCalledWith(false);
    expect(api.Hailbell.asksForNotifications).toBe(false);
  });
});

describe('without the native module (Expo Go, web)', () => {
  it('does nothing and throws nothing', async () => {
    const bare = loadWith(null);
    expect(() => {
      bare.Hailbell.configure({ appId: 'app_1' });
      bare.Hailbell.open();
      bare.Hailbell.identify({ userId: 'u' });
      bare.Hailbell.onPaywall = () => {};
      bare.HailbellPush.setTags({ a: '1' });
    }).not.toThrow();
    expect(bare.Hailbell.unreadCount).toBe(0);
    expect(bare.Hailbell.handleNotification({ hailbell: '1' })).toBe(true);
    await expect(bare.HailbellPush.canNotify()).resolves.toBe(false);
    await expect(bare.Hailbell.deleteData()).resolves.toBeUndefined();
  });
});
