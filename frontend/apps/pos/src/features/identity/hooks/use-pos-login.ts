import { useSecretCommand } from '../../../lib/use-command';
import { useServices } from '../../../lib/services';

/** `POST /api/v1/auth/pos-login` with the terminal's token (D182); the answer opens the POS session in memory. */
export function usePosLogin() {
  const { api, session } = useServices();
  return useSecretCommand(async (employeeCode: string, pin: string) => {
    const { data } = await api.POST('/api/v1/auth/pos-login', { body: { employeeCode, pin } });
    if (!data) throw new Error('Incomplete login answer');
    session.start(data);
  });
}
