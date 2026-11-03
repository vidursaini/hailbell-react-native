import type { ReactTestRenderer } from 'react-test-renderer';

import { createFakeNative, loadWith, type FakeNative } from './fakeNative';

// react-test-renderer needs this flag to run act() outside a browser test environment.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

/** The package, React and the renderer from one module registry, so they share one copy of React. */
function load(native: FakeNative) {
  const api = loadWith(native);
  const React = require('react') as typeof import('react');
  const renderer = require('react-test-renderer') as typeof import('react-test-renderer');
  return { api, React, renderer };
}

beforeEach(() => {
  jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => jest.restoreAllMocks());

it('renders the current count and follows changes', () => {
  const native = createFakeNative();
  native.unread = 2;
  const { api, React, renderer } = load(native);
  const seen: number[] = [];
  function Badge() {
    seen.push(api.useHailbellUnread());
    return null;
  }

  let tree!: ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(React.createElement(Badge));
  });
  expect(seen.at(-1)).toBe(2);

  renderer.act(() => native.emit('onUnreadCountChange', { count: 5 }));
  expect(seen.at(-1)).toBe(5);

  renderer.act(() => tree.unmount());
  expect(native.listenerCount('onUnreadCountChange')).toBe(0);
});

it('calls unread listeners once per change and reads the count synchronously', () => {
  const native = createFakeNative();
  const { Hailbell } = loadWith(native);
  const listener = jest.fn();
  const subscription = Hailbell.addUnreadCountListener(listener);
  native.emit('onUnreadCountChange', { count: 1 });
  native.emit('onUnreadCountChange', { count: 1 });
  native.emit('onUnreadCountChange', { count: 0 });
  expect(listener.mock.calls).toEqual([[1], [0]]);
  expect(Hailbell.unreadCount).toBe(0);
  subscription.remove();
  expect(native.listenerCount('onUnreadCountChange')).toBe(0);
});
