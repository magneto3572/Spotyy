import * as vscode from 'vscode';
import type { PeersViewAction, PeersViewModel } from './viewModel';

/**
 * Sidebar webview for "Listening Nearby" — peers on the LAN, vibe matches,
 * Focus Room controls and the ghost-mode toggle.
 *
 * The webview is a dumb renderer: each `{type:'state', model}` message fully
 * re-renders it, and user intents come back as PeersViewAction messages which
 * are validated here before being forwarded.
 */
export class PeersViewProvider implements vscode.WebviewViewProvider {
  static readonly viewId = 'spotyy.peers';

  private view: vscode.WebviewView | undefined;

  constructor(
    private readonly extensionUri: vscode.Uri,
    private readonly getModel: () => PeersViewModel,
    private readonly onAction: (a: PeersViewAction) => void,
  ) {}

  get visible(): boolean {
    return this.view?.visible ?? false;
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    const mediaRoot = vscode.Uri.joinPath(this.extensionUri, 'media');
    // html before options: setting options first loads an empty document, which VS Code
    // reports as "created a webview without a content security policy"
    view.webview.html = this.buildHtml(view.webview, mediaRoot);
    view.webview.options = { enableScripts: true, localResourceRoots: [mediaRoot] };

    view.webview.onDidReceiveMessage((msg: unknown) => {
      if (isRecord(msg) && msg.type === 'ready') {
        this.post();
        return;
      }
      const action = parseAction(msg);
      if (action) this.onAction(action);
    });
    view.onDidChangeVisibility(() => {
      if (view.visible) this.post();
    });
    view.onDidDispose(() => {
      if (this.view === view) this.view = undefined;
    });
  }

  /** Posts the latest model if the view is visible. Cheap; called every second. */
  refresh(): void {
    if (this.visible) this.post();
  }

  private post(): void {
    const view = this.view;
    if (!view) return;
    let model: PeersViewModel;
    try {
      model = this.getModel();
    } catch {
      return;
    }
    void view.webview.postMessage({ type: 'state', model });
  }

  private buildHtml(webview: vscode.Webview, mediaRoot: vscode.Uri): string {
    const nonce = makeNonce();
    const cssUri = webview.asWebviewUri(vscode.Uri.joinPath(mediaRoot, 'peers.css'));
    const jsUri = webview.asWebviewUri(vscode.Uri.joinPath(mediaRoot, 'peers.js'));
    const csp = [
      "default-src 'none'",
      `style-src ${webview.cspSource}`,
      `script-src 'nonce-${nonce}'`,
    ].join('; ');
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${csp}">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="${cssUri}">
<title>Listening Nearby</title>
</head>
<body>
<div id="app" class="app"></div>
<script nonce="${nonce}" src="${jsUri}"></script>
</body>
</html>`;
  }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

const MAX_USERNAME = 256;

function parseAction(msg: unknown): PeersViewAction | undefined {
  if (!isRecord(msg) || typeof msg.type !== 'string') return undefined;
  switch (msg.type) {
    case 'invite':
    case 'kick': {
      const u = msg.username;
      if (typeof u !== 'string' || u.length === 0 || u.length > MAX_USERNAME) return undefined;
      return { type: msg.type, username: u };
    }
    case 'leave':
      return { type: 'leave' };
    case 'toggleGhost':
      return { type: 'toggleGhost' };
    case 'startRoom': {
      const m = msg.minutes;
      if (typeof m !== 'number' || !Number.isInteger(m) || m <= 0 || m > 24 * 60) return undefined;
      return { type: 'startRoom', minutes: m };
    }
    default:
      return undefined;
  }
}

function makeNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let s = '';
  for (let i = 0; i < 32; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
  return s;
}
