import * as oauth from 'oauth4webapi';

/**
 * The browser keys of the sign-ins, in IndexedDB.
 *
 * - One record per sign-in, keyed by the RFC 7638 thumbprint of its public key (`jkt`). The key is created before the
 *   sign-in, because the authorization request carries its thumbprint, and gets its owner once the token has arrived.
 * - The key pair is not extractable: the page can sign with the private key, and cannot read its bytes.
 * - The refresh token is kept with its key, the only things that persist. The access token is never stored.
 */
export type KeyRecord = {
    jkt: string;
    pair: CryptoKeyPair;
    owner?: string;
    refreshToken?: string;
    createdAt: number;
};

const DB_NAME = 'shortener-ui';
const STORE = 'keys';

let database: Promise<IDBDatabase> | undefined;

function openDatabase(): Promise<IDBDatabase> {
    database ??= new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, 1);
        request.onupgradeneeded = () => request.result.createObjectStore(STORE);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
    });
    return database;
}

async function run<T>(
    mode: IDBTransactionMode,
    action: (store: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
        const request = action(db.transaction(STORE, mode).objectStore(STORE));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error('IndexedDB request failed'));
    });
}

/** The thumbprint of a key pair's public key, for `dpop_jkt`. */
export function thumbprintOf(pair: CryptoKeyPair): Promise<string> {
    return oauth.DPoP({}, pair).calculateThumbprint();
}

/** Creates a key for a sign-in that is about to start, and stores its record without an owner. */
export async function createKeyRecord(): Promise<KeyRecord> {
    const pair = await oauth.generateKeyPair('ES256', { extractable: false });
    const record: KeyRecord = { jkt: await thumbprintOf(pair), pair, createdAt: Date.now() };
    await saveKeyRecord(record);
    return record;
}

export const loadKeyRecord = (jkt: string) =>
    run<KeyRecord | undefined>('readonly', (store) => store.get(jkt));
export const saveKeyRecord = (record: KeyRecord) =>
    run('readwrite', (store) => store.put(record, record.jkt));
export const deleteKeyRecord = (jkt: string) => run('readwrite', (store) => store.delete(jkt));
export const listKeyRecordKeys = () =>
    run<IDBValidKey[]>('readonly', (store) => store.getAllKeys());

/** Deletes every record whose key is not in `keep`: a sign-in that never finished, or an account that was signed out. */
export async function keepOnlyKeyRecords(keep: readonly string[]): Promise<void> {
    for (const key of await listKeyRecordKeys()) {
        if (typeof key === 'string' && !keep.includes(key)) {
            await deleteKeyRecord(key);
        }
    }
}
