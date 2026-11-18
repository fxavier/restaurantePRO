import { useSecretCommand } from '../../../lib/use-command';
import { useServices } from '../../../lib/services';

/** `POST /api/v1/auth/pos-pin` after a manager's reset; the answer gives the session the employee's authorities. */
export function useChoosePin() {
  const { api, session } = useServices();
  return useSecretCommand(async (newPin: string) => {
    const sent = session.token(); // what posMiddleware sends with this request
    const { data } = await api.POST('/api/v1/auth/pos-pin', { body: { newPin } });
    if (!data) throw new Error('Incomplete PIN change answer');
    session.update(data, sent);
  });
}
