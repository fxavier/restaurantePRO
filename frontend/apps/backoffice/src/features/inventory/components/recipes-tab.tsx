import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { CheckCircle2, CookingPot, Plus } from 'lucide-react';
import { useState } from 'react';
import { formatQuantity } from '../format';
import { useActivateRecipeVersion } from '../hooks/use-inventory-commands';
import { useItemLookup, useRecipes } from '../hooks/use-inventory-reads';
import { usePermission } from '../hooks/use-permission';
import { useRowCommand } from '../hooks/use-row-command';
import type { RecipeVersionView, RecipeView } from '../types';
import { CommandError } from './command-error';
import { ConfirmDialog, FormDialog, type Confirmation } from './form-dialog';
import { QueryState } from './query-state';
import { ScrollTable } from './scroll-table';
import { RecipeForm, RecipeVersionForm } from './recipe-forms';
import { StateBadge, versionStates } from './state-badge';

/** "Fichas técnicas & Receitas" (§5 recipes, D115): the recipes, one at a time with its versions and ingredients. */
export function RecipesTab({ storeId }: { storeId: string }) {
  const recipes = useRecipes();
  const canManage = usePermission('RECIPE_MANAGE', storeId);
  const [chosen, setChosen] = useState<string>();
  const rows = recipes.data?.rows;
  const recipe = rows?.find((candidate) => candidate.id === chosen) ?? rows?.[0];

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      <Card className="min-w-0 gap-4">
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-lg">Fichas técnicas</CardTitle>
          {canManage && <NewRecipeButton />}
        </CardHeader>
        <CardContent>
          <QueryState query={recipes} pending={recipes.isPending} empty={!rows?.length} emptyMessage="Ainda não há fichas técnicas.">
            <RecipeList recipes={rows ?? []} current={recipe?.id} onChoose={setChosen} />
          </QueryState>
        </CardContent>
      </Card>
      {recipe && <RecipeDetail key={recipe.id} recipe={recipe} canManage={canManage} />}
    </div>
  );
}

function NewRecipeButton() {
  return (
    <FormDialog
      trigger={
        <>
          <Plus aria-hidden="true" />
          Nova ficha técnica
        </>
      }
      triggerVariant="outline"
      title="Nova ficha técnica"
      description="Dê-lhe um nome; os ingredientes entram numa versão."
    >
      {(close) => <RecipeForm onDone={close} />}
    </FormDialog>
  );
}

function RecipeList({ recipes, current, onChoose }: { recipes: RecipeView[]; current?: string; onChoose: (id?: string) => void }) {
  return (
    <ul aria-label="Fichas técnicas" className="flex flex-col gap-1">
      {recipes.map((candidate) => (
        <li key={candidate.id}>
          {/* The chosen one as the POS's chosen register: the green border on the free state's tint. */}
          <Button
            variant="outline"
            aria-current={candidate.id === current}
            className="h-auto min-h-12 w-full justify-start text-left whitespace-normal aria-[current=true]:border-primary aria-[current=true]:bg-success-tint"
            onClick={() => onChoose(candidate.id)}
          >
            <CookingPot aria-hidden="true" />
            {candidate.name}
          </Button>
        </li>
      ))}
    </ul>
  );
}

function RecipeDetail({ recipe, canManage }: { recipe: RecipeView; canManage: boolean }) {
  const { itemName } = useItemLookup();
  const activate = useRowCommand(useActivateRecipeVersion());
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const versions = recipe.versions ?? [];
  const active = versions.find((version) => version.status === 'ACTIVE');

  return (
    <Card className="min-w-0 gap-4 lg:col-span-2">
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-2">
        <CardTitle>
          <h2 className="text-xl font-semibold">Ficha técnica: {recipe.name}</h2>
        </CardTitle>
        {canManage && (
          <FormDialog
            trigger={
              <>
                <Plus aria-hidden="true" />
                Nova versão
              </>
            }
            title={`Nova versão de ${recipe.name}`}
            description="Fica em rascunho; as vendas só consomem a versão ativa."
          >
            {(close) => <RecipeVersionForm recipeId={recipe.id!} onDone={close} />}
          </FormDialog>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <CommandError error={activate.error} pending={activate.pending} onRetry={activate.retry} />
        <VersionsTable
          versions={versions}
          canManage={canManage}
          pending={activate.pending}
          onActivate={(versionNo) =>
            setConfirmation({
              title: `Ativar a versão ${versionNo} de ${recipe.name}?`,
              description: 'Passa a ser a versão que as vendas consomem; a versão ativa atual fica substituída.',
              action: 'Ativar versão',
              onConfirm: () => activate.run({ recipeId: recipe.id!, versionNo }),
            })
          }
        />
        <ActiveIngredients ingredients={active?.ingredients} itemName={itemName} />
      </CardContent>
      <ConfirmDialog confirmation={confirmation} onClose={() => setConfirmation(null)} />
    </Card>
  );
}

type VersionsTableProps = { versions: RecipeVersionView[]; canManage: boolean; pending: boolean; onActivate: (versionNo: number) => void };

function VersionsTable({ versions, canManage, pending, onActivate }: VersionsTableProps) {
  return (
    <ScrollTable label="Versões da ficha técnica">
      <TableHeader>
        <TableRow>
          <TableHead>Versão</TableHead>
          <TableHead>Rendimento</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead>
            <span className="sr-only">Ações</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {versions.map((version) => (
          <TableRow key={version.id}>
            <TableCell className="font-mono">v{version.versionNo}</TableCell>
            <TableCell className="tabular-nums">{formatQuantity(version.yieldQuantity, version.yieldUnit)}</TableCell>
            <TableCell>
              <StateBadge status={version.status} states={versionStates} />
            </TableCell>
            <TableCell>
              {canManage && version.status === 'DRAFT' && (
                <div className="flex justify-end">
                  <Button size="sm" aria-label={`Ativar v${version.versionNo}`} disabled={pending} onClick={() => onActivate(version.versionNo!)}>
                    <CheckCircle2 aria-hidden="true" />
                    Ativar
                  </Button>
                </div>
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </ScrollTable>
  );
}

type Ingredient = NonNullable<RecipeVersionView['ingredients']>[number];

/** What the active version consumes per yield, by stock item name; no cost (the API exposes none). */
function ActiveIngredients({ ingredients, itemName }: { ingredients: Ingredient[] | undefined; itemName: (id?: string) => string }) {
  return (
    <section aria-labelledby="active-ingredients" className="flex flex-col gap-2">
      <h3 id="active-ingredients" className="text-lg font-semibold">
        Composição da versão ativa
      </h3>
      {ingredients?.length ? (
        <ScrollTable label="Ingredientes da versão ativa">
          <TableHeader>
            <TableRow>
              <TableHead>Artigo de stock</TableHead>
              <TableHead className="text-right">Quantidade</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {ingredients.map((ingredient) => (
              <TableRow key={ingredient.stockItemId}>
                <TableCell>{itemName(ingredient.stockItemId)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatQuantity(ingredient.quantity, ingredient.unit)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </ScrollTable>
      ) : (
        <p className="text-muted-foreground">Esta ficha técnica ainda não tem uma versão ativa.</p>
      )}
    </section>
  );
}
