import { IDBFactory } from 'fake-indexeddb';
import { describe, expect, it } from 'vitest';
import { generateDeviceKey } from './client-assertion';
import { createDeviceStore } from './device-store';

const ENROLMENT = {
  id: '6f1c2a9e-0000-4000-8000-000000000001',
  storeId: '6f1c2a9e-0000-4000-8000-0000000000aa',
  clientId: 'pos-6f1c2a9e-0000-4000-8000-000000000001',
};

describe('device store (IndexedDB)', () => {
  it('has no device on a fresh terminal', async () => {
    const store = createDeviceStore(new IDBFactory());

    await expect(store.load()).resolves.toBeUndefined();
  });

  it('keeps the key pair, still non-extractable and able to sign, with the enrolment', async () => {
    const factory = new IDBFactory();
    const keyPair = await generateDeviceKey();
    await createDeviceStore(factory).save({ keyPair, enrolment: ENROLMENT });

    const loaded = await createDeviceStore(factory).load();

    expect(loaded?.enrolment).toEqual(ENROLMENT);
    expect(loaded?.keyPair.privateKey.extractable).toBe(false);
    const signature = await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      loaded!.keyPair.privateKey,
      new Uint8Array([1]),
    );
    expect(signature.byteLength).toBe(64);
  });

  it('replaces the record on save, and forgets it on clear', async () => {
    const store = createDeviceStore(new IDBFactory());
    const keyPair = await generateDeviceKey();
    await store.save({ keyPair });
    await store.save({ keyPair, enrolment: ENROLMENT });

    expect((await store.load())?.enrolment).toEqual(ENROLMENT);

    await store.clear();

    await expect(store.load()).resolves.toBeUndefined();
  });
});
