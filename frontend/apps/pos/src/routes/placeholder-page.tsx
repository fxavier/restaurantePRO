import { Card, CardContent, CardHeader } from '@restaurantpro/ui';

/** Stands in until enrolment (issue 08) gives the POS its first real screen. */
export function PlaceholderPage() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-muted p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <h1 className="text-xl font-semibold">restaurantPRO — Ponto de venda</h1>
        </CardHeader>
        <CardContent>
          <p className="text-base text-muted-foreground">Este terminal ainda não está registado.</p>
        </CardContent>
      </Card>
    </main>
  );
}
