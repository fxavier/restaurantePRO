import { useState } from 'react';
import { useChoosePin } from '../hooks/use-choose-pin';
import { pinChangeError } from '../messages';
import { isPin, normalisePin } from '../pin';
import { KeypadStep } from './keypad-step';

const MISMATCH = 'Os dois PIN não coincidem. Introduza o novo PIN de novo.';

/**
 * After a manager's reset (ADR 0008, S10): the session has no authority until the employee chooses a PIN of their
 * own, typed twice. Any failure starts over, so a PIN is never left in a field.
 */
export function ChangePinScreen({ name }: { name: string }) {
  const choose = useChoosePin();
  const [step, setStep] = useState<'new' | 'repeat'>('new');
  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  const [mismatch, setMismatch] = useState(false);
  const error = mismatch ? MISMATCH : choose.error && pinChangeError(choose.error);

  function restart() {
    setFirst('');
    setSecond('');
    setStep('new');
  }

  async function save() {
    if (second !== first) {
      setMismatch(true);
      restart();
      return;
    }
    if (await choose.execute(first)) restart();
  }

  const intro = (
    <>
      <h2 className="text-2xl font-semibold">Escolha um novo PIN</h2>
      <p className="text-muted-foreground">
        {name}, o seu PIN foi reposto por um gerente. Escolha um PIN só seu, de 4 a 8 algarismos.
      </p>
    </>
  );

  if (step === 'new') {
    return (
      <KeypadStep
        key="new"
        id="new-pin"
        label="Novo PIN"
        value={first}
        onChange={setFirst}
        normalise={normalisePin}
        secret
        canSubmit={isPin(first)}
        pending={false}
        submitLabel="Seguinte"
        onSubmit={() => {
          setMismatch(false);
          choose.clearError();
          setStep('repeat');
        }}
        error={error}
      >
        {intro}
      </KeypadStep>
    );
  }

  return (
    <KeypadStep
      key="repeat"
      id="repeat-pin"
      label="Repita o novo PIN"
      value={second}
      onChange={setSecond}
      normalise={normalisePin}
      secret
      canSubmit={isPin(second)}
      pending={choose.pending}
      submitLabel="Guardar PIN"
      onSubmit={() => void save()}
    >
      {intro}
    </KeypadStep>
  );
}
