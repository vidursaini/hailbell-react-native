// react-test-renderer 19 ships no types, and @types/react-test-renderer stopped at React 18.
// The few calls the hook test makes:
declare module 'react-test-renderer' {
  import type { ReactElement } from 'react';
  export interface ReactTestRenderer {
    unmount(): void;
  }
  export function create(element: ReactElement): ReactTestRenderer;
  export function act(callback: () => void): void;
}
