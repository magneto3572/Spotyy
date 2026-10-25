import * as vscode from 'vscode';
import { SpotifyState } from './spotify/types';
import { SpotyyPeer } from './network';
import { FocusRoom } from './focusRoom';

/** Peer/room info needed to render the peers item — mirrors the widget's peersButton states. */
export interface PeersStatus {
  ghostMode: boolean;
  peers: SpotyyPeer[];
  songMatches: SpotyyPeer[];
  room: FocusRoom | null;
}

const GREEN = new vscode.ThemeColor('charts.green');
const RING_STEPS = 32;

/**
 * Status bar layout — matches the JetBrains widget (right side, left → right):
 *   [logo] Spotyy  |  Artist - Track   ‹  (▶)  ›   🔊   👥 3
 * Icons come from media/spotyy-icons.woff (the JetBrains SVGs, contributed in package.json).
 * Higher priority = further left within the right-aligned group.
 */
export class SpotyyStatusBar implements vscode.Disposable {
  private readonly shown = new Set<vscode.StatusBarItem>();
  private readonly logo = this.item(112, '$(spotyy-logo)', 'Spotyy — show listening nearby', 'spotyy.showPeers');
  private readonly track = this.item(111, 'Spotyy', 'Spotyy', 'spotyy.showPeers');
  private readonly prev = this.item(110, '$(spotyy-prev)', 'Previous track', 'spotyy.previous');
  private readonly playPause = this.item(109, '$(spotyy-play-dark-00)', 'Play', 'spotyy.playPause');
  private readonly next = this.item(108, '$(spotyy-next)', 'Next track', 'spotyy.next');
  private readonly volume = this.item(107, '$(spotyy-volume)', 'Volume', 'spotyy.setVolume');
  private readonly peers = this.item(106, '', '', 'spotyy.showPeers');

  constructor() {
    this.logo.color = GREEN;
    [this.logo, this.track, this.prev, this.playPause, this.next].forEach(i => this.setVisible(i, true));
  }

  update(state: SpotifyState, status: PeersStatus): void {
    const config = vscode.workspace.getConfiguration('spotyy.statusBar');

    // Progress ring: one glyph per 1/RING_STEPS of the track (see scripts/gen-play-icons.mjs)
    const progress = state.durationMs > 0 ? Math.min(1, Math.max(0, state.positionMs / state.durationMs)) : 0;
    const step = String(Math.round(progress * RING_STEPS)).padStart(2, '0');
    // Colour glyphs (COLR font) carry their own palette, one set per theme brightness
    const kind = vscode.window.activeColorTheme.kind;
    const theme = kind === vscode.ColorThemeKind.Light || kind === vscode.ColorThemeKind.HighContrastLight ? 'light' : 'dark';
    this.playPause.text = `$(spotyy-${state.isPlaying ? 'pause' : 'play'}-${theme}-${step})`;
    this.playPause.tooltip = state.durationMs > 0
      ? `${state.isPlaying ? 'Pause' : 'Play'} · ${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}`
      : state.isPlaying ? 'Pause' : 'Play';

    if (!state.isRunning || !state.trackInfo) {
      this.track.text = 'Spotyy';
      this.track.tooltip = state.isRunning ? 'Spotify — nothing playing' : 'Spotify is not running';
    } else {
      const max = config.get<number>('maxTrackLength', 40);
      this.track.text = `Spotyy  |  ${truncate(state.trackInfo, max)}`;
      this.track.tooltip = state.durationMs > 0
        ? `${state.trackInfo}\n${formatMs(state.positionMs)} / ${formatMs(state.durationMs)}`
        : state.trackInfo;
    }

    if (config.get<boolean>('showVolume', true) && state.isRunning) {
      this.volume.text = state.volume === 0 ? '$(spotyy-mute)' : '$(spotyy-volume)';
      this.volume.tooltip = `Volume: ${state.volume}%`;
      this.setVisible(this.volume, true);
    } else {
      this.setVisible(this.volume, false);
    }

    this.updatePeers(status);
  }

  private updatePeers({ ghostMode, peers, songMatches, room }: PeersStatus): void {
    const item = this.peers;
    item.color = undefined;
    if (room) {
      // Always show when in a Focus Room so guests can see the timer and leave
      const n = room.members.size;
      item.text = `$(watch) ${room.formattedTime()}`;
      item.color = GREEN;
      item.tooltip = `Focus Room · ${n} member${n !== 1 ? 's' : ''}`;
    } else if (ghostMode) {
      item.text = '$(eye-closed)';
      item.tooltip = "Ghost mode — you're invisible";
    } else if (songMatches.length > 0) {
      item.text = `✦ ${peers.length}`;
      item.color = GREEN;
      item.tooltip = `Vibing with ${songMatches[0].username}!`;
    } else if (peers.length > 0) {
      item.text = `$(person) ${peers.length}`;
      item.tooltip = `${peers.length} people listening nearby`;
    } else {
      this.setVisible(item, false);
      return;
    }
    this.setVisible(item, true);
  }

  /** Visible items' text, left → right. Used by integration tests. */
  snapshot(): string[] {
    return [this.logo, this.track, this.prev, this.playPause, this.next, this.volume, this.peers]
      .filter(i => this.shown.has(i))
      .map(i => i.text);
  }

  private setVisible(i: vscode.StatusBarItem, visible: boolean): void {
    if (visible) { i.show(); this.shown.add(i); } else { i.hide(); this.shown.delete(i); }
  }

  private item(priority: number, text: string, tooltip: string, command: string): vscode.StatusBarItem {
    const i = vscode.window.createStatusBarItem(`spotyy.${command}.${priority}`, vscode.StatusBarAlignment.Right, priority);
    i.name = 'Spotyy';
    i.text = text;
    i.tooltip = tooltip;
    i.command = command;
    return i;
  }

  dispose(): void {
    [this.logo, this.track, this.prev, this.playPause, this.next, this.volume, this.peers].forEach(i => i.dispose());
  }
}

function truncate(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

function formatMs(ms: number): string {
  const total = Math.floor(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
