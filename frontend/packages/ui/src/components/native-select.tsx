import * as React from "react"
import { cn } from "../lib/utils"

/**
 * A native select in the field look of `Input` (light reference: `rounded-xl` on `bg-card`, `border-input`, the focus
 * ring, the disabled and invalid states): the platform's keyboard and screen-reader behaviour, for a picker of a few
 * options with no search. Full width by default; a caller narrows it through `className`.
 */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    <select
      data-slot="native-select"
      className={cn(
        "h-9 w-full min-w-0 rounded-xl border border-input bg-card px-3 text-sm text-foreground shadow-xs transition-[color,box-shadow] outline-none disabled:cursor-not-allowed disabled:opacity-50 dark:bg-muted",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
}

export { NativeSelect }
