import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section aria-labelledby="nao-encontrada" className="flex flex-col gap-2 rounded-2xl border bg-card p-6">
      <h1 id="nao-encontrada" className="text-2xl font-semibold">
        Página não encontrada
      </h1>
      <p>
        <Link to="/" className="text-primary underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-ring">
          Voltar à visão geral
        </Link>
      </p>
    </section>
  );
}
