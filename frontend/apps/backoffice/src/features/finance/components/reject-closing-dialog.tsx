import { zodResolver } from '@hookform/resolvers/zod';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@restaurantpro/ui';
import { useForm } from 'react-hook-form';
import { rejectClosingSchema, type RejectClosingValues } from '../schemas';
import type { ClosingView } from '../types';
import { textareaClass } from './styles';

type Props = {
  closing: ClosingView | null;
  name: string;
  onConfirm: (closing: ClosingView, reason: string) => void;
  onCancel: () => void;
};

/** Rejecting sends the closing back to the register for a recount (§8.3); the reason is required and audited. */
export function RejectClosingDialog({ closing, name, onConfirm, onCancel }: Props) {
  return (
    <Dialog open={closing !== null} onOpenChange={(open) => !open && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Rejeitar fecho</DialogTitle>
          <DialogDescription>O fecho da caixa {name} volta à caixa para ser contado de novo.</DialogDescription>
        </DialogHeader>
        {closing && <RejectForm onConfirm={(reason) => onConfirm(closing, reason)} onCancel={onCancel} />}
      </DialogContent>
    </Dialog>
  );
}

function RejectForm({ onConfirm, onCancel }: { onConfirm: (reason: string) => void; onCancel: () => void }) {
  const form = useForm<RejectClosingValues>({ resolver: zodResolver(rejectClosingSchema), defaultValues: { reason: '' } });
  const onSubmit = form.handleSubmit(({ reason }) => onConfirm(reason));
  return (
    <Form {...form}>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        <FormField
          control={form.control}
          name="reason"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Motivo</FormLabel>
              <FormControl>
                <textarea {...field} maxLength={500} className={textareaClass} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onCancel}>
            Cancelar
          </Button>
          <Button type="submit" variant="destructive">
            Rejeitar
          </Button>
        </DialogFooter>
      </form>
    </Form>
  );
}
