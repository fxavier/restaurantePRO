import { EmployeesTab } from './employees-tab';

/** Stitch "Utilizadores, Empregados & RBAC": the page header, then one tab per kind of identity. */
export function PeopleScreen() {
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Identidade & Acessos
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Utilizadores, Empregados & Funções</h1>
      <p className="mt-1 text-muted-foreground">
        Os empregados e os seus PIN, as funções e as suas permissões por loja, e as contas do BackOffice.
      </p>
      <div className="mt-6">
        <EmployeesTab />
      </div>
    </>
  );
}
