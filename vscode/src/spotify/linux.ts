import { spawn } from 'child_process';
import { run, which } from './exec';
import { NOT_RUNNING, SpotifyPermissionProblem, SpotifyService, SpotifyState } from './types';

const PLAYERCTL = ['--player=spotify'];
const DBUS_DEST = ['--print-reply', '--dest=org.mpris.MediaPlayer2.spotify', '/org/mpris/MediaPlayer2'];

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/** Kotlin toDoubleOrNull-ish: whole string must be a finite number. */
function toNumberOrNull(s: string | null | undefined): number | null {
  if (s == null || s.trim() === '') return null;
  const n = Number(s.trim());
  return Number.isFinite(n) ? n : null;
}

const clampPercent = (v: number) => Math.min(100, Math.max(0, Math.trunc(v * 100)));

export class SpotifyLinuxService implements SpotifyService {
  /** Set the first time a D-Bus call is blocked by AppArmor (snap Spotify). */
  private snapBlocked = false;

  // Whether playerctl is available on this system (checked once lazily)
  private playerctlPromise: Promise<boolean> | null = null;

  get permissionProblem(): SpotifyPermissionProblem {
    return this.snapBlocked ? 'linuxSnap' : null;
  }

  private hasPlayerctl(): Promise<boolean> {
    if (!this.playerctlPromise) this.playerctlPromise = which('playerctl');
    return this.playerctlPromise;
  }

  async getCurrentTrack(): Promise<SpotifyState> {
    if (!(await this.isSpotifyRunning())) return { ...NOT_RUNNING };

    const [status, trackInfo, volume, positionMs, durationMs] = await Promise.all([
      this.getPlaybackStatus(),
      this.getTrackInfo(),
      this.getVolume(),
      this.getPositionMs(),
      this.getDurationMs(),
    ]);
    return { isRunning: true, isPlaying: status === 'Playing', trackInfo, volume, positionMs, durationMs };
  }

  async playPause(): Promise<void> {
    if (!(await this.isSpotifyRunning())) {
      await this.launchSpotifyAndPlay();
      return;
    }
    await this.playerCommand('play-pause', 'PlayPause');
  }

  async nextTrack(): Promise<void> {
    await this.playerCommand('next', 'Next');
  }

  async previousTrack(): Promise<void> {
    await this.playerCommand('previous', 'Previous');
  }

  async getVolume(): Promise<number> {
    if (await this.hasPlayerctl()) {
      const v = toNumberOrNull(await this.runCommand('playerctl', [...PLAYERCTL, 'volume']));
      return v === null ? 50 : clampPercent(v);
    }
    // dbus-send output: "   variant       double 0.5"
    const output = await this.getDbusProperty('Volume');
    const v = toNumberOrNull(output?.match(/double\s+([\d.]+)/)?.[1]);
    return v === null ? 50 : clampPercent(v);
  }

  async setVolume(volume: number): Promise<void> {
    const safeVolume = Math.min(100, Math.max(0, Math.round(volume)));
    const linuxVolume = (safeVolume / 100).toFixed(2);
    if (await this.hasPlayerctl()) {
      await this.runCommand('playerctl', [...PLAYERCTL, 'volume', linuxVolume]);
    } else {
      await this.runCommand('dbus-send', [
        ...DBUS_DEST,
        'org.freedesktop.DBus.Properties.Set',
        'string:org.mpris.MediaPlayer2.Player',
        'string:Volume',
        `variant:double:${linuxVolume}`,
      ]);
    }
  }

  async increaseVolume(amount = 10): Promise<void> {
    await this.setVolume((await this.getVolume()) + amount);
  }

  async decreaseVolume(amount = 10): Promise<void> {
    await this.setVolume((await this.getVolume()) - amount);
  }

  // ── Launch ────────────────────────────────────────────────────────────────

