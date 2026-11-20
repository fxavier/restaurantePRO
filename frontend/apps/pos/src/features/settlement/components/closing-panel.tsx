import { Button, cn, Input, Label } from '@restaurantpro/ui';
import { CircleCheck, FileText } from 'lucide-react';
import { type ReactNode, useState } from 'react';
import { type Buyer, type FiscalDocument, useCloseOrder, useIssueOrderDocument } from '../hooks/use-settlement-commands';
import { canClose, canIssueDocument, type Settlement } from '../settlement';
import { CommandStatus } from './command-status';

type Props = {
  settlement: Settlement;
  disabled: boolean;
  /** The order is closed: back to the tables. */
  onClosed: () => void;
};

type BuyerFields = { taxId: string; name: string; address: string };

/**
 * Stitch's right column: who the document is for, its issue, and the close (§8.5 "emitir documento e fechar"). Both
 * wait for the server's SETTLED; the close issues the document itself when it was not issued here.
 */
export function ClosingPanel({ settlement, disabled, onClosed }: Props) {
  const [withTaxId, setWithTaxId] = useState(false);
  const [buyer, setBuyer] = useState<BuyerFields>({ taxId: '', name: '', address: '' });
  const [issued, setIssued] = useState<FiscalDocument>();
  const issue = useIssueOrderDocument(settlement.orderId, setIssued);
  const close = useCloseOrder(settlement.orderId, onClosed);
  const busy = disabled || issue.intent !== undefined || close.intent !== undefined;
  const buyerReady = !withTaxId || buyer.taxId.trim() !== '';

  return (
    <section aria-labelledby="closing-title" className="flex flex-col gap-4">
      <h3 id="closing-title" className="sr-only">
        Documento e fecho
      </h3>
      <fieldset className="flex flex-col gap-3 rounded-xl border bg-card p-4">
        <legend className="sr-only">Identificação fiscal</legend>
        <p aria-hidden="true" className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Identificação fiscal
        </p>
        <div className="grid grid-cols-2 gap-2">
          <Choice checked={!withTaxId} onChange={() => setWithTaxId(false)}>
            Consumidor final
          </Choice>
          <Choice checked={withTaxId} onChange={() => setWithTaxId(true)}>
            Com NIF
          </Choice>
        </div>
        {withTaxId && (
          <div className="flex flex-col gap-3">
            <Field id="buyer-tax-id" label="NIF" value={buyer.taxId} onChange={(taxId) => setBuyer({ ...buyer, taxId })} />
            <Field id="buyer-name" label="Nome" value={buyer.name} onChange={(name) => setBuyer({ ...buyer, name })} />
            <Field id="buyer-address" label="Morada" value={buyer.address} onChange={(address) => setBuyer({ ...buyer, address })} />
          </div>
        )}
      </fieldset>
      {!settlement.settled && <p className="text-muted-foreground">A conta ainda não está paga.</p>}
      <Button
        variant="outline"
        size="touch"
        disabled={busy || !canIssueDocument(settlement) || !buyerReady}
        onClick={() => issue.start(withTaxId ? buyerOf(buyer) : undefined, undefined)}
      >
        <FileText aria-hidden="true" />
        Emitir documento
      </Button>
      <Button size="touch-primary" disabled={busy || !canClose(settlement)} onClick={() => close.start(settlement.orderVersion, undefined)}>
        <CircleCheck aria-hidden="true" />
        Fechar conta
      </Button>
      <div className="min-h-12">
        {issued && (
          <p role="status" aria-label="Documento" className="font-medium">
            {documentName(issued)} emitido.
          </p>
        )}
        <CommandStatus
          command={issue}
          pendingText="A emitir o documento…"
          unconfirmedText="Sem ligação — o documento não foi confirmado."
          refusedText="O documento não foi emitido."
          blocked={disabled}
        />
        <CommandStatus
          command={close}
          pendingText="A fechar a conta…"
          unconfirmedText="Sem ligação — o fecho não foi confirmado."
          refusedText="A conta não foi fechada."
          blocked={disabled}
        />
      </div>
    </section>
  );
}

/** Only what was typed: an empty field is not sent. */
function buyerOf({ taxId, name, address }: BuyerFields): Buyer {
  const entries = Object.entries({ taxId, name, address }).filter(([, value]) => value.trim() !== '');
  return Object.fromEntries(entries.map(([key, value]) => [key, value.trim()]));
}

/** "Documento A/42", its series and number as the server issued it. */
export function documentName(document: FiscalDocument): string {
  return document.seriesCode && document.number !== undefined ? `Documento ${document.seriesCode}/${document.number}` : 'Documento';
}

function Choice({ checked, onChange, children }: { checked: boolean; onChange: () => void; children: ReactNode }) {
  return (
    <label
      className={cn(
        'flex min-h-12 cursor-pointer items-center justify-center rounded-lg border-2 px-3 text-center font-semibold has-focus-visible:ring-2 has-focus-visible:ring-ring',
        checked ? 'border-primary bg-primary text-primary-foreground' : 'border-border bg-muted',
      )}
    >
      <input type="radio" name="buyer-kind" checked={checked} onChange={onChange} className="sr-only" />
      {children}
    </label>
  );
}

function Field({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} autoComplete="off" value={value} onChange={(event) => onChange(event.target.value)} className="h-12" />
    </div>
  );
}
