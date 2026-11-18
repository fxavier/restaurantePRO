/** The BFF session may still be alive: say so, rather than pretend the user is signed out. */
export function LogoutFailed() {
  return (
    <p role="alert" className="text-sm text-destructive">
      Não foi possível terminar a sessão. Tente novamente.
    </p>
  );
}
