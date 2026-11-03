import { execFileSync } from 'child_process';
import path from 'path';

// The React Native package compiles copies of the native SDKs. This fails when they drift apart,
// for example after a change to sdk/swift or sdk/android without `npm run sync-native`.
it('ships the same native code as sdk/swift and sdk/android', () => {
  const script = path.join(__dirname, '..', '..', 'scripts', 'sync-native.mjs');
  expect(() => execFileSync(process.execPath, [script, '--check'], { stdio: 'pipe' })).not.toThrow();
});
