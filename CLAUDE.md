# Spotyy — Claude Context Document

## Project Overview

**Spotyy** is a JetBrains IDE plugin (Kotlin, IntelliJ Platform SDK) that embeds Spotify playback controls into the IDE status bar and adds a **local-network team music layer** — users on the same Wi-Fi can see what teammates are listening to, get vibe-match highlights, and collaborate in shared Focus Rooms.

- **Platform:** macOS only (uses AppleScript to talk to the Spotify desktop app)
- **Plugin ID:** `com.github.magneto3572.spotyy`
- **Current version:** `0.0.4` (in `gradle.properties`)
- **Supported IDEs:** IntelliJ 2022.3+ (`pluginSinceBuild = 231`), Android Studio Giraffe+, all JetBrains IDEs
- **Build tool:** Gradle + IntelliJ Platform Gradle Plugin

---

## Repository Layout

```
src/main/kotlin/com/magneto/spotyy/
├── focus/
│   └── FocusRoomService.kt       # Focus Room state machine + UDP protocol
├── network/
│   └── NetworkDiscoveryService.kt # UDP broadcast/listen, peer tracking, ghost mode
├── review/
│   └── ReviewNudgeService.kt     # One-time Marketplace review prompt after 3 days
├── spotify/
│   └── SpotifyMacService.kt      # All AppleScript calls to Spotify
├── startup/
│   └── MyProjectActivity.kt      # ProjectActivity — wires up services on project open
├── statusbar/
│   ├── MyStatusBarWidget.kt      # The entire UI: status bar panel + all popups
│   └── MyStatusBarWidgetFactory.kt
├── services/
│   └── MyProjectService.kt       # Placeholder project service
├── toolWindow/
│   └── MyToolWindowFactory.kt    # Unused tool window stub
└── MyBundle.kt                   # i18n bundle helper

src/main/resources/
├── META-INF/plugin.xml           # Plugin descriptor, extension points, description
├── messages/MyBundle.properties
└── icons/                        # SVG icons, light + dark variants
```

---

## Architecture & Data Flow

### 1. Startup (`MyProjectActivity`)
Runs once per project open on an IO coroutine:
- Calls `NetworkDiscoveryService.start()` → starts background UDP listener thread
- Wires `NetworkDiscoveryService.roomMessageHandler = { FocusRoomService.handleMessage(it) }` to avoid circular imports
- Sets `FocusRoomService.project` and `onRoomInvite` callback
- Calls `ReviewNudgeService.maybeShowReviewNudge()`

### 2. Status Bar Widget (`MyStatusBarWidget`)
- Registered via `MyStatusBarWidgetFactory` as a `CustomStatusBarWidget`
- Creates a `JPanel` with playback buttons + "Now Playing" label + peers button
- A **3-second `javax.swing.Timer`** polls Spotify (via `SpotifyMacService`) and broadcasts the track over UDP
- A **separate popup refresh timer** (1-second) ticks countdown timers and detects peer/room changes

### 3. Network Layer (`NetworkDiscoveryService`)
- **Port:** 57372 UDP, broadcast to all active subnet broadcast addresses (falls back to 255.255.255.255)
- **Peer broadcast format:** `SPOTYY|<username>|<track>|<isPlaying>`
- **Peer timeout:** 10 seconds — peers not seen are pruned from the active list
- `localUsername` = `System.getProperty("user.name")`
- Ghost mode: stored in `PropertiesComponent`, suppresses all broadcasts and clears local track
- Routes any packet starting with `SPOTYY_ROOM` to `FocusRoomService` via `roomMessageHandler` callback

### 4. Focus Room (`FocusRoomService`)
Singleton object. Manages one active room at a time.

**Room packet format:**
```
SPOTYY_ROOM|<ACTION>|<roomId>|<hostName>|<durationSecs>|<startTs>|<sender>|<target>
```

**Actions:** `INVITE`, `PING`, `JOIN`, `LEAVE`, `KICK`, `END`

**State:**
- `currentRoom: FocusRoom?` — the room this user is currently in
- `isHost: Boolean`
- `nearbyRooms: ConcurrentHashMap<String, NearbyRoom>` — rooms seen on the network that the user has NOT joined (populated from PING/JOIN packets when `currentRoom == null`)

**Key methods:**
- `startRoom(durationMinutes)` — creates a room, sets `isHost = true`, no auto-broadcast (invite-only)
- `invitePeer(targetUsername)` — sends `INVITE` packet; only the named target sees a notification
- `joinRoom(room)` — adds self to room, removes from `nearbyRooms`, sends `JOIN`
- `leaveRoom()` — sends `LEAVE`, clears `currentRoom`
- `kickPeer(targetUsername)` — host only; sends `KICK`, removes from member list
- `ping()` — called every 3s from widget timer; sends `PING`, prunes stale members, triggers `onRoomEnded` on expiry
- `getNearbyRooms()` — returns active `NearbyRoom` list (prunes expired rooms/members), used by popup for non-members

**Callbacks (set by `MyProjectActivity`):**
- `onRoomInvite: ((FocusRoom) -> Unit)?` — fires on EDT when an INVITE packet arrives for this user
- `onRoomEnded: (() -> Unit)?` — fires on EDT when room expires or END/KICK received

---

## UI: Peers Popup (`showPeersDialog`)

The popup is a `JDialog` (undecorated, MODELESS, transparent background) with a custom rounded-rect `root` panel.

**Critical layout rule:** The popup is a fixed **300px wide** `BoxLayout Y_AXIS` stack. Every direct child must have:
```kotlin
child.minimumSize = Dimension(popupWidth, 0)    // forces BoxLayout to allocate exactly 300px
child.maximumSize = Dimension(popupWidth, N)     // caps height
```
Without `minimumSize`, BoxLayout Y_AXIS computes `min(max(contentMinWidth, containerWidth), maxWidth)` — if `contentMinWidth > containerWidth`, the child overflows the container and gets clipped.

