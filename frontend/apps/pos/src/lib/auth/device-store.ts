/** What the backend answered to the enrolment (`DeviceEnrolmentResponse`): the terminal, its store, its client. */
export type Enrolment = { id: string; storeId: string; clientId: string };

/**
 * The terminal's identity on this browser (D182, FC §5): its key pair (private half non-extractable) and, once the
 * backend accepted it, the enrolment. A record without `enrolment` is a key whose enrolment did not finish; it is
 * reused on the next attempt, because the backend replays an enrolment only for the key that started it.
 * Clearing the site data un-enrols the terminal.
 */
export type DeviceRecord = { keyPair: CryptoKeyPair; enrolment?: Enrolment };

export type DeviceStore = {
  load(): Promise<DeviceRecord | undefined>;
  save(record: DeviceRecord): Promise<void>;
  clear(): Promise<void>;
};

const DATABASE = 'restaurantpro-pos';
const STORE = 'device';
const KEY = 'current';

/** A small IndexedDB wrapper: one database, one object store, one record. */
export function createDeviceStore(factory: IDBFactory = indexedDB): DeviceStore {
  let opening: Promise<IDBDatabase> | undefined;

  function database(): Promise<IDBDatabase> {
    opening ??= new Promise((resolve, reject) => {
      const request = factory.open(DATABASE, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(STORE);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return opening;
  }

  async function run<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await database();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(STORE, mode);
      const request = action(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  }

  return {
    async load() {
      return (await run<DeviceRecord | undefined>('readonly', (store) => store.get(KEY))) ?? undefined;
    },
    async save(record) {
      await run('readwrite', (store) => store.put(record, KEY));
    },
    async clear() {
      await run('readwrite', (store) => store.delete(KEY));
    },
  };
}
