import { Button } from '@restaurantpro/ui';
import { useState } from 'react';
import type { EndReason } from '../../../lib/auth/pos-session';
import { usePosLogin } from '../hooks/use-pos-login';
import { answered, loginError } from '../messages';
import { isPin, normaliseCode, normalisePin } from '../pin';
import { KeypadStep } from './keypad-step';

const ENDED: Partial<Record<EndReason, string>> = {
  idle: 'Sessão terminada por inatividade.',
  invalid: 'A sessão terminou. Inicie sessão de novo.',
};

/** The PIN pad (ADR 0008): the employee code, then the PIN with hidden digits. */
export function LoginScreen({ endedBy }: { endedBy?: EndReason }) {
  const login = usePosLogin();
  const [step, setStep] = useState<'code' | 'pin'>('code');
  const [code, setCode] = useState('');
  const [pin, setPin] = useState('');
  const error = login.error && loginError(login.error);
  const notice = endedBy && ENDED[endedBy];

  const heading = (
    <h2 id="login-title" className="text-2xl font-semibold">
      Iniciar sessão
    </h2>
  );

  if (step === 'code') {
    return (
      <KeypadStep
        key="code"
        id="employee-code"
        label="Código de empregado"
        value={code}
        onChange={setCode}
        normalise={normaliseCode}
        canSubmit={code.length > 0}
        pending={false}
        submitLabel="Seguinte"
        onSubmit={() => setStep('pin')}
        error={error}
        notice={notice}
      >
        {heading}
        <p className="text-muted-foreground">Introduza o seu código de empregado ou passe o cartão.</p>
      </KeypadStep>
    );
  }

  async function submit() {
    const failure = await login.execute(code, pin);
    if (failure && answered(failure)) setPin('');
  }

  return (
    <KeypadStep
      key="pin"
      id="pin"
      label="PIN"
      value={pin}
      onChange={setPin}
      normalise={normalisePin}
      secret
      canSubmit={isPin(pin)}
      pending={login.pending}
      submitLabel="Entrar"
      onSubmit={() => void submit()}
      error={error}
      notice={notice}
    >
      {heading}
      <div className="flex items-center justify-between gap-4">
        <p className="text-lg font-medium">Empregado {code}</p>
        <Button
          type="button"
          variant="outline"
          size="touch"
          className="rounded-xl bg-card"
          disabled={login.pending}
          onClick={() => {
            setPin('');
            login.clearError();
            setStep('code');
          }}
        >
          Alterar código
        </Button>
      </div>
    </KeypadStep>
  );
}
