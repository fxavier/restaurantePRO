import { Button, Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { Minus, Plus } from 'lucide-react';
import { useState } from 'react';
import { useSplitByItems, useSplitEqually } from '../hooks/use-settlement-commands';
import type { Settlement } from '../settlement';
import { CommandStatus } from './command-status';
import { ItemsSplit } from './items-split';

type Props = {
  settlement: Settlement;
  /** The order's guests, the equal split's first number of people. */
  guests: number | null | undefined;
  /** "Sem ligação" or a closed order: no command. */
  disabled: boolean;
  /** The mode shown first: the whole bill, or equal parts when the bill was opened to split it. */
  mode?: 'whole' | 'equal';
};

const MIN_PEOPLE = 2;
const MAX_PEOPLE = 50;

/** Stitch's "Modo de repartição": the whole bill, equal parts, or by items (§4 "divisão por artigos ou partes"). */
export function SplitPanel({ settlement, guests, disabled, mode = 'whole' }: Props) {
  const split = useSplitEqually(settlement.orderId);
  // Each split by items the server made starts the quantities again; a refused one keeps them.
  const [itemsSplits, setItemsSplits] = useState(0);
  const byItems = useSplitByItems(settlement.orderId, () => setItemsSplits((count) => count + 1));
  // Until the user picks a number, the order's guests (which may arrive after the bill).
  const [chosen, setPeople] = useState<number>();
  const people = chosen ?? Math.min(Math.max(guests ?? MIN_PEOPLE, MIN_PEOPLE), MAX_PEOPLE);

  return (
    <section aria-labelledby="split-title" className="flex flex-col gap-3 rounded-2xl border bg-card p-4">
      <h3 id="split-title" className="text-base font-semibold">
        Divisão da conta
      </h3>
      <Tabs defaultValue={mode}>
        {/* The area tabs (the shared pill variant), here as three equal columns. */}
        <TabsList variant="pill" className="grid w-full grid-cols-3">
          <TabsTrigger value="whole" className="whitespace-normal group-data-[variant=pill]/tabs-list:text-base">
            Conta total
          </TabsTrigger>
          <TabsTrigger value="equal" className="whitespace-normal group-data-[variant=pill]/tabs-list:text-base">
            Partes iguais
          </TabsTrigger>
          <TabsTrigger value="items" className="whitespace-normal group-data-[variant=pill]/tabs-list:text-base">
            Por artigos
          </TabsTrigger>
        </TabsList>
        <TabsContent value="whole" className="pt-2 text-muted-foreground">
          A conta é paga por inteiro.
        </TabsContent>
        <TabsContent value="equal" className="flex flex-col gap-3 pt-2">
          <div role="group" aria-label="Pessoas" className="flex items-center justify-between gap-3 rounded-xl border p-2">
            <span className="px-2">Pessoas</span>
            <span className="flex items-center gap-2">
              <Button
                variant="outline"
                size="touch-round"
                aria-label="Menos uma pessoa"
                disabled={people <= MIN_PEOPLE}
                onClick={() => setPeople(people - 1)}
              >
                <Minus aria-hidden="true" />
              </Button>
              <output className="w-10 text-center text-xl font-bold tabular-nums">{people}</output>
              <Button
                variant="outline"
                size="touch-round"
                aria-label="Mais uma pessoa"
                disabled={people >= MAX_PEOPLE}
                onClick={() => setPeople(people + 1)}
              >
                <Plus aria-hidden="true" />
              </Button>
            </span>
          </div>
          <Button
            size="touch"
            disabled={disabled || split.intent !== undefined}
            onClick={() => split.start({ orderVersion: settlement.orderVersion, parts: people }, undefined)}
          >
            Dividir em {people} partes
          </Button>
        </TabsContent>
        <TabsContent value="items" className="pt-2">
          <ItemsSplit
            key={itemsSplits}
            settlement={settlement}
            disabled={disabled || byItems.intent !== undefined}
            onSplit={(lines) => byItems.start({ orderVersion: settlement.orderVersion, lines }, undefined)}
          />
        </TabsContent>
      </Tabs>
      {/* One line for what the last split did, so the panel never jumps when it changes. */}
      <div className="min-h-12">
        {[split, byItems].map((command, index) => (
          <CommandStatus
            key={index}
            command={command}
            pendingText="A dividir a conta…"
            unconfirmedText="Sem ligação — a divisão não foi confirmada."
            refusedText="A divisão não foi feita."
            blocked={disabled}
          />
        ))}
      </div>
    </section>
  );
}
