import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@restaurantpro/ui';

/** Stands in until the access shell (issue 06) gives the BackOffice its first real screen. */
export function PlaceholderPage() {
  return (
    <main className="mx-auto max-w-7xl p-6">
      <h1 className="text-2xl font-semibold">restaurantPRO — BackOffice</h1>
      <Card className="mt-6 max-w-xl">
        <CardHeader>
          <CardTitle>Em preparação</CardTitle>
          <CardDescription>Área de gestão</CardDescription>
        </CardHeader>
        <CardContent>
          <p>A gestão da empresa chega em breve.</p>
        </CardContent>
      </Card>
    </main>
  );
}
