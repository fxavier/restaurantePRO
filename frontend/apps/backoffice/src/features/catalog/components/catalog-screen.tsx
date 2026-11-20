import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { useCatalogTab } from '../hooks/use-catalog-tab';
import { FamiliesTab } from './families-tab';
import { ItemsTab } from './items-tab';

/** Stitch "Catálogo & Versões": one page, its sections as tabs kept in `?tab`. */
export function CatalogScreen() {
  const { tab, selectTab } = useCatalogTab();
  return (
    <Tabs value={tab} onValueChange={selectTab} className="mt-6 gap-4">
      <TabsList className="w-full max-w-xl">
        <TabsTrigger value="artigos">Artigos</TabsTrigger>
        <TabsTrigger value="familias">Famílias</TabsTrigger>
      </TabsList>
      <TabsContent value="artigos">
        <ItemsTab />
      </TabsContent>
      <TabsContent value="familias">
        <FamiliesTab />
      </TabsContent>
    </Tabs>
  );
}
