import { test } from 'node:test';
import * as assert from 'node:assert/strict';
import { NetworkDiscoveryService, Transport, getBroadcastAddresses, parseIntOrNull } from '../network';
import { MemoryStore } from '../store';

class FakeTransport implements Transport {
  sent: string[] = [];
  send(msg: string): void {
    this.sent.push(msg);
  }
}

const REMOTE = '203.0.113.7'; // TEST-NET-3, never a local address

function setup(start = new Date(2026, 9, 25, 12, 0, 0).getTime()) {
  const clock = { t: start };
  const store = new MemoryStore();
  const transport = new FakeTransport();
  const net = new NetworkDiscoveryService(store, { username: 'alice', now: () => clock.t, transport });
  return { clock, store, transport, net };
}

test('presence packet: playing', () => {
  const { net, transport } = setup();
  net.broadcast('Daft Punk - One More Time', true);
  assert.deepEqual(transport.sent, ['SPOTYY|alice|Daft Punk - One More Time|true']);
  assert.equal(net.localTrack, 'Daft Punk - One More Time');
});

test('presence packet: not playing / blank track', () => {
  const { net, transport } = setup();
  net.broadcast('Daft Punk - One More Time', false);
  net.broadcast('   ', true);
  net.broadcast('', false);
  assert.deepEqual(transport.sent, [
    'SPOTYY|alice||false',
    'SPOTYY|alice||false',
    'SPOTYY|alice||false',
  ]);
  assert.equal(net.localTrack, '');
});

test('ghost mode: one empty packet, then broadcasts suppressed', async () => {
  const { net, transport, store } = setup();
  net.broadcast('A - B', true);
  assert.equal(net.isGhostMode(), false);
  await net.setGhostMode(true);
  assert.equal(net.isGhostMode(), true);
  assert.equal(store.get('spotyy.ghost.mode', false), true);
  assert.equal(net.localTrack, '');
  net.broadcast('A - C', true);
  assert.deepEqual(transport.sent, ['SPOTYY|alice|A - B|true', 'SPOTYY|alice||false']);
  await net.setGhostMode(false);
  assert.equal(transport.sent.length, 2, 'disabling sends nothing');
  net.broadcast('A - C', true);
  assert.equal(transport.sent[2], 'SPOTYY|alice|A - C|true');
});

test('sendRoomPacket passes text through verbatim', () => {
  const { net, transport } = setup();
  net.sendRoomPacket('SPOTYY_ROOM|PING|x|h|60|1|h|');
  assert.deepEqual(transport.sent, ['SPOTYY_ROOM|PING|x|h|60|1|h|']);
});

test('parsing peers, trimming, removal, sorting', () => {
  const { net } = setup();
  net.handlePacket('SPOTYY|zed| Artist - Song |true', REMOTE);
  net.handlePacket('SPOTYY|bob|X - Y|true', REMOTE);
  net.handlePacket('SPOTYY|carl||false', REMOTE);
  const peers = net.getActivePeers();
  assert.deepEqual(peers.map((p) => p.username), ['bob', 'zed']);
  assert.equal(peers[1].track, 'Artist - Song');

  net.handlePacket('SPOTYY|bob||false', REMOTE);
  assert.deepEqual(net.getActivePeers().map((p) => p.username), ['zed']);
  net.handlePacket('SPOTYY|zed|Artist - Song|false', REMOTE);
  assert.deepEqual(net.getActivePeers(), []);
});

test('malformed and foreign packets are ignored', () => {
  const { net } = setup();
  net.handlePacket('SPOTYY|bob|X - Y', REMOTE);
  net.handlePacket('OTHER|bob|X - Y|true', REMOTE);
  net.handlePacket('', REMOTE);
  assert.deepEqual(net.getActivePeers(), []);
});

test('packets from local addresses are skipped', () => {
  const { net } = setup();
  net.handlePacket('SPOTYY|bob|X - Y|true', '127.0.0.1');
  assert.deepEqual(net.getActivePeers(), []);
});

test('peer timeout pruning (> 10 s)', () => {
  const { net, clock } = setup();
  net.handlePacket('SPOTYY|bob|X - Y|true', REMOTE);
  clock.t += 10_000;
  assert.equal(net.getActivePeers().length, 1, 'exactly 10 s is still active');
  clock.t += 1;
  assert.equal(net.getActivePeers().length, 0);
});

