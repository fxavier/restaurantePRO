import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { CheckCircle2, MinusCircle, Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { usePaymentMethods } from '../hooks/use-reports';
import type { PaymentMethodView } from '../types';
import { KIND_LABELS, MethodDialog } from './method-dialog';
import { QueryState } from './query-state';

const ACTIVE = {
  true: { label: 'Ativo', icon: CheckCircle2, tone: 'success' as const },
  false: { label: 'Inativo', icon: MinusCircle, tone: 'neutral' as const },
};

/** "Meios de pagamento" (§7): the company's methods; reading and changing them needs a step-up (D142). */
export function PaymentMethodsTab({ storeId }: { storeId: string }) {
  const methods = usePaymentMethods();
  const [editing, setEditing] = useState<{ method?: PaymentMethodView } | null>(null);
  return (
    <Card className="gap-4">
      <CardHeader className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          <CardTitle className="text-lg">Meios de pagamento</CardTitle>
          <CardDescription>Os meios que os terminais POS aceitam, em todas as lojas.</CardDescription>
        </div>
        {methods.data && (
          <Button onClick={() => setEditing({})}>
            <Plus aria-hidden="true" />
            Novo meio de pagamento
          </Button>
        )}
      </CardHeader>
      <CardContent>
        <QueryState query={methods} empty={!methods.data?.length} emptyMessage="Ainda não há meios de pagamento." subject="os meios de pagamento">
          <Table>
            <TableCaption className="sr-only">Meios de pagamento</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Nome</TableHead>
                <TableHead>Código</TableHead>
                <TableHead>Tipo</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {methods.data?.map((method) => (
                <TableRow key={method.id}>
                  <TableCell className="font-medium">{method.name}</TableCell>
                  <TableCell className="font-mono text-muted-foreground">{method.code}</TableCell>
                  <TableCell>
                    <Badge variant="secondary">{KIND_LABELS[method.kind ?? ''] ?? method.kind}</Badge>
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={method.active ? 'true' : 'false'} statuses={ACTIVE} />
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end">
                      <Button size="sm" variant="outline" aria-label={`Editar ${method.name}`} onClick={() => setEditing({ method })}>
                        <Pencil aria-hidden="true" />
                        Editar
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </QueryState>
      </CardContent>
      <MethodDialog storeId={storeId} open={editing !== null} method={editing?.method} onClose={() => setEditing(null)} />
    </Card>
  );
}
