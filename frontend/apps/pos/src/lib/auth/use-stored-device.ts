import { useEffect, useState } from 'react';
import { useServices } from '../services';
import type { DeviceRecord } from './device-store';

export type StoredDevice = { loading: true } | { loading: false; record?: DeviceRecord };

/** The device record in IndexedDB, read once per mount; a failed read counts as "no device" (enrol again). */
export function useStoredDevice(): StoredDevice {
  const { devices } = useServices();
  const [device, setDevice] = useState<StoredDevice>({ loading: true });

  useEffect(() => {
    let live = true;
    devices.load().then(
      (record) => live && setDevice({ loading: false, record }),
      () => live && setDevice({ loading: false }),
    );
    return () => {
      live = false;
    };
  }, [devices]);

  return device;
}
