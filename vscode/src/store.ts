/**
 * Minimal key/value persistence. vscode.ExtensionContext.globalState satisfies this,
 * and tests use MemoryStore — keeps network/focusRoom free of any `vscode` import.
 */
export interface KeyValueStore {
  get<T>(key: string, defaultValue: T): T;
  update(key: string, value: unknown): Thenable<void> | Promise<void>;
}

export class MemoryStore implements KeyValueStore {
  private readonly map = new Map<string, unknown>();

  get<T>(key: string, defaultValue: T): T {
    return this.map.has(key) ? (this.map.get(key) as T) : defaultValue;
  }

  update(key: string, value: unknown): Promise<void> {
    this.map.set(key, value);
    return Promise.resolve();
  }
}
