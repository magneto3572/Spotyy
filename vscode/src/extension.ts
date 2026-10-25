import * as vscode from 'vscode';
import { createSpotifyService, NOT_RUNNING, SpotifyService, SpotifyState } from './spotify';
import { NetworkDiscoveryService } from './network';
import { FocusRoom, FocusRoomService } from './focusRoom';
import { SpotyyStatusBar, PeersStatus } from './statusBar';
import { PeersViewProvider } from './peersView';
import { PeersViewAction, PeersViewModel } from './viewModel';

const POLL_MS = 3000;
const REFRESH_MS = 1000;
const DURATIONS = [25, 45, 60];

/** Returned from activate() — read by the integration tests via `extension.exports`. */
export interface SpotyyTestApi {
  getModel(): PeersViewModel;
  getStatusBar(): string[];
  getSpotifyState(): SpotifyState;
  poll(): Promise<void>;
  act(action: PeersViewAction): void;
  rooms: FocusRoomService;
  network: NetworkDiscoveryService;
}

export function activate(context: vscode.ExtensionContext): SpotyyTestApi {
  const spotify = createSpotifyService();
  // SPOTYY_USERNAME overrides the broadcast name (dev/testing: run alongside a JetBrains IDE as the same OS user)
  const network = new NetworkDiscoveryService(context.globalState, { username: process.env.SPOTYY_USERNAME || undefined });
  const rooms = new FocusRoomService(network);
  network.roomMessageHandler = msg => rooms.handleMessage(msg);
  network.start();

  const statusBar = new SpotyyStatusBar();
  let lastState: SpotifyState = NOT_RUNNING;

  const peersStatus = (): PeersStatus => {
    const peers = network.getActivePeers();
    return {
      ghostMode: network.isGhostMode(),
      peers,
      songMatches: peers.filter(p => network.vibeMatch(p) === 'SAME_SONG'),
      room: rooms.currentRoom,
    };
  };

  const buildModel = (): PeersViewModel => {
    const room = rooms.currentRoom;
    const me = network.localUsername;
    return {
      localUsername: me,
      ghostMode: network.isGhostMode(),
      todayVibeCount: network.getTodayVibeCount(),
      peers: network.getActivePeers().map(p => ({
        username: p.username,
        track: p.track,
        match: network.vibeMatch(p),
        inMyRoom: room?.members.has(p.username) ?? false,
      })),
      room: room && {
        hostName: room.hostName,
        remaining: room.formattedTime(),
        isHost: rooms.isHost,
        members: [...room.members.values()]
          .map(m => ({ username: m.username, isMe: m.username === me, isHost: m.username === room.hostName }))
          .sort((a, b) => Number(b.isHost) - Number(a.isHost) || a.username.localeCompare(b.username)),
      },
      nearbyRooms: room ? [] : rooms.getNearbyRooms().map(r => ({
        roomId: r.roomId,
        hostName: r.hostName,
        memberCount: r.seenMembers.size,
        remaining: r.formattedTime(),
      })),
      durations: DURATIONS,
    };
  };

  const view = new PeersViewProvider(context.extensionUri, buildModel, action => handleAction(action));

  let lastPollAt = Date.now();
  // Between 3 s polls, advance the position locally so the progress ring moves every second
  const displayState = (): SpotifyState => !lastState.isPlaying ? lastState : {
    ...lastState,
    positionMs: Math.min(lastState.durationMs, lastState.positionMs + (Date.now() - lastPollAt)),
  };

  const refresh = (): void => {
    statusBar.update(displayState(), peersStatus());
    view.refresh();
  };

  const handleAction = (action: PeersViewAction): void => {
    switch (action.type) {
      case 'invite': rooms.invitePeer(action.username); break;
      case 'kick': rooms.kickPeer(action.username); break;
      case 'leave': rooms.leaveRoom(); break;
      case 'startRoom': if (!rooms.isInRoom) rooms.startRoom(action.minutes); break;
      case 'toggleGhost': if (!rooms.isInRoom) void network.setGhostMode(!network.isGhostMode()); break;
    }
    refresh();
  };

  rooms.onRoomInvite = room => void showInvite(room, rooms, refresh);
  rooms.onRoomEnded = () => {
    void vscode.window.showInformationMessage('Spotyy: Focus Room ended.');
    refresh();
  };

  // ── 3 s poll: Spotify state → broadcast → room heartbeat (same order as the JetBrains widget) ──
  let warnedPermission = false;
  const pollOnce = async (): Promise<void> => {
    lastState = await spotify.getCurrentTrack();
    lastPollAt = Date.now();
    network.broadcast(lastState.trackInfo ?? '', lastState.isPlaying);
    rooms.ping();
    // Record new song-level vibe matches (deduped inside the service)
    for (const p of network.getActivePeers()) {
      if (network.vibeMatch(p) === 'SAME_SONG') network.recordVibeMatch(p.username, p.track);
    }
    if (!warnedPermission && spotify.permissionProblem) {
      warnedPermission = true;
      void showPermissionWarning(spotify);
    }
    refresh();
  };

  // A poll already in flight may have read Spotify before the caller's change (e.g. a
  // play/pause click), so callers wait for it and then share one fresh poll queued after it.
  let inflight: Promise<void> | null = null;
  let queued: Promise<void> | null = null;
  const poll = (): Promise<void> => {
    if (!inflight) {
      inflight = pollOnce().finally(() => { inflight = null; });
      return inflight;
    }
    queued ??= inflight.catch(() => undefined).then(() => { queued = null; return poll(); });
    return queued;
  };

  // Run a control action, then re-poll so the status bar reflects it immediately
  const control = (fn: (s: SpotifyService) => Promise<void>) => async () => {
    await fn(spotify);
    await poll();
  };

  const pollTimer = setInterval(() => void poll(), POLL_MS);
  const refreshTimer = setInterval(refresh, REFRESH_MS);
  void poll();

  context.subscriptions.push(
    statusBar,
    vscode.window.registerWebviewViewProvider(PeersViewProvider.viewId, view),
    vscode.commands.registerCommand('spotyy.playPause', control(s => s.playPause())),
    vscode.commands.registerCommand('spotyy.next', control(s => s.nextTrack())),
    vscode.commands.registerCommand('spotyy.previous', control(s => s.previousTrack())),
    vscode.commands.registerCommand('spotyy.volumeUp', control(s => s.increaseVolume(10))),
    vscode.commands.registerCommand('spotyy.volumeDown', control(s => s.decreaseVolume(10))),
    vscode.commands.registerCommand('spotyy.setVolume', async () => {
      const picks = [100, 80, 60, 40, 20, 0].map(v => ({ label: `${v}%`, value: v }));
      const pick = await vscode.window.showQuickPick(picks, { placeHolder: `Volume (now ${lastState.volume}%)` });
      if (pick) await control(s => s.setVolume(pick.value))();
    }),
    vscode.commands.registerCommand('spotyy.showPeers', () =>
      vscode.commands.executeCommand(`${PeersViewProvider.viewId}.focus`)),
    vscode.commands.registerCommand('spotyy.toggleGhostMode', () => handleAction({ type: 'toggleGhost' })),
    vscode.commands.registerCommand('spotyy.startFocusRoom', async () => {
      if (rooms.isInRoom) {
        void vscode.window.showInformationMessage('Spotyy: You are already in a Focus Room.');
        return;
      }
      const pick = await vscode.window.showQuickPick(
        DURATIONS.map(m => ({ label: `${m} minutes`, minutes: m })),
        { placeHolder: 'Focus Room length — invite teammates from the Listening Nearby view' });
      if (pick) handleAction({ type: 'startRoom', minutes: pick.minutes });
    }),
    vscode.commands.registerCommand('spotyy.leaveFocusRoom', () => handleAction({ type: 'leave' })),
    vscode.workspace.onDidChangeConfiguration(e => { if (e.affectsConfiguration('spotyy')) refresh(); }),
    vscode.window.onDidChangeActiveColorTheme(() => refresh()),
    {
      dispose: () => {
        clearInterval(pollTimer);
        clearInterval(refreshTimer);
        if (rooms.isInRoom) rooms.leaveRoom();
        network.stop();
      },
    },
  );

  return {
    getModel: buildModel,
    getStatusBar: () => statusBar.snapshot(),
    getSpotifyState: () => lastState,
    poll,
    act: handleAction,
    rooms,
    network,
  };
}

