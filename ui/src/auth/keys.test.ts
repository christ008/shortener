// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { memoryIndexedDB } from '../../test-utils/browser';
import {
    createKeyRecord,
    deleteKeyRecord,
    keepOnlyKeyRecords,
    listKeyRecordKeys,
    loadKeyRecord,
    saveKeyRecord,
    thumbprintOf,
} from './keys';

describe('the browser keys', () => {
    beforeEach(() => {
        vi.stubGlobal('indexedDB', memoryIndexedDB());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('makes a non-extractable key per sign-in, keyed by its thumbprint, with no owner yet', async () => {
        const record = await createKeyRecord();

        expect(record.jkt).toMatch(/^[A-Za-z0-9_-]{43}$/);
        expect(record.owner).toBeUndefined();
        expect(record.pair.privateKey.extractable).toBe(false);
        expect(await thumbprintOf(record.pair)).toBe(record.jkt);
        expect(await loadKeyRecord(record.jkt)).toMatchObject({
            jkt: record.jkt,
            createdAt: record.createdAt,
        });
    });

    it('saves the owner and the refresh token with the key, and deletes a record', async () => {
        const record = await createKeyRecord();
        await saveKeyRecord({ ...record, owner: 'person-1', refreshToken: 'refresh-1' });

        expect(await loadKeyRecord(record.jkt)).toMatchObject({
            owner: 'person-1',
            refreshToken: 'refresh-1',
        });

        await deleteKeyRecord(record.jkt);
        expect(await loadKeyRecord(record.jkt)).toBeUndefined();
    });

    it('keeps only the records it is told to keep, and removes the rest', async () => {
        const kept = await createKeyRecord();
        const dropped = await createKeyRecord();

        await keepOnlyKeyRecords([kept.jkt]);

        expect(await listKeyRecordKeys()).toEqual([kept.jkt]);
        expect(await loadKeyRecord(dropped.jkt)).toBeUndefined();
    });
});
