import { zodResolver } from '@hookform/resolvers/zod';
import { Button, ConfirmButton, Form, FormControl, FormField, FormItem, FormLabel, FormMessage, Input } from '@restaurantpro/ui';
import { Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useReplaceRoutes } from '../hooks/use-routing';
import type { Printer } from '../parse';
import { routesSchema, type RoutesFormValues } from '../schemas';
import type { DestinationRouteView } from '../types';
import { NativeSelect } from './native-select';
import { ProblemAlert } from './problem-alert';

type RoutesEditorProps = { storeId: string; routes: DestinationRouteView[]; printers: Printer[] };

/**
 * Stitch's "Matriz" as the API has it: each production destination of the catalog goes to one printer of the store
 * (CONTEXT: DestinationRoute; a line with no route is not printed). The table is edited, then replaced whole. A
 * reread (after a save or a conflict) keeps the rows being edited.
 */
export function RoutesEditor({ storeId, routes, printers }: RoutesEditorProps) {
  const form = useForm<RoutesFormValues>({
    resolver: zodResolver(routesSchema),
    values: { routes },
    resetOptions: { keepDirtyValues: true },
  });
  const rows = useFieldArray({ control: form.control, name: 'routes' });
  const replace = useReplaceRoutes(storeId);
  const save = form.handleSubmit((values) => replace.mutate(values.routes));

  return (
    <Form {...form}>
      <form aria-label="Rotas por destino" noValidate onSubmit={(event) => void save(event)} className="flex flex-col gap-3">
        {rows.fields.length === 0 && <p className="text-sm text-muted-foreground">Nenhum destino encaminhado: nada é impresso.</p>}
        <ol className="flex flex-col gap-2">
          {rows.fields.map((row, index) => (
            <li key={row.id} className="grid grid-cols-12 items-start gap-2">
              <FormField
                control={form.control}
                name={`routes.${index}.destinationCode`}
                render={({ field }) => (
                  <FormItem className="col-span-12 sm:col-span-5">
                    <FormLabel className="sr-only">Destino {index + 1}</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" maxLength={40} placeholder="COZINHA" className="font-mono uppercase" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name={`routes.${index}.printerId`}
                render={({ field }) => (
                  <FormItem className="col-span-10 sm:col-span-6">
                    <FormLabel className="sr-only">Impressora do destino {index + 1}</FormLabel>
                    <FormControl>
                      <NativeSelect {...field}>
                        <option value="">Escolher impressora</option>
                        {printers.map((printer) => (
                          <option key={printer.id} value={printer.id}>
                            {printer.name}
                          </option>
                        ))}
                      </NativeSelect>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Remover destino ${index + 1}`}
                onClick={() => rows.remove(index)}
                className="col-span-2 sm:col-span-1"
              >
                <Trash2 aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ol>
        {replace.error ? <ProblemAlert error={replace.error} pending={replace.isPending} onRetry={() => void save()} /> : null}
        <p role="status" className="text-sm empty:hidden">
          {replace.isSuccess && 'Rotas guardadas.'}
        </p>
        <div className="flex flex-wrap justify-between gap-2">
          <Button type="button" variant="outline" onClick={() => rows.append({ destinationCode: '', printerId: '' })}>
            <Plus aria-hidden="true" />
            Adicionar destino
          </Button>
          <ConfirmButton type="submit" pending={replace.isPending}>
            Guardar rotas
          </ConfirmButton>
        </div>
      </form>
    </Form>
  );
}
