import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
} from '@restaurantpro/ui';
import { useFormContext } from 'react-hook-form';
import { useUpdatePrinter } from '../hooks/use-printer-commands';
import type { Printer } from '../parse';
import { updateRequest } from '../printer-requests';
import type { PrinterFormValues } from '../schemas';
import { PrinterForm } from './printer-form';

type EditPrinterDialogProps = {
  /** The printer as last read: a version conflict refetches it, and the next "Guardar" goes at the new version. */
  printer: Printer | undefined;
  onClose: () => void;
};

/** "Configurar": a printer's identity, address and whether it takes work; its paper settings stay as they are. */
export function EditPrinterDialog({ printer, onClose }: EditPrinterDialogProps) {
  const update = useUpdatePrinter();
  const close = () => {
    update.reset();
    onClose();
  };
  return (
    <Dialog open={printer !== undefined} onOpenChange={(open) => !open && close()}>
      <DialogContent showCloseButton={false} className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Configurar {printer?.name}</DialogTitle>
          <DialogDescription>O nome, o endereço na rede e se a impressora recebe trabalhos.</DialogDescription>
        </DialogHeader>
        {printer && (
          <PrinterForm
            defaultValues={valuesOf(printer)}
            submitLabel="Guardar"
            pending={update.isPending}
            error={update.error}
            send={(values) => update.mutateAsync({ id: printer.id, body: updateRequest(values, printer.version) }).then(close)}
            extraFields={<ActiveField />}
          >
            <Button type="button" variant="outline" onClick={close}>
              Cancelar
            </Button>
          </PrinterForm>
        )}
      </DialogContent>
    </Dialog>
  );
}

function valuesOf({ code, name, host, port, codePage, active }: Printer): PrinterFormValues {
  return { code, name, host, port: String(port), codePage, active, preset: '' };
}

function ActiveField() {
  const form = useFormContext<PrinterFormValues>();
  return (
    <FormField
      control={form.control}
      name="active"
      render={({ field }) => (
        <FormItem className="flex items-center gap-2 self-end pb-2">
          <FormControl>
            <input
              type="checkbox"
              checked={field.value}
              onChange={(event) => field.onChange(event.target.checked)}
              onBlur={field.onBlur}
              ref={field.ref}
              className="size-5 accent-primary"
            />
          </FormControl>
          <FormLabel>Ativa</FormLabel>
        </FormItem>
      )}
    />
  );
}
