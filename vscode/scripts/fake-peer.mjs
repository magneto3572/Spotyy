#!/usr/bin/env node
// fake-peer.mjs — standalone fake Spotyy "teammate" for LAN protocol interop tests.
//
// Protocol derived ONLY from the Kotlin plugin sources:
//   src/main/kotlin/com/magneto/spotyy/network/NetworkDiscoveryService.kt
//   src/main/kotlin/com/magneto/spotyy/focus/FocusRoomService.kt
//
// Zero dependencies, Node 18+, ESM.
//
// Usage:
//   node fake-peer.mjs --name alice [--track "Daft Punk - One More Time"] [--paused]
//                      [--host-room <minutes>] [--auto-join] [--quiet]
//
// Stdin commands (one per line):
//   track <text> | pause | play | leave | invite <username> (host only) | raw <packet text> | quit
// --host-room accepts fractional minutes (durationSecs = Math.round(min*60), e.g. 0.3 -> 18).
//
// Output: one JSON object per line on stdout:
//   {"ev":"recv","from":"<ip>","msg":"..."}   every received packet (own-interface packets skipped)
//   {"ev":"send","msg":"..."}                 every sent packet (suppressed by --quiet)
//   {"ev":"state",...}                         room join/leave/end and lifecycle events

import dgram from 'node:dgram';
import os from 'node:os';
import readline from 'node:readline';

// ── Protocol constants (NetworkDiscoveryService / FocusRoomService) ──────────
const PORT = 57372;
const FALLBACK_BROADCAST = '255.255.255.255';
const PREFIX = 'SPOTYY';
const ROOM_PREFIX = 'SPOTYY_ROOM';
const TICK_MS = 3000;            // widget poll / room ping interval
const MEMBER_TIMEOUT_MS = 10_000; // FocusRoomService.MEMBER_TIMEOUT

// ── CLI ──────────────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const o = { name: null, track: '', paused: false, hostRoom: null, autoJoin: false, quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) die(`missing value for ${a}`);
      return argv[++i];
    };
    switch (a) {
      case '--name': o.name = next(); break;
      case '--track': o.track = next(); break;
      case '--paused': o.paused = true; break;
      case '--host-room': {
        const n = Number(next());
        if (!Number.isFinite(n) || n <= 0) die('--host-room expects a positive number of minutes');
        o.hostRoom = n;
        break;
      }
      case '--auto-join': o.autoJoin = true; break;
      case '--quiet': o.quiet = true; break;
      case '-h': case '--help': usage(0); break;
      default: die(`unknown argument: ${a}`);
    }
  }
  if (!o.name) die('--name is required');
  return o;
}
function usage(code) {
  process.stderr.write(
    'usage: node fake-peer.mjs --name <name> [--track "Artist - Title"] [--paused]\n' +
    '                          [--host-room <minutes>] [--auto-join] [--quiet]\n' +
    'stdin: track <text> | pause | play | leave | invite <user> | raw <packet> | quit\n');
  process.exit(code);
}
function die(msg) { process.stderr.write(`fake-peer: ${msg}\n`); usage(2); }

const opts = parseArgs(process.argv.slice(2));
const me = opts.name;

// ── Logging ──────────────────────────────────────────────────────────────────
const log = (obj) => console.log(JSON.stringify(obj));

// ── Network helpers ──────────────────────────────────────────────────────────
/** Subnet broadcast addresses of all non-internal IPv4 interfaces (fallback 255.255.255.255). */
function getBroadcastAddresses() {
  const out = new Set();
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.internal || (a.family !== 'IPv4' && a.family !== 4) || !a.netmask) continue;
      const ip = a.address.split('.').map(Number);
      const mask = a.netmask.split('.').map(Number);
      if (ip.length !== 4 || mask.length !== 4) continue;
      // Skip /32 point-to-point style addresses (no meaningful broadcast).
      if (mask.every((m) => m === 255)) continue;
      out.add(ip.map((o, i) => (o | (~mask[i] & 255)) & 255).join('.'));
    }
  }
  if (out.size === 0) out.add(FALLBACK_BROADCAST);
  return [...out];
}

/** All addresses on this host's interfaces (like Kotlin isLocalAddress). */
function getLocalAddresses() {
  const set = new Set();
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs || []) set.add(a.address);
  }
  return set;
}
const normalizeIp = (ip) => (ip.startsWith('::ffff:') ? ip.slice(7) : ip);

