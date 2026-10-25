// @ts-check
// Renderer for the "Listening Nearby" webview (see src/peersView.ts).
// All peer data (usernames, tracks, host names) is untrusted network input:
// it is only ever inserted via textContent, never innerHTML.
(function () {
  'use strict';

  // eslint-disable-next-line no-undef
  const vscode = acquireVsCodeApi();
  const app = document.getElementById('app');

  /** Same 6-colour palette and hash as the JetBrains popup so avatars match across IDEs. */
  const AVATAR_PALETTE_SIZE = 6;

  /** Java String.hashCode, so a user's avatar colour matches the JetBrains plugin. */
  function javaHash(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
    return h;
  }
  /** Index into the palette (colours live in peers.css as .avatar-c0 … .avatar-c5; CSP forbids inline styles). */
  function avatarIndex(name) {
    return Math.abs(javaHash(name)) % AVATAR_PALETTE_SIZE;
  }
  function initial(name) {
    const first = Array.from(name)[0];
    return first ? first.toUpperCase() : '?';
  }

  // ── Client-side "Sent" invite state (survives re-renders and view hide/show) ──
  const saved = vscode.getState() || {};
  /** @type {Set<string>} */
  let sentInvites = new Set(Array.isArray(saved.sent) ? saved.sent : []);
  /** @type {string|null} */
  let roomKey = typeof saved.roomKey === 'string' ? saved.roomKey : null;
  function persist() {
    vscode.setState({ sent: Array.from(sentInvites), roomKey: roomKey });
  }

  // ── DOM helpers ─────────────────────────────────────────────────────────────
  /**
   * @param {string} tag
   * @param {string=} cls
   * @param {string=} text
   */
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function button(cls, text, onClick, disabled) {
    const b = /** @type {HTMLButtonElement} */ (el('button', cls, text));
    b.type = 'button';
    if (disabled) b.disabled = true;
    else if (onClick) b.addEventListener('click', onClick);
    return b;
  }
  function avatar(name, glow) {
    const a = el('div', 'avatar avatar-c' + avatarIndex(name) + (glow ? ' avatar-glow' : ''), initial(name));
    a.setAttribute('aria-hidden', 'true');
    return a;
  }
  function divider() {
    return el('div', 'divider');
  }
  function plural(n, one, many) {
    return n === 1 ? one : many;
  }

  // ── Sections ────────────────────────────────────────────────────────────────
  function renderHeader(m) {
    const h = el('div', 'header');
    h.appendChild(el('div', 'title', 'Listening nearby'));
    h.appendChild(el('span', 'count-badge' + (m.peers.length > 0 ? ' count-active' : ''), String(m.peers.length)));
    return h;
  }

  function renderVibe(m) {
    const n = m.todayVibeCount;
    return el('div', 'vibe-row', '✦  ' + n + ' vibe ' + plural(n, 'match', 'matches') + ' today');
  }

  function renderPeers(m) {
    const list = el('div', 'peer-list');
    if (m.peers.length === 0) {
      list.appendChild(el('div', 'empty', 'No one else is listening right now'));
      return list;
    }
    const canInvite = !!(m.room && m.room.isHost);
    for (const p of m.peers) {
      const row = el('div', 'peer-row' + (p.match === 'SAME_SONG' ? ' peer-vibing' : ''));
      row.appendChild(avatar(p.username, p.match === 'SAME_SONG'));

      const info = el('div', 'peer-info');
      const name = el('div', 'peer-name', p.username);
      name.title = p.username;
      info.appendChild(name);

      const trackRow = el('div', 'track-row');
      const track = el('span', 'peer-track', p.track || '—');
      track.title = p.track || '';
      trackRow.appendChild(track);
      if (p.match === 'SAME_SONG') trackRow.appendChild(el('span', 'badge badge-song', '✦ Vibing!'));
      else if (p.match === 'SAME_ARTIST') trackRow.appendChild(el('span', 'badge badge-artist', '∼ Same artist'));
      info.appendChild(trackRow);
      row.appendChild(info);

      if (canInvite) {
        let btn;
        if (p.inMyRoom) {
          btn = button('pill-btn pill-muted', '✓ Joined', null, true);
        } else if (sentInvites.has(p.username)) {
          btn = button('pill-btn pill-muted', 'Sent', null, true);
        } else {
          const username = p.username;
          btn = button('pill-btn pill-accent', 'Invite', function () {
            sentInvites.add(username);
            persist();
            vscode.postMessage({ type: 'invite', username: username });
            this.disabled = true;
            this.textContent = 'Sent';
            this.className = 'pill-btn pill-muted';
          });
        }
        btn.title = btn.textContent === 'Invite' ? 'Invite ' + p.username + ' to your Focus Room' : '';
        row.appendChild(btn);
      }
      list.appendChild(row);
    }
    return list;
  }

  function renderStartButtons(m) {
    const wrap = el('div', 'duration-row');
    for (const mins of m.durations) {
      wrap.appendChild(
        button('duration-btn', mins + 'm', function () {
          vscode.postMessage({ type: 'startRoom', minutes: mins });
        }),
      );
    }
    return wrap;
  }

  function renderRoom(m) {
    const sec = el('div', 'section room');
    const room = m.room;

    if (room) {
      const head = el('div', 'section-head');
      head.appendChild(el('div', 'section-title', 'Focus Room'));
      head.appendChild(el('span', 'countdown countdown-live', '⏱ ' + room.remaining));
      sec.appendChild(head);

      const members = el('div', 'member-list');
      for (const mb of room.members) {
        const row = el('div', 'member-row');
        row.appendChild(avatar(mb.username, false));
        const name = el('span', 'member-name', mb.username);
        name.title = mb.username;
        row.appendChild(name);
        const tag = mb.isHost && mb.isMe ? 'host · you' : mb.isHost ? 'host' : mb.isMe ? 'you' : '';
        if (tag) row.appendChild(el('span', 'member-tag' + (mb.isHost ? ' member-host' : ''), '(' + tag + ')'));
        if (room.isHost && !mb.isMe) {
          const u = mb.username;
          const kick = button('link-btn', 'Remove', function () {
            vscode.postMessage({ type: 'kick', username: u });
          });
          kick.title = 'Remove ' + u + ' from the room';
          row.appendChild(kick);
        }
        members.appendChild(row);
      }
      sec.appendChild(members);

      sec.appendChild(
        button('wide-btn ' + (room.isHost ? 'danger-btn' : 'secondary-btn'), room.isHost ? 'End Room' : 'Leave Room', function () {
          vscode.postMessage({ type: 'leave' });
        }),
      );
      return sec;
    }

    if (m.nearbyRooms.length > 0) {
      const head = el('div', 'section-head');
      head.appendChild(el('div', 'section-title', 'Focus Room Nearby'));
      head.appendChild(el('span', 'tag-lock', 'invite only'));
      sec.appendChild(head);

      for (const r of m.nearbyRooms) {
        const card = el('div', 'nearby-card');
        card.appendChild(avatar(r.hostName, false));
        const info = el('div', 'nearby-info');
        const host = el('div', 'nearby-host', r.hostName + "'s room");
        host.title = r.hostName + "'s room";
        info.appendChild(host);
        info.appendChild(
          el('div', 'nearby-sub', r.memberCount + ' ' + plural(r.memberCount, 'member', 'members') + ' · ask to be invited'),
        );
        card.appendChild(info);
        card.appendChild(el('span', 'countdown', r.remaining));
        sec.appendChild(card);
      }
      sec.appendChild(el('div', 'subhead', 'Focus Room'));
      sec.appendChild(renderStartButtons(m));
      return sec;
    }

    const head = el('div', 'section-head');
    head.appendChild(el('div', 'section-title', 'Focus Room'));
    sec.appendChild(head);
    sec.appendChild(renderStartButtons(m));
    return sec;
  }

  function renderGhost(m) {
    const locked = !!m.room;
    const row = el('div', 'ghost-row' + (locked ? ' locked' : ''));
    const labels = el('div', 'ghost-labels');
    labels.appendChild(el('div', 'ghost-label', 'Ghost mode'));
    if (locked) labels.appendChild(el('div', 'ghost-hint', 'Leave the room first'));
    else if (m.ghostMode) labels.appendChild(el('div', 'ghost-hint', "You're invisible to teammates"));
    row.appendChild(labels);

    const sw = /** @type {HTMLButtonElement} */ (el('button', 'switch' + (m.ghostMode ? ' on' : '')));
    sw.type = 'button';
    sw.setAttribute('role', 'switch');
    sw.setAttribute('aria-checked', m.ghostMode ? 'true' : 'false');
    sw.setAttribute('aria-label', 'Ghost mode');
    sw.appendChild(el('span', 'knob'));
    if (locked) sw.disabled = true;
    else
      sw.addEventListener('click', function () {
        vscode.postMessage({ type: 'toggleGhost' });
      });
    row.appendChild(sw);
    return row;
  }

  // ── Render ──────────────────────────────────────────────────────────────────
  function isModel(m) {
    return (
      m &&
      typeof m === 'object' &&
      Array.isArray(m.peers) &&
      Array.isArray(m.nearbyRooms) &&
      Array.isArray(m.durations)
    );
  }

  function render(m) {
    if (!app || !isModel(m)) return;

    // Invites belong to a specific room: drop the "Sent" memory when it changes.
    const key = m.room && m.room.isHost ? m.room.hostName : null;
    if (key !== roomKey) {
      roomKey = key;
      sentInvites = new Set();
      persist();
    }

    // Preserve scroll position and keyboard focus across full re-renders.
    const scrollY = window.scrollY;
    const active = document.activeElement;
    const focusKey = active && active !== document.body ? active.textContent + '|' + active.className : null;

    const frag = document.createDocumentFragment();
    frag.appendChild(renderHeader(m));
    if (m.todayVibeCount > 0) frag.appendChild(renderVibe(m));
    frag.appendChild(renderPeers(m));
    frag.appendChild(divider());
    frag.appendChild(renderRoom(m));
    frag.appendChild(divider());
    frag.appendChild(renderGhost(m));
    app.replaceChildren(frag);

    if (focusKey) {
      const buttons = app.querySelectorAll('button');
      for (let i = 0; i < buttons.length; i++) {
        const b = buttons[i];
        if (b.textContent + '|' + b.className === focusKey && !b.disabled) {
          b.focus();
          break;
        }
      }
    }
    window.scrollTo(0, scrollY);
  }

  window.addEventListener('message', function (event) {
    const msg = event.data;
    if (msg && msg.type === 'state') render(msg.model);
  });

  vscode.postMessage({ type: 'ready' });
})();