export function deactivate(): void {
  // Cleanup runs through context.subscriptions
}

async function showInvite(room: FocusRoom, rooms: FocusRoomService, refresh: () => void): Promise<void> {
  const mins = Math.floor(room.durationSeconds / 60);
  const choice = await vscode.window.showInformationMessage(
    `${room.hostName} started a Focus Room — ${mins} min session, join to code together.`, 'Join');
  // The invite may be stale by the time it's clicked
  if (choice === 'Join' && !rooms.isInRoom && !room.isExpired()) {
    rooms.joinRoom(room);
    refresh();
  }
}

async function showPermissionWarning(spotify: SpotifyService): Promise<void> {
  if (spotify.permissionProblem === 'macAutomation') {
    const steps =
      '1. Open System Settings\n' +
      '2. Go to Privacy & Security → Automation\n' +
      '3. Find Visual Studio Code (or your editor)\n' +
      '4. Enable the Spotify checkbox\n' +
      '5. Restart the editor';
    const choice = await vscode.window.showWarningMessage(
      'Spotyy: macOS is blocking AppleScript access to Spotify. Enable Spotify for your editor in System Settings → Privacy & Security → Automation.',
      'Copy steps');
    if (choice) await vscode.env.clipboard.writeText(steps);
  } else if (spotify.permissionProblem === 'linuxSnap') {
    // Snap Spotify's AppArmor policy blocks D-Bus from processes it doesn't trust;
    // the reliable fix is replacing the snap with the official .deb package.
    const commands = [
      'sudo snap remove spotify',
      'curl -sS https://download.spotify.com/debian/pubkey_6224F9941A8AA6D1.gpg | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/spotify.gpg',
      'echo "deb http://repository.spotify.com stable non-free" | sudo tee /etc/apt/sources.list.d/spotify.list',
      'sudo apt update && sudo apt install spotify-client',
    ].join('\n');
    const choice = await vscode.window.showWarningMessage(
      "Spotyy: Snap Spotify's AppArmor policy blocks D-Bus access. Reinstall Spotify from the official apt repository for full support.",
      'Copy fix commands');
    if (choice) await vscode.env.clipboard.writeText(commands);
  }
}