// ── State ────────────────────────────────────────────────────────────────────
let track = opts.track;
let paused = opts.paused;
/** @type {null | {roomId:string, hostName:string, durationSecs:number, startTs:number, members: Map<string, number>}} */
let room = null;
let isHost = false;
let pendingJoinTimer = null;
let closing = false;

const remainingSecs = (r) => Math.max(0, r.durationSecs - Math.floor((Date.now() - r.startTs) / 1000));
const stateEv = (what, extra = {}) =>
  log({
    ev: 'state', what, name: me, isHost,
    room: room ? { roomId: room.roomId, host: room.hostName, durationSecs: room.durationSecs, startTs: room.startTs,
      remainingSecs: remainingSecs(room), members: [...room.members.keys()].sort() } : null,
    ...extra,
  });

// ── Socket ───────────────────────────────────────────────────────────────────
const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });

function sendRaw(msg) {
  const buf = Buffer.from(msg, 'utf8');
  const targets = getBroadcastAddresses();
  if (!opts.quiet) log({ ev: 'send', msg, to: targets });
  return Promise.all(targets.map((addr) => new Promise((res) => {
    sock.send(buf, PORT, addr, (err) => {
      if (err) log({ ev: 'error', where: 'send', addr, error: String(err.message || err) });
      res();
    });
  })));
}

// NetworkDiscoveryService.broadcast()
function presenceMsg() {
  const t = !paused && track.trim() !== '' ? track : '';
  return t ? `${PREFIX}|${me}|${t}|true` : `${PREFIX}|${me}||false`;
}
const sendPresence = () => sendRaw(presenceMsg());

// FocusRoomService.send()
function roomMsg(action, r, target = '') {
  return `${ROOM_PREFIX}|${action}|${r.roomId}|${r.hostName}|${r.durationSecs}|${r.startTs}|${me}|${target}`;
}
const sendRoom = (action, r, target = '') => sendRaw(roomMsg(action, r, target));

// ── Room actions ─────────────────────────────────────────────────────────────
function startRoom(minutes) {
  const now = Date.now();
  room = {
    roomId: now.toString(36),
    hostName: me,
    durationSecs: Math.round(minutes * 60),
    startTs: now,
    members: new Map([[me, now]]),
  };
  isHost = true;
  stateEv('room-started');
}

function joinRoom(r) {
  const now = Date.now();
  room = { ...r, members: new Map([[r.hostName, now], [me, now]]) };
  isHost = false;
  stateEv('room-joined');
  sendRoom('JOIN', room);
}

function leaveRoom(reason = 'leave') {
  if (!room) return Promise.resolve();
  const r = room;
  const p = sendRoom('LEAVE', r);
  room = null;
  isHost = false;
  stateEv('room-left', { reason, roomId: r.roomId });
  return p;
}

function clearRoom(reason) {
  const id = room?.roomId;
  room = null;
  isHost = false;
  stateEv('room-left', { reason, roomId: id });
}

// FocusRoomService.ping()
function roomTick() {
  if (!room) return;
  if (remainingSecs(room) <= 0) {
    if (isHost) sendRoom('END', room);
    clearRoom('expired');
    return;
  }
  const now = Date.now();
  for (const [n, seen] of room.members) {
    if (n !== me && now - seen > MEMBER_TIMEOUT_MS) {
      room.members.delete(n);
      stateEv('member-timeout', { member: n });
    }
  }
  room.members.set(me, now);
  sendRoom('PING', room);
}

