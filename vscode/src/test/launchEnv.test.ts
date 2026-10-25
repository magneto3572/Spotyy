import { test } from 'node:test';
import * as assert from 'node:assert';
import { launchEnv } from '../spotify/linux';

test('launchEnv restores snap-overridden vars and drops SNAP_*', () => {
  const env = launchEnv({
    PATH: '/usr/bin',
    SNAP: '/snap/code/1',
    SNAP_NAME: 'code',
    GTK_PATH: '/snap/code/1/usr/lib/gtk-3.0',
    GTK_PATH_VSCODE_SNAP_ORIG: '',
    XDG_DATA_DIRS: '/snap/code/1/usr/share:/usr/share',
    XDG_DATA_DIRS_VSCODE_SNAP_ORIG: '/usr/share',
  });
  assert.deepStrictEqual(env, { PATH: '/usr/bin', XDG_DATA_DIRS: '/usr/share' });
});

test('launchEnv leaves a non-snap environment untouched', () => {
  assert.deepStrictEqual(launchEnv({ PATH: '/usr/bin', HOME: '/home/x' }), { PATH: '/usr/bin', HOME: '/home/x' });
});
