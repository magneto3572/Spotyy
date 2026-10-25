/** Mirrors SpotifyState in the JetBrains plugin. trackInfo format: "Artist - Track Name". */
export interface SpotifyState {
  isRunning: boolean;
  isPlaying: boolean;
  trackInfo: string | null;
  volume: number;
  positionMs: number;
  durationMs: number;
}

export const NOT_RUNNING: SpotifyState = {
  isRunning: false,
  isPlaying: false,
  trackInfo: null,
  volume: 50,
  positionMs: 0,
  durationMs: 0,
};

/**
 * A platform problem detected while talking to Spotify, surfaced once as a notification.
 * - macAutomation: macOS denied AppleScript automation of Spotify (error -1743)
 * - linuxSnap: snap Spotify's AppArmor policy blocked D-Bus
 */
export type SpotifyPermissionProblem = 'macAutomation' | 'linuxSnap' | null;

/** All methods are async and must never throw — failures resolve to defaults. */
export interface SpotifyService {
  getCurrentTrack(): Promise<SpotifyState>;
  playPause(): Promise<void>;
  nextTrack(): Promise<void>;
  previousTrack(): Promise<void>;
  getVolume(): Promise<number>;
  setVolume(volume: number): Promise<void>;
  increaseVolume(amount?: number): Promise<void>;
  decreaseVolume(amount?: number): Promise<void>;
  readonly permissionProblem: SpotifyPermissionProblem;
}
