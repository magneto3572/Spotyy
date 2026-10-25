// End-to-end suite, run inside a real VS Code extension host (see run.sh).
// Drives the real Spotify desktop app and fake LAN teammates running in Docker
// containers (scripts/fake-peer.mjs), so packets cross a real network boundary.
const vscode = require('vscode');
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const { spawn, execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const OUT = process.env.SPOTYY_OUT || path.join(ROOT, 'test-integration', 'out');
const SKIP_SPOTIFY = process.env.SPOTYY_SKIP_SPOTIFY === '1';

const results = [];
const peers = [];

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function waitFor(fn, label, timeoutMs = 12000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try { last = await fn(); if (last) return last; } catch (e) { last = e; }
    await sleep(250);
  }
  throw new Error(`timed out waiting for: ${label}${last instanceof Error ? ` (${last.message})` : ''}`);
}

/**
 * Spotify Free plays ads that can't be skipped or paused remotely and have no
 * "Artist - Track" title. Wait them out before tests that control playback.
 */
async function waitNoAd(api) {
  await waitFor(async () => {
    await api.poll();
    const s = api.getSpotifyState();
    return !s.isPlaying || (s.trackInfo && s.trackInfo.includes(' - '));
  }, 'Spotify ad break to finish', 120000);
}

async function test(name, fn) {
  const t0 = Date.now();
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - t0 });
    console.log(`  ✔ ${name}`);
  } catch (e) {
    results.push({ name, ok: false, ms: Date.now() - t0, error: String(e && e.stack || e) });
    console.log(`  ✘ ${name}\n      ${e && e.message}`);
  }
  // Written after every test so a killed run still leaves partial results
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
}

/** Starts a fake teammate in its own container (own IP on docker0). */
function startPeer(name, args = []) {
  const proc = spawn('docker', ['run', '-i', '--rm', '--name', `spotyy-${name}`,
    '-v', `${path.join(ROOT, 'scripts')}:/s:ro`, 'node:22-alpine',
    'node', '/s/fake-peer.mjs', '--name', name, ...args]);
  const peer = { name, proc, events: [], log: '' };
  let buf = '';
  proc.stdout.on('data', d => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      peer.log += line + '\n';
      try { peer.events.push(JSON.parse(line)); } catch { /* non-JSON line */ }
    }
  });
  proc.stderr.on('data', d => { peer.log += `[stderr] ${d}`; });
  peer.send = line => proc.stdin.write(line + '\n');
  peer.received = pred => peer.events.filter(e => e.ev === 'recv' && pred(e.msg));
  peers.push(peer);
  return peer;
}

function stopPeers() {
  for (const p of peers) {
    try { execFileSync('docker', ['rm', '-f', `spotyy-${p.name}`], { stdio: 'ignore' }); } catch { /* gone */ }
    fs.writeFileSync(path.join(OUT, `peer-${p.name}.log`), p.log);
  }
}

function screenshot(file) {
  try {
    execFileSync('python3', ['-c', `from PIL import ImageGrab; ImageGrab.grab().save(${JSON.stringify(file)})`]);
  } catch (e) { console.log(`  (screenshot failed: ${e.message})`); }
}

