import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@restaurantpro/ui';
import { ChevronDown, LogOut, UserRound } from 'lucide-react';
import { useLogout } from '../hooks/use-logout';
import { useSession } from '../hooks/use-session';
import { LogoutFailed } from './logout-failed';

/** The signed-in user and "Terminar sessão" (story 10). */
export function UserMenu() {
  const { data: session } = useSession();
  const logout = useLogout();
  const name = session?.name ?? '';
  return (
    <div className="flex items-center gap-2">
      {logout.isError && <LogoutFailed />}
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="lg" aria-label={`Menu do utilizador: ${name}`} disabled={logout.isPending}>
            <UserRound aria-hidden="true" className="size-5" />
            <span className="max-w-48 truncate">{name}</span>
            <ChevronDown aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-48">
          <DropdownMenuLabel>{name}</DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => logout.mutate()}>
            <LogOut aria-hidden="true" />
            Terminar sessão
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
