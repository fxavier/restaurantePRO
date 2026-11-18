import { Card, CardContent } from '@restaurantpro/ui';
import { PosScreen } from './pos-screen';

/** Stands in until the PIN login (issue 09). */
export function LoginPage() {
  return (
    <PosScreen>
      <Card className="w-full max-w-md">
        <CardContent className="flex flex-col gap-1">
          <p className="text-lg font-semibold">Terminal registado.</p>
          <p className="text-muted-foreground">O início de sessão com PIN chega no próximo passo.</p>
        </CardContent>
      </Card>
    </PosScreen>
  );
}
