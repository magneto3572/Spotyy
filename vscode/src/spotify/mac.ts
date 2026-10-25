import { run } from './exec';
import { NOT_RUNNING, SpotifyPermissionProblem, SpotifyService, SpotifyState } from './types';

const CURRENT_TRACK_SCRIPT = `set spotifyRunning to false
tell application "System Events"
    set spotifyRunning to (exists process "Spotify")
end tell
if spotifyRunning then
    tell application "Spotify"
        try
            set ps to player state as string
            set vol to sound volume as integer
            set pos to player position as real
            try
                set trackArtist to artist of current track
                set trackName to name of current track
                set dur to duration of current track as integer
                return "1|" & ps & "|" & vol & "|" & trackArtist & " - " & trackName & "|" & pos & "|" & dur
            on error
                return "1|" & ps & "|" & vol & "||" & pos & "|0"
            end try
        on error
            return "1|stopped|50||0|0"
        end try
    end tell
else
    return "0|stopped|50||0|0"
end if`;

/** Kotlin-style split(sep, limit): at most limit-1 splits, remainder kept in the last field. */
export function splitLimit(s: string, sep: string, limit: number): string[] {
  const parts: string[] = [];
  let rest = s;
  while (parts.length < limit - 1) {
    const i = rest.indexOf(sep);
    if (i < 0) break;
    parts.push(rest.slice(0, i));
    rest = rest.slice(i + sep.length);
  }
  parts.push(rest);
  return parts;
}

/** Kotlin toIntOrNull semantics (no whitespace, no decimals). */
function toIntOrNull(s: string | undefined): number | null {
  return s !== undefined && /^[+-]?\d+$/.test(s) ? parseInt(s, 10) : null;
}

/** Kotlin toDoubleOrNull-ish: whole string must be a finite number. */
function toDoubleOrNull(s: string | undefined): number | null {
  if (s === undefined || s.trim() === '') return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

export class SpotifyMacService implements SpotifyService {
  /** Set once macOS Automation permission is denied for this process. */
  private automationBlocked = false;

  get permissionProblem(): SpotifyPermissionProblem {
    return this.automationBlocked ? 'macAutomation' : null;
  }

  async getCurrentTrack(): Promise<SpotifyState> {
    const result = await this.runAppleScript(CURRENT_TRACK_SCRIPT);
    if (result === null) return { ...NOT_RUNNING };

    const parts = splitLimit(result, '|', 6);
    if (parts.length < 4) return { ...NOT_RUNNING };

    const isRunning = parts[0] === '1';
    const isPlaying = parts[1] === 'playing';
    const volume = toIntOrNull(parts[2]) ?? 50;
    const track = parts[3].trim();
    const trackInfo = track !== '' ? track : null;
    const positionMs = Math.trunc((toDoubleOrNull(parts[4]) ?? 0) * 1000);
    // AppleScript duration is in milliseconds already
    const durationMs = toIntOrNull(parts[5]) ?? 0;

    return { isRunning, isPlaying, trackInfo, volume, positionMs, durationMs };
  }

  async playPause(): Promise<void> {
    await this.runAppleScript('tell application "Spotify"\n    playpause\nend tell');
  }

  async nextTrack(): Promise<void> {
    await this.runAppleScript('tell application "Spotify"\n    next track\nend tell');
  }

  async previousTrack(): Promise<void> {
    await this.runAppleScript('tell application "Spotify"\n    previous track\nend tell');
  }

  async getVolume(): Promise<number> {
    const result = await this.runAppleScript('tell application "Spotify"\n    return sound volume\nend tell');
    return toIntOrNull(result ?? undefined) ?? 50; // Default to 50% if we can't get the volume
  }

  async setVolume(volume: number): Promise<void> {
    const safeVolume = Math.round(Math.min(100, Math.max(0, volume)));
    await this.runAppleScript(`tell application "Spotify"\n    set sound volume to ${safeVolume}\nend tell`);
  }

  async increaseVolume(amount = 10): Promise<void> {
    await this.setVolume(Math.min(100, (await this.getVolume()) + amount));
  }

  async decreaseVolume(amount = 10): Promise<void> {
    await this.setVolume(Math.max(0, (await this.getVolume()) - amount));
  }

  /** Runs `osascript -e script`; returns trimmed stdout, or null on error/timeout. */
  private async runAppleScript(script: string): Promise<string | null> {
    const r = await run('osascript', ['-e', script]);
    if (r === null) return null;

    if (r.stderr.trim() !== '') {
      const err = r.stderr.toLowerCase();
      if (err.includes('-1743') || err.includes('not authorized') || err.includes('automation')) {
        this.automationBlocked = true;
      }
    }
    if (r.stdout.startsWith('execution error') || (r.code !== 0 && r.stdout.trim() === '')) {
      return null;
    }
    return r.stdout.trim();
  }
}