  private async launchSpotifyAndPlay(): Promise<void> {
    await this.launchSpotify();
    if (!(await this.waitForSpotify(20_000))) return; // Spotify did not start within 20 seconds
    // The D-Bus/playerctl interface registers after the process appears, and a slow cold
    // start can ignore the first Play — retry until Spotify reports Playing (up to ~10 s)
    for (let i = 0; i < 10; i++) {
      await sleep(1000);
      await this.playerCommand('play', 'Play');
      await sleep(300);
      if ((await this.getPlaybackStatus()) === 'Playing') return;
    }
  }

  /**
   * Launches Spotify using the first method that works:
   * 1. xdg-open spotify: — works for native, snap, and flatpak via URI handler
   * 2. Direct binary: spotify / flatpak run / snap run
   */
  private async launchSpotify(): Promise<void> {
    if (await which('xdg-open')) {
      spawnDetached('xdg-open', ['spotify:']);
      return;
    }
    const candidates: [string, string[]][] = [
      ['spotify', []],
      ['flatpak', ['run', 'com.spotify.Client']],
      ['snap', ['run', 'spotify']],
    ];
    for (const [cmd, args] of candidates) {
      if (await which(cmd)) {
        spawnDetached(cmd, args);
        return;
      }
    }
    // No Spotify launcher found (tried xdg-open, spotify, flatpak, snap)
  }

  /** Polls every 500 ms for Spotify to appear — via pgrep or playerctl (native, snap, flatpak). */
  private async waitForSpotify(timeoutMs: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if ((await this.isSpotifyRunning()) || (await this.isSpotifyVisibleToPlayerctl())) return true;
      await sleep(500);
    }
    return false;
  }

  private async isSpotifyVisibleToPlayerctl(): Promise<boolean> {
    if (!(await this.hasPlayerctl())) return false;
    const output = await this.runCommand('playerctl', ['-l']);
    return output !== null && output.toLowerCase().includes('spotify');
  }

  // ── Queries ───────────────────────────────────────────────────────────────

  private async isSpotifyRunning(): Promise<boolean> {
    const result =
      (await this.runCommand('pgrep', ['-x', 'spotify'])) ?? (await this.runCommand('pgrep', ['-x', 'Spotify']));
    return result !== null;
  }

  private async getPlaybackStatus(): Promise<string | null> {
    if (await this.hasPlayerctl()) {
      return this.runCommand('playerctl', [...PLAYERCTL, 'status']);
    }
    // dbus-send output: '   variant       string "Playing"'
    const output = await this.getDbusProperty('PlaybackStatus');
    return output?.match(/string\s+"(Playing|Paused|Stopped)"/)?.[1] ?? null;
  }

  private async getTrackInfo(): Promise<string | null> {
    if (await this.hasPlayerctl()) {
      const [artist, title] = await Promise.all([
        this.runCommand('playerctl', [...PLAYERCTL, 'metadata', 'xesam:artist']),
        this.runCommand('playerctl', [...PLAYERCTL, 'metadata', 'xesam:title']),
      ]);
      return joinArtistTitle(artist, title);
    }
    return this.parseMetadataFromDbus();
  }

  // Reads Metadata dict from D-Bus and extracts artist + title.
  // dbus-send output uses nested dict entries like:
  //   string "xesam:artist"
  //   variant   array [
  //         string "Artist Name"
  //      ]
  //   string "xesam:title"
  //   variant   string "Track Name"
  private async parseMetadataFromDbus(): Promise<string | null> {
    const output = await this.getDbusProperty('Metadata');
    if (output === null) return null;
    // [\s\S] crosses newlines; bounded lookahead avoids runaway matching
    const artist = output.match(/"xesam:artist"[\s\S]{1,400}?"([^"]+)"/)?.[1] ?? null;
    const title = output.match(/"xesam:title"[\s\S]{1,400}?"([^"]+)"/)?.[1] ?? null;
    return joinArtistTitle(artist, title);
  }

  private async getPositionMs(): Promise<number> {
    if (await this.hasPlayerctl()) {
      // playerctl position returns seconds as a float
      const s = toNumberOrNull(await this.runCommand('playerctl', [...PLAYERCTL, 'position']));
      return s === null ? 0 : Math.trunc(s * 1000);
    }
    // D-Bus Position is in microseconds
    const output = await this.getDbusProperty('Position');
    const us = toNumberOrNull(output?.match(/int64\s+(\d+)/)?.[1]);
    return us === null ? 0 : Math.trunc(us / 1000);
  }

  private async getDurationMs(): Promise<number> {
    let us: number | null;
    if (await this.hasPlayerctl()) {
      // mpris:length is in microseconds
      const raw = await this.runCommand('playerctl', [...PLAYERCTL, 'metadata', 'mpris:length']);
      us = raw !== null && /^[+-]?\d+$/.test(raw) ? Number(raw) : null;
    } else {
      // Metadata dict contains mpris:length as int64 microseconds
      const output = await this.getDbusProperty('Metadata');
      us = toNumberOrNull(output?.match(/"mpris:length"[\s\S]{1,100}?int64\s+(\d+)/)?.[1]);
    }
    return us === null ? 0 : Math.trunc(us / 1000);
  }

  // ── Plumbing ──────────────────────────────────────────────────────────────

  /** playerctl `<cmd>` if available, else the MPRIS `<method>` over dbus-send. */
  private async playerCommand(playerctlCmd: string, mprisMethod: string): Promise<void> {
    if (await this.hasPlayerctl()) {
      await this.runCommand('playerctl', [...PLAYERCTL, playerctlCmd]);
    } else {
      await this.runCommand('dbus-send', [...DBUS_DEST, `org.mpris.MediaPlayer2.Player.${mprisMethod}`]);
    }
  }

  private getDbusProperty(property: string): Promise<string | null> {
    return this.runCommand('dbus-send', [
      ...DBUS_DEST,
      'org.freedesktop.DBus.Properties.Get',
      'string:org.mpris.MediaPlayer2.Player',
      `string:${property}`,
    ]);
  }

  /**
   * Runs [cmd] and returns trimmed stdout, or null on failure/timeout/blank output.
   * Checks stderr so AppArmor/snap errors set [permissionProblem].
   */
  private async runCommand(cmd: string, args: string[]): Promise<string | null> {
    const r = await run(cmd, args);
    if (r === null) return null;
    if (r.stderr.includes('AppArmor') || r.stderr.includes('AccessDenied')) {
      this.snapBlocked = true;
      return null;
    }
    const out = r.stdout.trim();
    return out !== '' ? out : null;
  }
}

