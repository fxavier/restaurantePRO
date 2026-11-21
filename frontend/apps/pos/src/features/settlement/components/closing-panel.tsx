import { Button } from '@restaurantpro/ui';
import { CircleCheck, FileText } from 'lucide-react';
import { type ReactNode, useState } from 'react';
import { type Buyer, type FiscalDocument, type Reprint, useCloseOrder, useIssueOrderDocument } from '../hooks/use-settlement-commands';
import type { Can } from '../hooks/use-permissions';
import { canClose, canIssueDocument, type Settlement } from '../settlement';
import { CommandStatus } from './command-status';
import { DocumentNotice, ReprintButton } from './document-actions';

type Props = {
  settlement: Settlement;
  /** Who the order's document is for; not ready while a NIF is asked for and still empty. */
  buyer: { ready: boolean; buyer?: Buyer };
  /** "Sem ligação": no command, not even a copy (FC §11); the open-bill rules are canClose/canIssueDocument. */
  blocked: boolean;
  /** The order is closed: back to the tables. */
  onClosed: () => void;
  /** Above the actions: who the document is for. */
  children?: ReactNode;
  can: Can;
};

/**
 * Stitch's "Liquidar": the order's document and the close (§8.5 "emitir documento e fechar"). Both wait for the
 * server's SETTLED; the close issues the document itself when it was not issued here (D70).
 */
export function ClosingPanel({ settlement, buyer, blocked, onClosed, children, can }: Props) {
  const [issued, setIssued] = useState<FiscalDocument>();
  const [reprint, setReprint] = useState<Reprint>();
  const issue = useIssueOrderDocument(settlement.orderId, (document) => {
    setReprint(undefined);
    setIssued(document);
  });
  const close = useCloseOrder(settlement.orderId, onClosed);
  const busy = blocked || issue.intent !== undefined || close.intent !== undefined;

  return (
    <section aria-labelledby="closing-title" className="flex flex-col gap-3">
      <h3 id="closing-title" className="sr-only">
        Documento e fecho
      </h3>
      {children}
      {!settlement.settled && <p className="text-muted-foreground">A conta ainda não está paga.</p>}
      {can.collect && (
        <Button size="touch-primary" disabled={busy || !canClose(settlement)} onClick={() => close.start(settlement.orderVersion, undefined)}>
          <CircleCheck aria-hidden="true" />
          Fechar conta
        </Button>
      )}
      <div className="flex flex-wrap gap-2">
        {can.collect && (
          <Button
            variant="outline"
            size="touch"
            className="flex-1"
            disabled={busy || !canIssueDocument(settlement) || !buyer.ready}
            onClick={() => issue.start(buyer.buyer, undefined)}
          >
            <FileText aria-hidden="true" />
            Emitir documento
          </Button>
        )}
        {can.reprint && settlement.fiscalState === 'ISSUED' && settlement.documentId && (
          <ReprintButton
            documentId={settlement.documentId}
            disabled={blocked}
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
          blocked={blocked}
        />
        <CommandStatus
          command={close}
          pendingText="A fechar a conta…"
          unconfirmedText="Sem ligação — o fecho não foi confirmado."
          refusedText="A conta não foi fechada."
          blocked={blocked}
        />
      </div>
    </section>
  );
}
