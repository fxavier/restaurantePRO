import { useMutation } from '@tanstack/react-query';
import { generateDeviceKey, publicJwk } from '../../../lib/auth/client-assertion';
import type { DeviceRecord, Enrolment } from '../../../lib/auth/device-store';
import { useServices } from '../../../lib/services';

/**
 * Enrols this terminal with a one-time code (D182, FC §6). The key pair is made and stored before the request and
 * reused by every later attempt, so a lost answer can be retried: the backend replays an enrolment only for the key
 * that started it. The code is a mutation variable only: never a key, a URL or a log line.
 */
export function useEnrol() {
  const { api, devices } = useServices();
  return useMutation({
    mutationFn: async (code: string): Promise<Enrolment> => {
      const record = (await devices.load()) ?? (await storeNewKey());
      const { data } = await api.POST('/api/v1/pos/enrolment', {
        body: { code, publicJwk: await publicJwk(record.keyPair) },
      });
      const enrolment = toEnrolment(data);
      await devices.save({ ...record, enrolment });
      return enrolment;
    },
  });

  async function storeNewKey(): Promise<DeviceRecord> {
    const record = { keyPair: await generateDeviceKey() };
    await devices.save(record);
    return record;
  }
}

/** The contract marks every field optional (springdoc); an answer without all three cannot be used. */
function toEnrolment(data: { id?: string; storeId?: string; clientId?: string } | undefined): Enrolment {
  if (!data?.id || !data.storeId || !data.clientId) throw new Error('Incomplete enrolment answer');
  return { id: data.id, storeId: data.storeId, clientId: data.clientId };
}
