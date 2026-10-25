import * as dgram from 'dgram';
import * as os from 'os';
import type { KeyValueStore } from './store';
import {
  BROADCAST_ADDR,
  GHOST_MODE_KEY,
  LISTENER_RETRY_MS,
  PEER_TIMEOUT_MS,
  PORT,
  PRESENCE_PREFIX,
  ROOM_PREFIX,
  VIBE_COUNT_KEY,
  VIBE_DATE_KEY,
} from './protocol';

export interface SpotyyPeer {
  username: string;
  track: string;
  lastSeen: number;
}

export type VibeMatch = 'NONE' | 'SAME_ARTIST' | 'SAME_SONG';

/** Outgoing packet sink. The default broadcasts over UDP; tests inject a fake. */
export interface Transport {
  send(msg: string): void;
}

const isBlank = (s: string): boolean => s.trim().length === 0;

/** Local calendar date as YYYY-MM-DD (same as Kotlin LocalDate.now().toString()). */
function localDateString(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function ipv4ToInt(ip: string): number | null {
  const parts = ip.split('.');
  if (parts.length !== 4) return null;
  let n = 0;
  for (const p of parts) {
    const v = Number(p);
    if (!Number.isInteger(v) || v < 0 || v > 255) return null;
    n = (n << 8) | v;
  }
  return n >>> 0;
}

function intToIpv4(n: number): string {
  return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.');
}

/**
 * Subnet broadcast addresses (addr | ~netmask) of every non-internal IPv4 interface.
 * Falls back to 255.255.255.255 — most Wi-Fi routers drop limited broadcast between
 * clients, so the subnet address is what actually reaches teammates.
 */
export function getBroadcastAddresses(): string[] {
  const result = new Set<string>();
  try {
    const ifaces = os.networkInterfaces();
    for (const name of Object.keys(ifaces)) {
      for (const ia of ifaces[name] ?? []) {
        // Node 18.0–18.3 reported family as a number (4); later versions use 'IPv4'.
        const fam = ia.family as string | number;
        if ((fam !== 'IPv4' && fam !== 4) || ia.internal) continue;
        const addr = ipv4ToInt(ia.address);
        const mask = ipv4ToInt(ia.netmask);
        // Skip /32 point-to-point links (e.g. VPN tunnels): "broadcast" would just be our own address.
        if (addr === null || mask === null || mask === 0xffffffff) continue;
        result.add(intToIpv4((addr | ~mask) >>> 0));
      }
    }
  } catch {
    /* ignore */
  }
  if (result.size === 0) result.add(BROADCAST_ADDR);
  return [...result];
}

/** True if [addr] belongs to one of this machine's interfaces (including loopback). */
function isLocalAddress(addr: string): boolean {
  try {
    const normalized = addr.startsWith('::ffff:') ? addr.slice(7) : addr;
    const ifaces = os.networkInterfaces();
    for (const name of Object.keys(ifaces)) {
      for (const ia of ifaces[name] ?? []) {
        if (ia.address === normalized || ia.address === addr) return true;
      }
    }
  } catch {
    /* ignore */
  }
  return false;
}

/**
 * Default transport: one lazily created, reused udp4 socket with SO_BROADCAST.
 * setBroadcast() only works on a bound socket, so we bind to an ephemeral port
 * first and queue anything sent before the bind completes. All errors are swallowed.
 */
export class UdpBroadcastTransport implements Transport {
  private socket: dgram.Socket | null = null;
  private ready = false;
  private queue: string[] = [];

  send(msg: string): void {
    try {
      const s = this.ensureSocket();
      if (!this.ready) {
        this.queue.push(msg);
        return;
      }
      this.sendNow(s, msg);
    } catch {
      /* ignore */
    }
  }

  close(): void {
    const s = this.socket;
    this.socket = null;
    this.ready = false;
    this.queue = [];
    try {
      s?.close();
    } catch {
      /* ignore */
    }
  }

  private ensureSocket(): dgram.Socket {
    if (this.socket) return this.socket;
    const s = dgram.createSocket({ type: 'udp4' });
    this.socket = s;
    this.ready = false;
    s.on('error', () => {
      if (this.socket === s) this.close();
      else {
        try {
          s.close();
        } catch {
          /* ignore */
        }
      }
    });
    s.unref();
    s.bind(0, () => {
      if (this.socket !== s) return;
      try {
        s.setBroadcast(true);
      } catch {
        /* ignore */
      }
      this.ready = true;
      const pending = this.queue;
      this.queue = [];
      for (const m of pending) this.sendNow(s, m);
    });
    return s;
  }

  private sendNow(s: dgram.Socket, msg: string): void {
    const data = Buffer.from(msg, 'utf8');
    for (const addr of getBroadcastAddresses()) {
      try {
        s.send(data, PORT, addr, () => {
          /* swallow send errors */
        });
      } catch {
        /* ignore */
      }
    }
  }
}

/** Port of com.magneto.spotyy.network.NetworkDiscoveryService. */
export class NetworkDiscoveryService {
  readonly localUsername: string;

  /** Set to route SPOTYY_ROOM packets to FocusRoomService. */
  roomMessageHandler: ((msg: string) => void) | null = null;

  private _localTrack = '';
  private readonly peers = new Map<string, SpotyyPeer>();
  private readonly recordedVibes = new Set<string>();
  private vibeDate: string;

  private readonly store: KeyValueStore;
  private readonly now: () => number;
  private readonly transport: Transport;

  private listener: dgram.Socket | null = null;
  private retryTimer: NodeJS.Timeout | null = null;
  private running = false;

  constructor(
    store: KeyValueStore,
    opts: { username?: string; now?: () => number; transport?: Transport } = {},
  ) {
    this.store = store;
    this.now = opts.now ?? Date.now;
    this.transport = opts.transport ?? new UdpBroadcastTransport();
    this.localUsername = opts.username ?? defaultUsername();
    this.vibeDate = localDateString(this.now());
  }

  /** The track this user is currently playing — kept in sync by broadcast(). */
  get localTrack(): string {
    return this._localTrack;
  }

  // ── Ghost mode ──────────────────────────────────────────────────────────

  isGhostMode(): boolean {
    return this.store.get<boolean>(GHOST_MODE_KEY, false) === true;
  }

  async setGhostMode(enabled: boolean): Promise<void> {
    await this.store.update(GHOST_MODE_KEY, enabled);
    if (enabled) {
      this._localTrack = '';
      this.sendPacket(`${PRESENCE_PREFIX}|${this.localUsername}||false`);
    }
  }

  // ── Vibe match ──────────────────────────────────────────────────────────

  vibeMatch(peer: SpotyyPeer): VibeMatch {
    if (isBlank(this._localTrack) || isBlank(peer.track)) return 'NONE';
    const local = this._localTrack.toLowerCase();
    const other = peer.track.toLowerCase();
    if (local === other) return 'SAME_SONG';
    const localArtist = local.split(' - ')[0].trim();
    const peerArtist = other.split(' - ')[0].trim();
    if (!isBlank(localArtist) && localArtist === peerArtist) return 'SAME_ARTIST';
    return 'NONE';
  }

  /** Counts a vibe match once per (peer, track) pair per day. */
  recordVibeMatch(peerName: string, track: string): void {
    const today = localDateString(this.now());
    if (today !== this.vibeDate) {
      this.recordedVibes.clear();
      this.vibeDate = today;
    }
    const key = `${peerName}|${track}`;
    if (this.recordedVibes.has(key)) return;
    this.recordedVibes.add(key);

    // Start from 0 on a new day — the stored count belongs to the stored date
    const current = this.store.get<string>(VIBE_DATE_KEY, '') === today
      ? parseIntOrNull(String(this.store.get<unknown>(VIBE_COUNT_KEY, '0'))) ?? 0
      : 0;
    void this.store.update(VIBE_DATE_KEY, today);
    void this.store.update(VIBE_COUNT_KEY, String(current + 1));
  }

  /** Today's total vibe match count (resets at midnight). */
  getTodayVibeCount(): number {
    const today = localDateString(this.now());
    const stored = this.store.get<string>(VIBE_DATE_KEY, '');
    if (stored !== today) return 0;
    return parseIntOrNull(String(this.store.get<unknown>(VIBE_COUNT_KEY, '0'))) ?? 0;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  start(): void {
    if (this.running) return;
    this.running = true;
    this.bindListener();
  }

  stop(): void {
    this.running = false;
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.closeListener();
    this.peers.clear();
    if (this.transport instanceof UdpBroadcastTransport) this.transport.close();
  }

  // ── Broadcasting ────────────────────────────────────────────────────────

  broadcast(track: string, isPlaying: boolean): void {
    if (this.isGhostMode()) return;
    this._localTrack = isPlaying && !isBlank(track) ? track : '';
    const msg = !isBlank(this._localTrack)
      ? `${PRESENCE_PREFIX}|${this.localUsername}|${this._localTrack}|true`
      : `${PRESENCE_PREFIX}|${this.localUsername}||false`;
    this.sendPacket(msg);
  }

  /** Used by FocusRoomService to send room messages. */
  sendRoomPacket(msg: string): void {
    this.sendPacket(msg);
  }

  // ── Peers ───────────────────────────────────────────────────────────────

  getActivePeers(): SpotyyPeer[] {
    const now = this.now();
    for (const [name, p] of this.peers) {
      if (now - p.lastSeen > PEER_TIMEOUT_MS) this.peers.delete(name);
    }
    return [...this.peers.values()].sort((a, b) =>
      a.username < b.username ? -1 : a.username > b.username ? 1 : 0,
    );
  }

  /** Entry point for every received datagram. Skips packets from this machine. */
  handlePacket(msg: string, fromAddress: string): void {
    // Compare by IP, not username, so two machines with the same username are distinct peers.
    if (isLocalAddress(fromAddress)) return;
    this.parseAndStore(msg);
  }

  // ── Internal ────────────────────────────────────────────────────────────

  private sendPacket(msg: string): void {
    try {
      this.transport.send(msg);
    } catch {
      /* ignore */
    }
  }

  private parseAndStore(msg: string): void {
    if (msg.startsWith(ROOM_PREFIX)) {
      this.roomMessageHandler?.(msg);
      return;
    }
    const parts = msg.split('|');
    if (parts.length < 4 || parts[0] !== PRESENCE_PREFIX) return;
    const name = parts[1].trim();
    const track = parts[2].trim();
    const isPlaying = parts[3].trim() === 'true';
    if (isPlaying && !isBlank(track)) {
      this.peers.set(name, { username: name, track, lastSeen: this.now() });
    } else {
      this.peers.delete(name);
    }
  }

  private bindListener(): void {
    if (!this.running) return;
    let socket: dgram.Socket;
    try {
      socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    } catch {
      this.scheduleRetry();
      return;
    }
    this.listener = socket;
    socket.on('message', (buf, rinfo) => {
      try {
        this.handlePacket(buf.toString('utf8'), rinfo.address);
      } catch {
        /* ignore */
      }
    });
    socket.on('error', () => {
      if (this.listener !== socket) return;
      this.closeListener();
      if (this.running) {
        this.peers.clear();
        this.scheduleRetry();
      }
    });
    socket.on('listening', () => {
      try {
        socket.setBroadcast(true);
      } catch {
        /* ignore */
      }
    });
    try {
      socket.bind(PORT);
    } catch {
      this.closeListener();
      this.scheduleRetry();
    }
  }

  private scheduleRetry(): void {
    if (!this.running || this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.bindListener();
    }, LISTENER_RETRY_MS);
    this.retryTimer.unref?.();
  }

  private closeListener(): void {
    const s = this.listener;
    this.listener = null;
    if (!s) return;
    try {
      s.close();
    } catch {
      /* ignore */
    }
  }
}

function defaultUsername(): string {
  try {
    const u = os.userInfo().username;
    if (u) return u;
  } catch {
    /* ignore */
  }
  return 'Someone';
}

/** Kotlin String.toIntOrNull(): optional sign + digits only. */
export function parseIntOrNull(s: string): number | null {
  if (!/^[+-]?\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) ? n : null;
}
