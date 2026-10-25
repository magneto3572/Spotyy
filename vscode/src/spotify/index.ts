import { SpotifyLinuxService } from './linux';
import { SpotifyMacService } from './mac';
import { NOT_RUNNING, SpotifyPermissionProblem, SpotifyService, SpotifyState } from './types';

export * from './types';

/** Used on unsupported platforms: always reports Spotify as not running. */
class NoOpSpotifyService implements SpotifyService {
  readonly permissionProblem: SpotifyPermissionProblem = null;
  async getCurrentTrack(): Promise<SpotifyState> {
    return { ...NOT_RUNNING };
  }
  async playPause(): Promise<void> {}
  async nextTrack(): Promise<void> {}
  async previousTrack(): Promise<void> {}
  async getVolume(): Promise<number> {
    return NOT_RUNNING.volume;
  }
  async setVolume(): Promise<void> {}
  async increaseVolume(): Promise<void> {}
  async decreaseVolume(): Promise<void> {}
}

export function createSpotifyService(): SpotifyService {
  switch (process.platform) {
    case 'darwin':
      return new SpotifyMacService();
    case 'linux':
      return new SpotifyLinuxService();
    default:
      return new NoOpSpotifyService();
  }
}
