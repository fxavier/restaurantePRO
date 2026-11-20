import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
} from '@restaurantpro/ui';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useFormContext } from 'react-hook-form';
import { useCreatePrinter, usePrinterPresets } from '../hooks/use-printer-commands';
import { createRequest } from '../printer-requests';
import type { PrinterFormValues } from '../schemas';
import type { PrinterPresetView } from '../types';
import { NativeSelect } from './native-select';
import { PrinterForm } from './printer-form';

const EMPTY: PrinterFormValues = { code: '', name: '', host: '', port: '9100', codePage: 'CP860', preset: '', active: true };

/** "Adicionar impressora": the form is mounted only while open, so each opening starts clean. */
export function AddPrinterDialog({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false);
  const presets = usePrinterPresets();
  const create = useCreatePrinter();
  const close = (next: boolean) => {
    setOpen(next);
    if (!next) create.reset();
  };
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogTrigger asChild>
        <Button>
          <Plus aria-hidden="true" />
          Adicionar impressora
        </Button>
      </DialogTrigger>
      <DialogContent showCloseButton={false} className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Adicionar impressora</DialogTitle>
          <DialogDescription>Uma impressora térmica da loja, ligada à rede local (ESC/POS, normalmente na porta 9100).</DialogDescription>
        </DialogHeader>
        <PrinterForm
          defaultValues={EMPTY}
          submitLabel="Adicionar"
          pending={create.isPending}
          error={create.error}
          send={(values) =>
            create.mutateAsync(createRequest(storeId, values, presets.data ?? [])).then(() => close(false))
          }
          extraFields={<PresetField presets={presets.data ?? []} />}
        >
          <Button type="button" variant="outline" onClick={() => close(false)}>
            Cancelar
          </Button>
        </PrinterForm>
      </DialogContent>
    </Dialog>
  );
}

/** The preset fills the code page; its paper and cut settings go with the printer. */
function PresetField({ presets }: { presets: PrinterPresetView[] }) {
  const form = useFormContext<PrinterFormValues>();
  return (
    <FormField
      control={form.control}
      name="preset"
      render={({ field }) => (
        <FormItem>
          <FormLabel>Perfil</FormLabel>
          <FormControl>
            <NativeSelect
              {...field}
              onChange={(event) => {
                field.onChange(event);
                const codePage = presets.find((preset) => preset.name === event.target.value)?.codePage;
                if (codePage) form.setValue('codePage', codePage);
              }}
            >
              <option value="">Padrão do sistema</option>
              {presets.map((preset) => (
                <option key={preset.name} value={preset.name}>
                  {presetLabel(preset)}
                </option>
              ))}
            </NativeSelect>
          </FormControl>
        </FormItem>
      )}
    />
  );
}

function presetLabel({ name, profile }: PrinterPresetView): string {
  return profile?.paperWidthMm ? `${name} (${profile.paperWidthMm} mm, ${profile.columns} colunas)` : (name ?? '');
}
