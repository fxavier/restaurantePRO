import { CompanyStores } from '../features/organization';

/** `/organization/stores` (§5, Stitch "Lojas, Terminais & Organização"): the company's profile and its stores. */
export function StoresPage() {
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Organização
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Lojas</h1>
      <p className="mt-1 text-muted-foreground">A empresa e as suas lojas.</p>
      <CompanyStores />
    </>
  );
}
