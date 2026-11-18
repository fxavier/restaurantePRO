import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@restaurantpro/ui';
import { useSession } from '../features/identity';

/** `/`: a placeholder until the BackOffice has something to summarise. */
export function OverviewPage() {
  const { data: session } = useSession();
  return (
    <>
      <h1 className="text-2xl font-semibold">Visão geral</h1>
      <p className="mt-1 text-muted-foreground">Bem-vindo, {session?.name}.</p>
      <Card className="mt-6 max-w-xl">
        <CardHeader>
          <CardTitle>Por onde começar</CardTitle>
          <CardDescription>Escolha a loja na barra superior e uma área no menu lateral.</CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-sm">Os indicadores da empresa chegam numa próxima versão.</p>
        </CardContent>
      </Card>
    </>
  );
}