**Popup sections (top to bottom):**
1. **Header** — "Listening nearby" + peer count badge
2. **Vibe row** (conditional) — shown if `getTodayVibeCount() > 0`
3. **Peer list** — one row per active peer (avatar + name + truncated track + optional invite button); scrollable if > 5 peers
4. **Divider**
5. **Focus Room section** — one of three states:
   - User IS in a room: header with live countdown + member list + Leave/End Room button
   - User is NOT in a room but nearby rooms exist: "Focus Room Nearby" cards (host, member count, countdown) + Start Room buttons
   - User is NOT in a room and no nearby rooms: just Start Room duration buttons (25m / 45m / 60m)
6. **Divider**
7. **Ghost mode toggle** — locked (alpha 0.35) while in a room

**Popup refresh timer (1-second `javax.swing.Timer`):**
- Always updates `timerLbl` (active room countdown) and `nearbyTimerLabels` (nearby room countdowns)
- Every 3 ticks: checks `peerSnapshot`, `memberSnapshot`, `nearbySnapshot` — rebuilds popup on any change
- If `wasInRoom != nowInRoom`, rebuilds immediately

**Track label truncation** — pixel-accurate via `JLabel().getFontMetrics(font)`:
```kotlin
val maxTrackPx = popupWidth - 28 - 38 - 20 - (if (hasInviteBtn) 74 else 0) - 4
```

---

## Spotify Integration (`SpotifyMacService`)

All Spotify communication is via AppleScript (`osascript`). Key contract:
- `getCurrentTrack()` → `SpotifyState(isRunning, isPlaying, trackInfo, volume)`
- `trackInfo` format: `"Artist - Track Name"`
- All AppleScript calls run on a daemon thread with a 3-second process timeout and 5-second future timeout
- `setVolume()` dispatches to a pooled thread to avoid blocking EDT

---

## Threading Model

| Thread | What runs there |
|---|---|
| EDT | All Swing UI, popup creation, timer callbacks (`invokeLater`) |
| `Spotyy-Discovery` daemon | UDP socket listen loop (`NetworkDiscoveryService`) |
| Pooled (IntelliJ) | AppleScript calls, UDP broadcast sends, `FocusRoomService.invitePeer()` |
| Coroutine IO (`Dispatchers.IO`) | `MyProjectActivity.execute()` startup |
| `javax.swing.Timer` | Status bar 3s poll + popup 1s refresh (fires on EDT) |

**Rule:** Never do network I/O or AppleScript on the EDT. Always use `executeOnPooledThread` or a background thread, then `invokeLater` to update UI.

---

## Build & Distribution

```bash
# Compile + build jar (works even when sandbox IDE is open)
JAVA_HOME="C:/Users/sejal/.jdks/corretto-17.0.15" ./gradlew compileKotlin jar --no-daemon

# Full plugin zip (fails if sandbox IDE has files locked)
JAVA_HOME="C:/Users/sejal/.jdks/corretto-17.0.15" ./gradlew buildPlugin --no-daemon
```

**When `buildPlugin` fails** (sandbox file lock), patch the zip manually:
1. Compile jar: `gradlew compileKotlin jar`
2. Take the existing `build/distributions/Spotyy-0.0.5.zip` as a base
3. Use PowerShell `System.IO.Compression.ZipFile` to rebuild with correct structure

**Correct JetBrains plugin zip structure** (one root folder, no nested zips):
```
Spotyy-0.0.4.zip
└── Spotyy/
    └── lib/
        └── Spotyy-0.0.4.jar
```

**JDK:** `corretto-17.0.15` at `C:/Users/sejal/.jdks/corretto-17.0.15`

---

## Key Constraints & Gotchas

1. **macOS only** — all Spotify communication is via AppleScript; no Windows/Linux support
2. **UDP is unreliable** — always design for packet loss; PING every 3s + 10s timeout is the heartbeat
3. **Same subnet required** — most routers drop 255.255.255.255 limited broadcast; `NetworkDiscoveryService` iterates all active non-loopback interfaces to get subnet broadcast addresses (e.g. 192.168.1.255)
4. **BoxLayout Y_AXIS width** — child width = `min(max(child.minWidth, container.width), child.maxWidth)`. If `child.minWidth > container.width`, the child overflows. Always set BOTH `minimumSize` and `maximumSize` on every stack child to pin width to exactly `popupWidth = 300`
5. **Ghost mode** — when enabled, sends one empty broadcast and stops; `localTrack` is cleared; popup pill is locked at `alpha = 0.35` only when `isInRoom`
6. **Focus Room is invite-only** — `startRoom()` does NOT broadcast; `invitePeer()` sends a directed INVITE (only the named target sees it); non-members see the room via PING packets passively
7. **`preferredSize = Dimension(w, 0)`** causes `pack()` to create a zero-height dialog — use an anonymous subclass overriding `getPreferredSize()` instead
8. **Review nudge** — stored in `PropertiesComponent`; shows once after 3 days of use; keys: `spotyy.first.use.date`, `spotyy.review.shown`

---

## Extension Points (plugin.xml)

```xml
<statusBarWidgetFactory id="..." implementation="MyStatusBarWidgetFactory"/>
<postStartupActivity implementation="MyProjectActivity"/>
<notificationGroup id="Spotyy" displayType="BALLOON"/>
```

---

## GitHub & Releases

- **Repo:** `magneto3572/Spotyy`
- **Release convention:** tag = version string (e.g. `0.0.4`), asset = `Spotyy-0.0.4.zip`
- **Marketplace upload:** use the zip from the GitHub release assets page