function joinArtistTitle(artist: string | null, title: string | null): string | null {
  const a = artist?.trim() ?? '';
  const t = title?.trim() ?? '';
  if (a !== '' && t !== '') return `${a} - ${t}`;
  return t !== '' ? t : null;
}

/**
 * The environment Spotify should be launched with. Snap-packaged VS Code leaks its sandbox
 * variables (GTK_PATH, GIO_MODULE_DIR, …) into child processes and keeps the originals in
 * `<NAME>_VSCODE_SNAP_ORIG`; a Spotify started with the leaked values misbehaves (e.g. resumes
 * playback on its own after being paused), so restore the originals.
 */
export function launchEnv(env: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const SUFFIX = '_VSCODE_SNAP_ORIG';
  const out: NodeJS.ProcessEnv = { ...env };
  for (const [key, orig] of Object.entries(env)) {
    if (!key.endsWith(SUFFIX)) continue;
    const name = key.slice(0, -SUFFIX.length);
    if (orig) out[name] = orig; else delete out[name];
    delete out[key];
  }
  for (const key of Object.keys(out)) {
    if (key === 'SNAP' || key.startsWith('SNAP_')) delete out[key];
  }
  return out;
}

/** Fire-and-forget launch that outlives the extension host. */
function spawnDetached(cmd: string, args: string[]): void {
  try {
    const child = spawn(cmd, args, { detached: true, stdio: 'ignore', env: launchEnv() });
    child.on('error', () => undefined);
    child.unref();
  } catch {
    // ignore — waitForSpotify will time out
  }
}