test('refresh keeps peer alive', () => {
  const { net, clock } = setup();
  net.handlePacket('SPOTYY|bob|X - Y|true', REMOTE);
  clock.t += 8_000;
  net.handlePacket('SPOTYY|bob|X - Z|true', REMOTE);
  clock.t += 8_000;
  const peers = net.getActivePeers();
  assert.equal(peers.length, 1);
  assert.equal(peers[0].track, 'X - Z');
});

test('SPOTYY_ROOM packets go to roomMessageHandler, not peers', () => {
  const { net } = setup();
  const got: string[] = [];
  const pkt = 'SPOTYY_ROOM|PING|lq3k|bob|1500|1700000000000|bob|';
  net.handlePacket(pkt, REMOTE);
  net.roomMessageHandler = (m) => got.push(m);
  net.handlePacket(pkt, REMOTE);
  assert.deepEqual(got, [pkt]);
  assert.deepEqual(net.getActivePeers(), []);
});

test('vibe matching', () => {
  const { net } = setup();
  const peer = (track: string) => ({ username: 'bob', track, lastSeen: 0 });
  assert.equal(net.vibeMatch(peer('A - B')), 'NONE', 'no local track');
  net.broadcast('Daft Punk - One More Time', true);
  assert.equal(net.vibeMatch(peer('daft punk - one more time')), 'SAME_SONG');
  assert.equal(net.vibeMatch(peer('DAFT PUNK - Around the World')), 'SAME_ARTIST');
  assert.equal(net.vibeMatch(peer('Daft Punk  - Aerodynamic')), 'SAME_ARTIST', 'artist is trimmed');
  assert.equal(net.vibeMatch(peer('Justice - Genesis')), 'NONE');
  assert.equal(net.vibeMatch(peer('')), 'NONE');
  assert.equal(net.vibeMatch(peer('   ')), 'NONE');
  // No " - " separator: whole string is the artist
  net.broadcast('Daft Punk', true);
  assert.equal(net.vibeMatch(peer('Daft Punk - Digital Love')), 'SAME_ARTIST');
  net.broadcast(' - Untitled', true);
  assert.equal(net.vibeMatch(peer(' - Other')), 'NONE', 'blank artist never matches');
});

test('vibe count dedupe, string storage, and date reset', () => {
  const { net, clock, store } = setup();
  assert.equal(net.getTodayVibeCount(), 0);
  net.recordVibeMatch('bob', 'A - B');
  net.recordVibeMatch('bob', 'A - B');
  net.recordVibeMatch('bob', 'A - C');
  net.recordVibeMatch('carl', 'A - B');
  assert.equal(net.getTodayVibeCount(), 3);
  assert.equal(store.get('spotyy.vibe.count', ''), '3');
  assert.equal(store.get('spotyy.vibe.date', ''), '2026-10-25');

  // Next day: count reads 0 until a new match, dedupe set cleared
  clock.t = new Date(2026, 9, 26, 0, 0, 1).getTime();
  assert.equal(net.getTodayVibeCount(), 0);
  net.recordVibeMatch('bob', 'A - B');
  assert.equal(store.get('spotyy.vibe.date', ''), '2026-10-26');
  // The counter restarts for the new day
  assert.equal(net.getTodayVibeCount(), 1);
  net.recordVibeMatch('bob', 'A - B');
  assert.equal(net.getTodayVibeCount(), 1);
});

test('vibe date is zero-padded local date', () => {
  const { net, store } = setup(new Date(2027, 0, 5, 9, 0, 0).getTime());
  net.recordVibeMatch('bob', 'x');
  assert.equal(store.get('spotyy.vibe.date', ''), '2027-01-05');
});

test('getBroadcastAddresses returns at least one IPv4 address', () => {
  const addrs = getBroadcastAddresses();
  assert.ok(addrs.length >= 1);
  for (const a of addrs) assert.match(a, /^\d+\.\d+\.\d+\.\d+$/);
});

test('parseIntOrNull mirrors Kotlin toIntOrNull', () => {
  assert.equal(parseIntOrNull('1500'), 1500);
  assert.equal(parseIntOrNull('-3'), -3);
  assert.equal(parseIntOrNull(''), null);
  assert.equal(parseIntOrNull('12a'), null);
  assert.equal(parseIntOrNull('1.5'), null);
});

test('default username comes from the OS', () => {
  const net = new NetworkDiscoveryService(new MemoryStore(), { transport: new FakeTransport() });
  assert.ok(net.localUsername.length > 0);
});
