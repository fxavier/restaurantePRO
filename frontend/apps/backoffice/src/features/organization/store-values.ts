/** `StoreSummary.businessDayCutoff` is a local time (`HH:mm:ss`); the screen and the time field show `HH:mm`. */
export function cutoffLabel(cutoff: string): string {
  return cutoff.slice(0, 5);
}

/** The time field's `HH:mm` as the contract's local time; an empty field leaves the server's default. */
export function toCutoff(time: string): string | undefined {
  return time ? `${time}:00` : undefined;
}

/** An IANA timezone this browser knows, as the server checks it (`ZoneId.of`). */
export function isTimeZone(name: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone: name });
    return true;
  } catch {
    return false;
  }
}
