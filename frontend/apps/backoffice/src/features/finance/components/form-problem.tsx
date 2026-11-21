/** A form's server problem that belongs to no field (the root error), announced. */
export function FormProblem({ message }: { message: string | undefined }) {
  if (!message) return null;
  return (
    <p role="alert" className="text-sm text-error">
      {message}
    </p>
  );
}
