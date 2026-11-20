import { CatalogScreen } from '../features/catalog';
import { useStoreSelection } from '../features/organization';

/** `/catalog?tab=…&storeId=…` (§5, Stitch "Catálogo & Versões"): items, families, prices and the store's releases. */
export function CatalogPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      {/* Stitch's page header: the area as an eyebrow, then the title and what the page holds. */}
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Catálogo
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Catálogo de artigos e versões</h1>
      <p className="mt-1 text-muted-foreground">
        Os artigos, as famílias e os preços, e as versões publicadas para os terminais POS de cada loja.
      </p>
      <CatalogScreen storeId={storeId} />
    </>
  );
}