// ── Incoming ─────────────────────────────────────────────────────────────────
function handleRoomMessage(msg) {
  const parts = msg.split('|');
  if (parts.length < 7 || parts[0] !== ROOM_PREFIX) return;
  const [, action, roomId, hostName, durStr, tsStr] = parts;
  const durationSecs = Number.parseInt(durStr, 10);
  const startTs = Number.parseInt(tsStr, 10);
  if (!Number.isFinite(durationSecs) || !Number.isFinite(startTs)) return;
  const sender = parts[6].trim();
  const target = parts.length > 7 ? parts[7].trim() : '';
  if (sender === me) return;

  switch (action) {
    case 'INVITE':
      if (target === me && !room) {
        stateEv('invited', { roomId, host: hostName, from: sender });
        if (opts.autoJoin && !pendingJoinTimer) {
          pendingJoinTimer = setTimeout(() => {
            pendingJoinTimer = null;
            if (!room && !closing) joinRoom({ roomId, hostName, durationSecs, startTs });
          }, 1000);
        }
      }
      break;
    case 'PING':
    case 'JOIN':
      if (room && room.roomId === roomId) {
        const isNew = !room.members.has(sender);
        room.members.set(sender, Date.now());
        if (isNew) stateEv('member-joined', { member: sender });
      }
      break;
    case 'LEAVE':
      if (room && room.roomId === roomId && room.members.delete(sender)) {
        stateEv('member-left', { member: sender });
      }
      break;
    case 'KICK':
      if (target === me) {
        if (room && room.roomId === roomId) clearRoom('kicked');
        else if (room) clearRoom('kicked'); // Kotlin clears regardless of roomId
      } else if (room && room.roomId === roomId && room.members.delete(target)) {
        stateEv('member-kicked', { member: target });
      }
      break;
    case 'END':
      if (room && room.roomId === roomId) clearRoom('ended');
      break;
  }
}

sock.on('message', (buf, rinfo) => {
  const from = normalizeIp(rinfo.address);
  if (getLocalAddresses().has(from)) return; // skip own packets (by IP, like Kotlin)
  const msg = buf.toString('utf8');
  log({ ev: 'recv', from, msg });
  if (msg.startsWith(ROOM_PREFIX)) handleRoomMessage(msg);
});

sock.on('error', (err) => {
  log({ ev: 'error', where: 'socket', error: String(err.message || err) });
  process.exit(1);
});

// ── Main loop ────────────────────────────────────────────────────────────────
let ticker = null;
function tick() {
  sendPresence();
  roomTick();
}

sock.bind(PORT, () => {
  sock.setBroadcast(true);
  stateEv('started', { broadcast: getBroadcastAddresses(), port: PORT });
  if (opts.hostRoom) startRoom(opts.hostRoom);
  tick();
  ticker = setInterval(tick, TICK_MS);
});

// ── Stdin control ────────────────────────────────────────────────────────────
const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
  const s = line.trim();
  if (!s) return;
  const sp = s.indexOf(' ');
  const cmd = (sp < 0 ? s : s.slice(0, sp)).toLowerCase();
  const arg = sp < 0 ? '' : s.slice(sp + 1).trim();
  switch (cmd) {
    case 'track': track = arg; paused = false; log({ ev: 'cmd', cmd, track }); sendPresence(); break;
    case 'pause': paused = true; log({ ev: 'cmd', cmd }); sendPresence(); break;
    case 'play': paused = false; log({ ev: 'cmd', cmd }); sendPresence(); break;
    case 'leave': log({ ev: 'cmd', cmd }); leaveRoom('cmd'); break;
    case 'invite': // FocusRoomService.invitePeer(): host only
      if (!arg) { log({ ev: 'error', where: 'stdin', error: 'invite requires a username' }); break; }
      if (!room || !isHost) { log({ ev: 'error', where: 'stdin', error: 'invite: not hosting a room' }); break; }
      log({ ev: 'cmd', cmd, target: arg });
      sendRoom('INVITE', room, arg);
      break;
    case 'raw': // broadcast verbatim (text after the first space, untrimmed)
      if (sp < 0) { log({ ev: 'error', where: 'stdin', error: 'raw requires packet text' }); break; }
      log({ ev: 'cmd', cmd });
      sendRaw(line.replace(/^\s*raw /i, ''));
      break;
    case 'quit': log({ ev: 'cmd', cmd }); shutdown('quit'); break;
    default: log({ ev: 'error', where: 'stdin', error: `unknown command: ${s}` });
  }
});
// stdin EOF is NOT a quit (container runs without -i); only explicit `quit` or signals.

// ── Shutdown ─────────────────────────────────────────────────────────────────
async function shutdown(reason) {
  if (closing) return;
  closing = true;
  if (ticker) clearInterval(ticker);
  if (pendingJoinTimer) clearTimeout(pendingJoinTimer);
  try {
    if (room) {
      if (isHost) { const r = room; await sendRoom('END', r); clearRoom('shutdown-end'); }
      else await leaveRoom('shutdown');
    }
    await sendRaw(`${PREFIX}|${me}||false`);
  } catch { /* ignore */ }
  log({ ev: 'state', what: 'exit', name: me, reason });
  rl.close();
  sock.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 500).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
