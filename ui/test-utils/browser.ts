import { vi } from 'vitest';

/**
 * A browser for the tests that need one and not a DOM: storage, a location and the network, in memory. The tests stub what they
 * use, and the real values, such as the keys, come from the platform's WebCrypto.
 */

/** A `Storage` in memory. */
export function memoryStorage(): Storage {
    const values = new Map<string, string>();
    return {
        get length() {
            return values.size;
        },
        clear: () => values.clear(),
        getItem: (key: string) => values.get(key) ?? null,
        key: (index: number) => [...values.keys()][index] ?? null,
        removeItem: (key: string) => {
            values.delete(key);
        },
        setItem: (key: string, value: string) => {
            values.set(key, value);
        },
    };
}

/** An IndexedDB in memory, for the operations the key store uses. It keeps values as they are: a CryptoKey cannot be cloned in a test. */
export function memoryIndexedDB() {
    const stores = new Map<string, Map<IDBValidKey, unknown>>();
    const request = (run: () => unknown) => {
        const pending: { result?: unknown; onsuccess?: () => void; onerror?: () => void } = {};
        queueMicrotask(() => {
            pending.result = run();
            pending.onsuccess?.();
        });
        return pending;
    };
    const database = {
        createObjectStore: (name: string) => stores.set(name, new Map()),
        transaction: (name: string) => ({
            objectStore: () => {
                const store = stores.get(name) as Map<IDBValidKey, unknown>;
                return {
                    get: (key: IDBValidKey) => request(() => store.get(key)),
                    put: (value: unknown, key: IDBValidKey) =>
                        request(() => {
                            store.set(key, value);
                            return key;
                        }),
                    delete: (key: IDBValidKey) =>
                        request(() => {
                            store.delete(key);
                        }),
                    getAllKeys: () => request(() => [...store.keys()]),
                };
            },
        }),
    };
    return {
        open: () => {
            const pending: {
                result?: unknown;
                onupgradeneeded?: () => void;
                onsuccess?: () => void;
            } = {};
            queueMicrotask(() => {
                pending.result = database;
                pending.onupgradeneeded?.();
                pending.onsuccess?.();
            });
            return pending;
        },
    };
}

/** Stubs the browser globals the app reads, and returns the fetch mock the test answers with. */
export function stubBrowser(origin = 'http://localhost:3000') {
    const fetch = vi.fn<(input: Request | URL | string, init?: RequestInit) => Promise<Response>>();
    const location = {
        origin,
        href: `${origin}/app/`,
        assign: vi.fn((url: string) => (location.href = url)),
    };
    vi.stubGlobal('window', { location });
    vi.stubGlobal('sessionStorage', memoryStorage());
    vi.stubGlobal('localStorage', memoryStorage());
    vi.stubGlobal('indexedDB', memoryIndexedDB());
    vi.stubGlobal('fetch', fetch);
    return { fetch, location };
}

/** A JSON answer. */
export function json(body: unknown, init: ResponseInit = {}): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        ...init,
    });
}

/** An unsigned JWT with these claims: the app reads the claims and does not check the signature. */
export function jwt(claims: Record<string, unknown>): string {
    return [
        'eyJhbGciOiJSUzI1NiJ9',
        Buffer.from(JSON.stringify(claims)).toString('base64url'),
        'signature',
    ].join('.');
}
