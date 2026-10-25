import { execFile } from 'child_process';

export interface RunResult {
  stdout: string;
  stderr: string;
  code: number | null;
}

/**
 * Runs [cmd] with [args] (no shell). Resolves null on timeout (process is killed)
 * or if the binary can't be spawned. Never rejects.
 */
export function run(
  cmd: string,
  args: string[],
  opts: { timeoutMs?: number } = {},
): Promise<RunResult | null> {
  const timeout = opts.timeoutMs ?? 3000;
  return new Promise((resolve) => {
    try {
      execFile(cmd, args, { timeout, killSignal: 'SIGKILL', encoding: 'utf8' }, (err, stdout, stderr) => {
        if (!err) {
          resolve({ stdout, stderr, code: 0 });
          return;
        }
        const e = err as NodeJS.ErrnoException & { killed?: boolean; code?: number | string };
        // Timed out (killed) or failed to spawn (e.g. ENOENT)
        if (e.killed || typeof e.code !== 'number') {
          resolve(null);
          return;
        }
        resolve({ stdout: stdout ?? '', stderr: stderr ?? '', code: e.code });
      });
    } catch {
      resolve(null);
    }
  });
}

/** True if [bin] is on PATH (`which` prints a path). */
export async function which(bin: string): Promise<boolean> {
  const r = await run('which', [bin]);
  return r !== null && r.code === 0 && r.stdout.trim() !== '';
}
