import { isHailbellData, readPayload, toStringMap, toUserInfo } from '../payload';

const campaign = { n: 'ntf_1', u: 'myapp://offer', s: 'sub_1' };

describe('readPayload', () => {
  it('reads a React Native Firebase message on Android, where data values are strings', () => {
    const message = {
      messageId: '0:123',
      data: { hailbell: '1', conversationId: 'c1' },
      notification: { title: 'YourApp Support', body: 'Hi there' },
    };
    expect(readPayload(message)).toEqual({
      data: { hailbell: '1', conversationId: 'c1' },
      title: 'YourApp Support',
      body: 'Hi there',
      identifier: '0:123',
    });
  });

  it('reads a React Native Firebase message on iOS, where hb stays an object', () => {
    const read = readPayload({ data: { hb: campaign } });
    expect(read?.data.hb).toEqual(campaign);
  });

  it('reads an expo-notifications notification on iOS from the APNs payload', () => {
    const notification = {
      date: 1,
      request: {
        identifier: 'req-1',
        content: { title: 'YourApp Support', body: 'Reply', data: {} },
        trigger: { type: 'push', payload: { aps: { alert: 'Reply' }, hailbell: { conversationId: 'c1' } } },
      },
    };
    const read = readPayload(notification);
    expect(read?.data.hailbell).toEqual({ conversationId: 'c1' });
    expect(read?.title).toBe('YourApp Support');
    expect(read?.identifier).toBe('req-1');
  });

  it('reads an expo-notifications notification on Android from the FCM message', () => {
    const notification = {
      request: {
        identifier: 'req-2',
        content: { title: null, body: null, data: {} },
        trigger: {
          type: 'push',
          remoteMessage: {
            data: { hb: JSON.stringify(campaign), hb_a: '{"t":"Sale"}' },
            notification: { title: 'From FCM', body: 'Body' },
          },
        },
      },
    };
    const read = readPayload(notification);
    expect(read?.data.hb).toBe(JSON.stringify(campaign));
    expect(read?.title).toBe('From FCM');
    expect(read?.body).toBe('Body');
  });

  it('reads a tap on the notification itself without an action id', () => {
    const response = {
      actionIdentifier: 'expo.modules.notifications.actions.DEFAULT',
      notification: { request: { identifier: 'r', content: {}, trigger: { payload: { hb: campaign } } } },
    };
    expect(readPayload(response)?.actionId).toBeUndefined();
  });

  it('keeps the id of a tapped button', () => {
    const response = {
      actionIdentifier: 'b1',
      notification: { request: { identifier: 'r', content: {}, trigger: { payload: { hb: campaign } } } },
    };
    expect(readPayload(response)?.actionId).toBe('b1');
  });

  it('reads an expo-notifications background task payload', () => {
    const task = { notification: null, data: { hb: JSON.stringify(campaign), hb_s: 'sub_9', dataString: null } };
    expect(readPayload(task)?.data.hb_s).toBe('sub_9');
  });

  it('finds Hailbell data carried as a JSON string in dataString', () => {
    const task = { notification: null, data: { dataString: JSON.stringify({ hailbell: '1' }) } };
    expect(isHailbellData(readPayload(task)?.data)).toBe(true);
  });

  it('reads a bare data map or userInfo', () => {
    expect(readPayload({ hailbell: { conversationId: 'c1' } })?.data).toEqual({ hailbell: { conversationId: 'c1' } });
  });

  it('returns null for things that are not objects', () => {
    expect(readPayload(null)).toBeNull();
    expect(readPayload('hb')).toBeNull();
    expect(readPayload([1])).toBeNull();
  });
});

describe('isHailbellData', () => {
  it('knows chat replies and campaigns', () => {
    expect(isHailbellData({ hailbell: '1' })).toBe(true);
    expect(isHailbellData({ hb: '{}' })).toBe(true);
  });

  it("ignores other pushes, including Hailbell's silent uninstall check", () => {
    expect(isHailbellData({ type: 'daily_verse' })).toBe(false);
    expect(isHailbellData({ hb_check: '1' })).toBe(false);
    expect(isHailbellData(undefined)).toBe(false);
  });
});

describe('toStringMap', () => {
  it('turns values into the strings Android FCM data uses and drops empty ones', () => {
    expect(toStringMap({ hb: campaign, hailbell: '1', count: 2, gone: null, missing: undefined })).toEqual({
      hb: JSON.stringify(campaign),
      hailbell: '1',
      count: '2',
    });
  });
});

describe('toUserInfo', () => {
  it('turns hb text back into the object APNs delivers', () => {
    expect(toUserInfo({ hb: JSON.stringify(campaign) })).toEqual({ hb: campaign });
  });

  it('leaves other data alone', () => {
    const data = { hb: campaign, hailbell: '1' };
    expect(toUserInfo(data)).toBe(data);
  });
});