async function run() {
  fs.mkdirSync(OUT, { recursive: true });
  const ext = vscode.extensions.all.find(e => e.packageJSON.name === 'spotyy');
  assert.ok(ext, 'extension not found');
  const api = await ext.activate();
  const me = api.network.localUsername;
  const bar = () => api.getStatusBar();
  const model = () => api.getModel();
  const peerVM = n => model().peers.find(p => p.username === n);

  console.log(`\nSpotyy integration suite (user "${me}")\n`);

  // ── Activation ────────────────────────────────────────────────────────────
  await test('activation registers all commands', async () => {
    const all = await vscode.commands.getCommands(true);
    for (const c of ext.packageJSON.contributes.commands.map(c => c.command)) {
      assert.ok(all.includes(c), `missing command ${c}`);
    }
  });

  await test('status bar shows playback controls', async () => {
    const b = bar();
    assert.ok(b.includes('$(spotyy-logo)') && b.includes('$(spotyy-prev)') && b.includes('$(spotyy-next)'), JSON.stringify(b));
    assert.ok(b.some(t => t.startsWith('Spotyy')), JSON.stringify(b));
  });

  await test('Listening Nearby view opens', async () => {
    await vscode.commands.executeCommand('spotyy.showPeers');
  });

  // ── Spotify ───────────────────────────────────────────────────────────────
  let originalVolume = 50;
  let track = null;
  if (!SKIP_SPOTIFY) {
    await test('play/pause launches Spotify if needed and starts playback', async () => {
      await api.poll();
      if (!api.getSpotifyState().isPlaying) {
        // On Linux this launches Spotify when it isn't running (up to ~20 s)
        await vscode.commands.executeCommand('spotyy.playPause');
      }
      const s = await waitFor(async () => { await api.poll(); const s = api.getSpotifyState(); return s.isPlaying && s.trackInfo && s; },
        'Spotify playing a track', 30000).catch(e => { throw new Error(`${e.message} — last state ${JSON.stringify(api.getSpotifyState())}`); });
      track = s.trackInfo;
      originalVolume = s.volume;
    });

    await test('status bar reflects playing track', async () => {
      await waitNoAd(api);
      await waitFor(() => bar().some(t => /^\$\(spotyy-pause-(dark|light)-\d\d\)$/.test(t)), 'pause ring icon');
      const t = bar().find(x => x.startsWith('Spotyy'));
      assert.ok(t && t.startsWith('Spotyy  |  ') && t.length > 14, `track text: ${t}`);
      assert.ok(track.includes(' - '), `trackInfo format "Artist - Track": ${track}`);
    });

    await test('progress and duration reported', async () => {
      const s = api.getSpotifyState();
      assert.ok(s.durationMs > 0, `durationMs=${s.durationMs}`);
      assert.ok(s.positionMs >= 0 && s.positionMs <= s.durationMs, `positionMs=${s.positionMs}`);
    });

    await test('progress ring advances while playing', async () => {
      const ring = () => bar().find(t => /^\$\(spotyy-pause-(dark|light)-\d\d\)$/.test(t));
      const first = await waitFor(ring, 'pause ring icon');
      const s = api.getSpotifyState();
      // One ring step per 1/32 of the track, plus slack for the 1 s refresh
      const stepMs = s.durationMs / 32;
      await waitFor(() => ring() && ring() !== first, `ring to advance from ${first}`, stepMs + 4000);
    });

    await test('next track changes the track', async () => {
      await waitNoAd(api);
      const before = api.getSpotifyState().trackInfo;
      await vscode.commands.executeCommand('spotyy.next');
      await waitFor(async () => { await api.poll(); return api.getSpotifyState().trackInfo !== before; }, 'track change');
    });

    await test('previous track goes back', async () => {
      await waitNoAd(api);
      const before = api.getSpotifyState().trackInfo;
      await vscode.commands.executeCommand('spotyy.previous');
      // Spotify restarts the current song if > 3 s in, so press twice if needed
      try {
        await waitFor(async () => { await api.poll(); return api.getSpotifyState().trackInfo !== before; }, 'track change', 5000);
      } catch {
        await vscode.commands.executeCommand('spotyy.previous');
        await waitFor(async () => { await api.poll(); return api.getSpotifyState().trackInfo !== before; }, 'track change');
      }
      track = api.getSpotifyState().trackInfo;
    });

    await test('volume down / up', async () => {
      await api.poll();
      const v0 = api.getSpotifyState().volume;
      await vscode.commands.executeCommand(v0 >= 20 ? 'spotyy.volumeDown' : 'spotyy.volumeUp');
      await waitFor(async () => { await api.poll(); return api.getSpotifyState().volume !== v0; }, `volume to change from ${v0}`, 6000);
      const v1 = api.getSpotifyState().volume;
      assert.ok(Math.abs(Math.abs(v1 - v0) - 10) <= 2, `expected ±10, got ${v0} → ${v1}`);
      await waitFor(() => bar().includes(v1 === 0 ? '$(spotyy-mute)' : '$(spotyy-volume)'), 'volume icon in status bar');
    });
  } else {
    track = 'Daft Punk - One More Time';
  }

  // ── LAN: presence ─────────────────────────────────────────────────────────
  const alice = startPeer('alice', ['--track', 'Bonobo - Kerala', '--auto-join']);

  await test('teammate (other IP) appears in Listening Nearby', async () => {
    const p = await waitFor(() => peerVM('alice'), 'alice in peers', 20000);
    assert.strictEqual(p.track, 'Bonobo - Kerala');
    await waitFor(() => bar().includes('$(person) 1') || bar().some(t => t.startsWith('✦')), `peer count in status bar ${JSON.stringify(bar())}`);
  });

  await test('teammate receives our presence packet (JetBrains format)', async () => {
    if (SKIP_SPOTIFY) { api.network.broadcast(track, true); }
    const expected = `SPOTYY|${me}|${api.network.localTrack}|true`;
    await waitFor(() => alice.received(m => m === expected).length > 0, `alice to receive ${expected}`);
  });

  await test('vibe match: same song', async () => {
    alice.send(`track ${api.network.localTrack || track}`);
    await waitFor(() => peerVM('alice')?.match === 'SAME_SONG', 'SAME_SONG');
    await api.poll(); // records the vibe + refreshes status bar
    await waitFor(() => bar().some(t => t.startsWith('✦')), `✦ in status bar ${JSON.stringify(bar())}`);
    assert.ok(model().todayVibeCount >= 1, `todayVibeCount=${model().todayVibeCount}`);
  });

  await test('vibe match: same artist', async () => {
    const artist = (api.network.localTrack || track).split(' - ')[0];
    alice.send(`track ${artist} - Some Other Song`);
    await waitFor(() => peerVM('alice')?.match === 'SAME_ARTIST', 'SAME_ARTIST');
  });

  await test('teammate pausing disappears from the list', async () => {
    alice.send('pause');
    await waitFor(() => !peerVM('alice'), 'alice removed');
    alice.send('track Bonobo - Kerala');
    alice.send('play');
    await waitFor(() => peerVM('alice'), 'alice back');
  });

  // ── Focus Room as host ────────────────────────────────────────────────────
  await test('start a Focus Room (host)', async () => {
    api.act({ type: 'startRoom', minutes: 25 });
    const r = model().room;
    assert.ok(r && r.isHost && r.hostName === me, JSON.stringify(r));
    assert.match(r.remaining, /^2[45]:\d\d$/);
    await waitFor(() => bar().some(t => /^\$\(watch\) 2[45]:\d\d$/.test(t)), `room timer in status bar ${JSON.stringify(bar())}`);
  });

  await test('ghost mode is locked while in a room', async () => {
    api.act({ type: 'toggleGhost' });
    assert.strictEqual(model().ghostMode, false);
  });

  await test('invite → teammate receives INVITE and auto-joins', async () => {
    api.act({ type: 'invite', username: 'alice' });
    await waitFor(() => alice.received(m => m.startsWith('SPOTYY_ROOM|INVITE|') && m.endsWith(`|${me}|alice`)).length > 0, 'alice receives INVITE');
    await waitFor(() => model().room?.members.some(m => m.username === 'alice'), 'alice in members', 15000);
    await waitFor(() => peerVM('alice')?.inMyRoom, 'alice marked ✓ Joined');
  });

  await test('kick → teammate receives KICK and is removed', async () => {
    api.act({ type: 'kick', username: 'alice' });
    await waitFor(() => alice.received(m => m.startsWith('SPOTYY_ROOM|KICK|') && m.endsWith(`|${me}|alice`)).length > 0, 'alice receives KICK');
    assert.ok(!model().room.members.some(m => m.username === 'alice'));
    await sleep(7000); // alice must not come back via PINGs
    assert.ok(!model().room.members.some(m => m.username === 'alice'), 'alice rejoined after kick');
  });

  await test('end room → teammate receives END and the room closes for them', async () => {
    api.act({ type: 'invite', username: 'alice' });   // alice back in, so ending affects her
    await waitFor(() => model().room?.members.some(m => m.username === 'alice'), 'alice rejoined', 15000);
    api.act({ type: 'leave' });
    assert.strictEqual(model().room, null);
    await waitFor(() => alice.received(m => m.startsWith('SPOTYY_ROOM|END|')).length > 0, 'alice receives END');
    await waitFor(() => alice.events.some(e => e.ev === 'state' && e.what === 'room-left' && e.reason === 'ended'), 'alice left the room');
  });

  // ── Focus Room as guest ───────────────────────────────────────────────────
  const bob = startPeer('bob', ['--track', 'Radiohead - Reckoner', '--host-room', '0.5']);

  await test('nearby room from another host is visible (invite-only card)', async () => {
    const r = await waitFor(() => model().nearbyRooms.find(n => n.hostName === 'bob'), 'bob\'s nearby room', 20000);
    assert.ok(r.memberCount >= 1);
    assert.match(r.remaining, /^00:\d\d$/);
  });

  let invited = null;
  await test('incoming INVITE fires the invite notification', async () => {
    const orig = api.rooms.onRoomInvite;
    api.rooms.onRoomInvite = room => { invited = room; orig && orig(room); };
    bob.send(`invite ${me}`);
    await waitFor(() => invited, 'onRoomInvite');
    assert.strictEqual(invited.hostName, 'bob');
  });

  await test('join → host receives JOIN and both see each other', async () => {
    api.rooms.joinRoom(invited);
    await waitFor(() => bob.received(m => m.startsWith(`SPOTYY_ROOM|JOIN|${invited.roomId}|bob|`) && m.includes(`|${me}|`)).length > 0, 'bob receives JOIN');
    await waitFor(() => model().room?.members.some(m => m.username === 'bob' && m.isHost), 'bob as host member');
    assert.strictEqual(model().room.isHost, false);
    assert.deepStrictEqual(model().nearbyRooms, []);
    await vscode.commands.executeCommand('spotyy.showPeers');
    await sleep(1500);
    screenshot(path.join(OUT, 'in-room.png'));
  });

  await test('room expiry ends the room for guests', async () => {
    let ended = false;
    const orig = api.rooms.onRoomEnded;
    api.rooms.onRoomEnded = () => { ended = true; orig && orig(); };
    await waitFor(() => model().room === null, 'room to end', 45000);
    assert.ok(ended, 'onRoomEnded not fired');
  });

  // ── Ghost mode ────────────────────────────────────────────────────────────
  await test('ghost mode hides us from teammates', async () => {
    api.act({ type: 'toggleGhost' });
    assert.strictEqual(model().ghostMode, true);
    await waitFor(() => bar().includes('$(eye-closed)'), 'ghost icon');
    await waitFor(() => alice.received(m => m === `SPOTYY|${me}||false`).length > 0, 'alice receives empty presence');
    const n = alice.received(m => m.startsWith(`SPOTYY|${me}|`) && m.endsWith('|true')).length;
    await sleep(7000);
    assert.strictEqual(alice.received(m => m.startsWith(`SPOTYY|${me}|`) && m.endsWith('|true')).length, n, 'presence still broadcast in ghost mode');
    screenshot(path.join(OUT, 'ghost.png'));
    api.act({ type: 'toggleGhost' });
    assert.strictEqual(model().ghostMode, false);
  });

  // ── Cleanup ───────────────────────────────────────────────────────────────
  if (!SKIP_SPOTIFY) {
    await test('pause via command', async () => {
      await waitNoAd(api);
      // Opens a quick pick (resolves only once dismissed) — check it opens, then dismiss it
      const picked = vscode.commands.executeCommand('spotyy.setVolume');
      await sleep(800);
      await vscode.commands.executeCommand('workbench.action.closeQuickOpen');
      await picked;
      await vscode.commands.executeCommand('spotyy.playPause');
      await waitFor(async () => { await api.poll(); return !api.getSpotifyState().isPlaying; }, `paused (state ${JSON.stringify(api.getSpotifyState())})`);
      await waitFor(() => bar().some(t => /^\$\(spotyy-play-(dark|light)-\d\d\)$/.test(t)), 'play ring icon');
    });
    // Restore the user's volume
    for (let i = 0; i < 3 && api.getSpotifyState().volume !== originalVolume; i++) {
      const v = api.getSpotifyState().volume;
      await vscode.commands.executeCommand(v < originalVolume ? 'spotyy.volumeUp' : 'spotyy.volumeDown');
      await api.poll();
    }
  }

  stopPeers();
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
  const failed = results.filter(r => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} passed\n`);
  if (failed.length) throw new Error(`${failed.length} test(s) failed`);
}

module.exports = { run };
