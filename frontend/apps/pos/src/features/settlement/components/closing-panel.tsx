import { Button } from '@restaurantpro/ui';
import { CircleCheck, FileText } from 'lucide-react';
import { type ReactNode, useState } from 'react';
import { type Buyer, type FiscalDocument, type Reprint, useCloseOrder, useIssueOrderDocument } from '../hooks/use-settlement-commands';
import { canClose, canIssueDocument, type Settlement } from '../settlement';
import { CommandStatus } from './command-status';
import { DocumentNotice, ReprintButton } from './document-actions';

type Props = {
  settlement: Settlement;
  /** Who the order's document is for; not ready while a NIF is asked for and still empty. */
  buyer: { ready: boolean; buyer?: Buyer };
  disabled: boolean;
  /** The order is closed: back to the tables. */
  onClosed: () => void;
  /** Above the actions: who the document is for. */
  children?: ReactNode;
};

/**
 * Stitch's "Liquidar": the order's document and the close (§8.5 "emitir documento e fechar"). Both wait for the
 * server's SETTLED; the close issues the document itself when it was not issued here (D70).
 */
export function ClosingPanel({ settlement, buyer, disabled, onClosed, children }: Props) {
  const [issued, setIssued] = useState<FiscalDocument>();
  const [reprint, setReprint] = useState<Reprint>();
  const issue = useIssueOrderDocument(settlement.orderId, (document) => {
    setReprint(undefined);
    setIssued(document);
  });
  const close = useCloseOrder(settlement.orderId, onClosed);
  const busy = disabled || issue.intent !== undefined || close.intent !== undefined;

  return (
    <section aria-labelledby="closing-title" className="flex flex-col gap-3">
      <h3 id="closing-title" className="sr-only">
        Documento e fecho
      </h3>
      {children}
      {!settlement.settled && <p className="text-muted-foreground">A conta ainda não está paga.</p>}
      <Button size="touch-primary" disabled={busy || !canClose(settlement)} onClick={() => close.start(settlement.orderVersion, undefined)}>
        <CircleCheck aria-hidden="true" />
        Fechar conta
      </Button>
      <div className="grid grid-cols-2 gap-2">
        <Button
          variant="outline"
          size="touch"
          disabled={busy || !canIssueDocument(settlement) || !buyer.ready}
          onClick={() => issue.start(buyer.buyer, undefined)}
        >
          <FileText aria-hidden="true" />
          Emitir documento
        </Button>
        {settlement.fiscalState === 'ISSUED' && settlement.documentId && (
          <ReprintButton
            documentId={settlement.documentId}
            disabled={disabled}
            onPrinted={(copy) => {
              setIssued(undefined);
              setReprint(copy);
            }}
          />
        )}
      </div>
      <div className="min-h-12">
        <DocumentNotice label="Documento" issued={issued} reprint={reprint} />
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
