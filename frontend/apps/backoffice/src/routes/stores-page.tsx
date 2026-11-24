import { CompanyProfileCard, CompanyStores } from '../features/organization';

/** `/organization/stores` (§5, Stitch "Lojas, Terminais & Organização"): the company's profile and its stores. */
export function StoresPage() {
  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Organização
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Lojas</h1>
      <p className="mt-1 text-muted-foreground">A empresa e as suas lojas.</p>
      <CompanyProfileCard />
      <CompanyStores />
    </>
  );
}
