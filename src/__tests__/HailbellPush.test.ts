import { Platform } from 'react-native';

import { createFakeNative, loadWith, type FakeNative } from './fakeNative';

let native: FakeNative;
let api: ReturnType<typeof loadWith>;
const os = Platform.OS;

beforeEach(() => {
  native = createFakeNative();
  api = loadWith(native);
});

afterEach(() => {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
});

describe('HailbellPush', () => {
  it('sets tags and removes the ones set to null', () => {
    api.HailbellPush.setTags({ plan: 'pro', streak: 12, cuisine: null, skipped: undefined });
    expect(native.setTags).toHaveBeenCalledWith({ plan: 'pro', streak: '12' }, ['cuisine']);
  });

  it('passes marketing, opt-out, badge and channel settings', () => {
    api.HailbellPush.setMarketing(true);
    api.HailbellPush.optOut();
    api.HailbellPush.clearBadge('group.com.example.app');
    api.HailbellPush.clearBadge();
    api.HailbellPush.offersChannel = 'promotions';
    expect(native.setMarketing).toHaveBeenCalledWith(true);
    expect(native.optOut).toHaveBeenCalled();
    expect(native.clearBadge.mock.calls).toEqual([['group.com.example.app'], [null]]);
    expect(native.setOffersChannel).toHaveBeenCalledWith('promotions');
    expect(api.HailbellPush.offersChannel).toBe('promotions');
  });

  it('asks for permission, provisionally if asked', async () => {
    await expect(api.HailbellPush.requestPermission()).resolves.toBe(true);
    await api.HailbellPush.requestPermission({ provisional: true });
    expect(native.requestPushPermission.mock.calls).toEqual([[false], [true]]);
    native.canNotify.mockResolvedValueOnce(false);
    await expect(api.HailbellPush.canNotify()).resolves.toBe(false);
  });

  it('knows Hailbell pushes in any library shape', () => {
    expect(api.HailbellPush.isHailbell({ data: { hailbell: '1' } })).toBe(true);
    expect(api.HailbellPush.isHailbell({ request: { content: {}, trigger: { payload: { hb: {} } } } })).toBe(true);
    expect(api.HailbellPush.isHailbell({ data: { type: 'news' } })).toBe(false);
    expect(api.HailbellPush.isHailbell(undefined)).toBe(false);
  });

  describe('handleMessage', () => {
    it("leaves other apps' pushes alone", async () => {
      await expect(api.HailbellPush.handleMessage({ data: { type: 'news' } })).resolves.toBe(false);
      await expect(api.HailbellPush.handleMessage({ data: { hb_check: '1' } })).resolves.toBe(false);
      expect(native.handleMessage).not.toHaveBeenCalled();
    });

    it('on Android hands the FCM data, title and body to the SDK as strings', async () => {
      Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
      const message = {
        data: { hb: '{"n":"ntf_1"}', hb_a: '{"t":"Sale"}' },
        notification: { title: 'Sale', body: '50% off' },
      };
      await expect(api.HailbellPush.handleMessage(message)).resolves.toBe(true);
      expect(native.handleMessage).toHaveBeenCalledWith({ hb: '{"n":"ntf_1"}', hb_a: '{"t":"Sale"}' }, 'Sale', '50% off');
    });

    it('on Android reads an expo-notifications background task payload', async () => {
      Object.defineProperty(Platform, 'OS', { value: 'android', configurable: true });
      await api.HailbellPush.handleMessage({ notification: null, data: { hb: '{"n":"ntf_1"}', dataString: null } });
      expect(native.handleMessage).toHaveBeenCalledWith({ hb: '{"n":"ntf_1"}' }, null, null);
    });

    it('returns what the native side says', async () => {
      native.handleMessage.mockResolvedValueOnce(false);
      await expect(api.HailbellPush.handleMessage({ hailbell: { conversationId: 'c1' } })).resolves.toBe(false);
    });
  });
});
