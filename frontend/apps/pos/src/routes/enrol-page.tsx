import { ApiError } from '@restaurantpro/api-client';
import { Navigate, useNavigate } from 'react-router';
import { EnrolmentForm } from '../features/organization/components/enrolment-form';
import { useEnrol } from '../features/organization/hooks/use-enrol';
import { useStoredDevice } from '../lib/auth/use-stored-device';
import { errorMessage } from '../lib/error-messages';
import { PosScreen } from './pos-screen';

/** `/enrol` (D182, FC §6): a terminal without a device key; an enrolled one goes on to the PIN screen. */
export function EnrolPage() {
  const device = useStoredDevice();
  const enrol = useEnrol();
  const navigate = useNavigate();

  if (device.loading) return null;
  if (device.record?.enrolment && !enrol.isPending) return <Navigate to="/login" replace />;

  const message = enrol.error ? enrolmentError(enrol.error) : undefined;

  return (
    <PosScreen>
      <section
        aria-labelledby="enrol-title"
        className="flex w-full max-w-[808px] flex-col gap-4 rounded-xl border bg-background p-6"
      >
        <div className="flex flex-col gap-1">
          <h2 id="enrol-title" className="text-2xl font-semibold">
            Registar este terminal
          </h2>
          <p className="text-muted-foreground">
            Introduza o código de registo que o administrador criou para este terminal.
          </p>
        </div>
        <EnrolmentForm
          pending={enrol.isPending}
          message={message}
          onSubmit={(code) => enrol.mutate(code, { onSuccess: () => navigate('/login', { replace: true }) })}
        />
      </section>
    </PosScreen>
  );
}

function enrolmentError(error: Error): string {
  if (error instanceof ApiError) {
    return errorMessage(error, { offline: 'Sem ligação — o terminal não foi registado. Tente novamente.' });
  }
  // WebCrypto or IndexedDB failed (a private window, an old browser): the key cannot be kept.
  return 'Este navegador não consegue guardar a chave do terminal. Use um navegador atualizado, fora da navegação privada.';
}
