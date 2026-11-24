import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { useCatalogTab } from '../hooks/use-catalog-tab';
import { FamiliesTab } from './families-tab';
import { ItemsTab } from './items-tab';
import { PricesTab } from './prices-tab';
import { ReleasesTab } from './releases-tab';

/**
 * The light reference's area tabs (as the POS section bar and the BackOffice areas): a white card of white bordered
 * 48 px pills, the shown one solid green. The shared Tabs keep their behaviour; only their look is set here.
 */
const TAB_LIST = 'w-fit max-w-full flex-wrap justify-start gap-2 rounded-2xl border bg-card p-2 group-data-[orientation=horizontal]/tabs:h-auto';
const TAB =
  'h-12 flex-none rounded-xl border-border bg-card px-5 text-foreground hover:bg-muted data-[state=active]:border-primary data-[state=active]:font-semibold data-[state=active]:hover:bg-primary-hover group-data-[variant=default]/tabs-list:data-[state=active]:shadow-none';

/**
 * Stitch "Catálogo & Versões": one page, its sections as tabs kept in `?tab`. Items and families are the company's;
 * prices and releases belong to the store chosen in the top bar (`storeId`).
 */
export function CatalogScreen({ storeId }: { storeId: string | undefined }) {
  const { tab, selectTab } = useCatalogTab();
  return (
    <Tabs value={tab} onValueChange={selectTab} className="mt-6 gap-4">
      <TabsList className={TAB_LIST}>
        <TabsTrigger value="artigos" className={TAB}>
          Artigos
        </TabsTrigger>
        <TabsTrigger value="familias" className={TAB}>
          Famílias
        </TabsTrigger>
        <TabsTrigger value="precos" className={TAB}>
          Preços
        </TabsTrigger>
        <TabsTrigger value="versoes" className={TAB}>
          Versões
        </TabsTrigger>
      </TabsList>
      <TabsContent value="artigos">
        <ItemsTab />
      </TabsContent>
      <TabsContent value="familias">
        <FamiliesTab />
      </TabsContent>
      <TabsContent value="precos">
        <PricesTab storeId={storeId} />
      </TabsContent>
      <TabsContent value="versoes">
        <ReleasesTab storeId={storeId} />
      </TabsContent>
    </Tabs>
  );
}
