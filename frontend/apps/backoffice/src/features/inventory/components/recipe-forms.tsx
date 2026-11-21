import { zodResolver } from '@hookform/resolvers/zod';
import { Button } from '@restaurantpro/ui';
import { Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useFormCommand } from '../hooks/use-form-command';
import { useCreateRecipe, useCreateRecipeVersion } from '../hooks/use-inventory-commands';
import { useStockItems } from '../hooks/use-inventory-reads';
import {
  recipeSchema,
  recipeVersionSchema,
  type RecipeValues,
  type RecipeVersionInput,
  type RecipeVersionValues,
} from '../schemas';
import type { RecipeRequest, RecipeVersionRequest } from '../types';
import { CommandForm, SelectField, TextField } from './command-form';

/** "Nova ficha técnica": a recipe by its name; its versions carry the ingredients. */
export function RecipeForm({ onDone }: { onDone: () => void }) {
  const form = useForm<RecipeValues>({ resolver: zodResolver(recipeSchema), defaultValues: { name: '' } });
  const mutation = useCreateRecipe();
  const command = useFormCommand({
    form,
    fields: ['name'],
    mutation,
    toVariables: (intent: { body: RecipeRequest; idempotencyKey: string }) => intent,
    onDone,
  });
  return (
    <CommandForm form={form} onSubmit={command.send} command={command} pending={mutation.isPending} submitLabel="Criar ficha técnica" onCancel={onDone}>
      <TextField control={form.control} name="name" label="Nome" maxLength={160} />
    </CommandForm>
  );
}

const EMPTY_INGREDIENT = { stockItemId: '', quantity: '', unit: '' };

/** "Nova versão" (D115): a draft with its yield and ingredients; it applies only once activated. */
export function RecipeVersionForm({ recipeId, onDone }: { recipeId: string; onDone: () => void }) {
  const items = useStockItems();
  const form = useForm<RecipeVersionInput, unknown, RecipeVersionValues>({
    resolver: zodResolver(recipeVersionSchema),
    defaultValues: { yieldQuantity: '', yieldUnit: '', ingredients: [EMPTY_INGREDIENT] },
  });
  const ingredients = useFieldArray({ control: form.control, name: 'ingredients' });
  const mutation = useCreateRecipeVersion();
  const command = useFormCommand({
    form,
    fields: ['yieldQuantity', 'yieldUnit', 'ingredients'],
    mutation,
    toVariables: (intent: { body: RecipeVersionRequest; idempotencyKey: string }) => ({ ...intent, recipeId }),
    onDone,
  });
  const submit = (values: RecipeVersionValues) =>
    command.send({ ...values, ingredients: values.ingredients.map((line) => ({ ...line, kind: 'INGREDIENT' as const })) });
  const options = (items.data ?? []).map((item) => ({ value: item.id!, label: item.name! }));

  return (
    <CommandForm form={form} onSubmit={submit} command={command} pending={mutation.isPending} submitLabel="Criar versão" onCancel={onDone}>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField control={form.control} name="yieldQuantity" label="Rendimento" inputMode="decimal" />
        <TextField control={form.control} name="yieldUnit" label="Unidade do rendimento" maxLength={10} />
      </div>
      <fieldset className="flex flex-col gap-3">
        <legend className="mb-2 text-sm font-medium">Ingredientes</legend>
        {ingredients.fields.map((line, index) => (
          <div key={line.id} className="grid items-start gap-2 sm:grid-cols-12">
            <div className="sm:col-span-6">
              <SelectField control={form.control} name={`ingredients.${index}.stockItemId`} label={`Ingrediente ${index + 1}`} options={options} />
            </div>
            <div className="sm:col-span-3">
              <TextField control={form.control} name={`ingredients.${index}.quantity`} label={`Quantidade ${index + 1}`} inputMode="decimal" />
            </div>
            <div className="sm:col-span-2">
              <TextField control={form.control} name={`ingredients.${index}.unit`} label={`Unidade ${index + 1}`} maxLength={10} />
            </div>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="self-end"
              aria-label={`Retirar ingrediente ${index + 1}`}
              disabled={ingredients.fields.length === 1}
              onClick={() => ingredients.remove(index)}
            >
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        ))}
        <Button type="button" variant="outline" className="w-fit" onClick={() => ingredients.append(EMPTY_INGREDIENT)}>
          <Plus aria-hidden="true" />
          Acrescentar ingrediente
        </Button>
      </fieldset>
    </CommandForm>
  );
}
