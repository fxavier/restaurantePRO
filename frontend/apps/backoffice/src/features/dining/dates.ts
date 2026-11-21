/** The calendar date (`YYYY-MM-DD`, the contract's `format: date`) at `instant` in the store's timezone (FC §12). */
export function dateIn(timeZone: string, instant: Date): string {
  // en-CA writes dates as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(instant);
}

/** An ISO instant's `HH:mm` in the store's timezone, 24-hour. */
export function timeIn(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-PT', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(iso));
}
