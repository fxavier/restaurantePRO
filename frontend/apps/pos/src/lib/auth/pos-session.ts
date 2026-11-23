import type { components } from '@restaurantpro/api-client';

type SessionView = components['schemas']['com.xavier.backend.identity.model.dto.SessionView'];

/** The logged-in employee on this terminal, as the PIN login answered (ADR 0008, D182). */
export type Employee = {
  employeeId: string;
  storeId: string;
  name: string;
  /** A manager reset the PIN: only the PIN change is allowed until a new one is chosen. */
  mustChangePin: boolean;
  /** `PERM_X@storeId`; for showing controls only, the backend enforces. */
  permissions: string[];
  /** The session's store, which the status bar names. */
  storeName: string;
  /** The store's IANA zone: every time is shown in it, not in the terminal's (FC §12). */
  storeTimeZone: string;
  /** The session's terminal ("POS-02") and its name, which the status bar names; absent from an older backend. */
  terminalCode?: string;
  terminalName?: string;
};

/** Why the last session ended, shown once on the PIN pad; a switch says nothing. */
export type EndReason = 'idle' | 'invalid' | 'switch';

export type PosSession = {
  /** The token for `X-Pos-Session`, read by the POS middleware on every call. */
  token(): string | undefined;
  employee(): Employee | undefined;
  endedBy(): EndReason | undefined;
  /** From the PIN login's answer, which carries the token. */
  start(view: SessionView): void;
  /**
   * From the PIN change's answer, which keeps the token; only while `sent`, the token the request carried, is still
   * the current one, so a late answer for an ended session never changes the next employee's.
   */
  update(view: SessionView, sent: string | undefined): void;
  end(reason: EndReason): void;
  subscribe(listener: () => void): () => void;
};

/**
 * The POS session (FC §5, §6): memory only, never storage, a URL or a query key; reloading the page logs out. A
 * terminal has one at a time.
 */
export function createPosSession(): PosSession {
  const listeners = new Set<() => void>();
  let token: string | undefined;
  let employee: Employee | undefined;
  let endedBy: EndReason | undefined;

  function changed() {
    for (const listener of listeners) listener();
  }

  return {
    token: () => token,
    employee: () => employee,
    endedBy: () => endedBy,
    start(view) {
      if (!view.posSession) throw new Error('Incomplete login answer');
      token = view.posSession;
      employee = toEmployee(view);
      endedBy = undefined;
      changed();
    },
    update(view, sent) {
      if (!token || sent !== token) return;
      employee = toEmployee(view);
      changed();
    },
    end(reason) {
      if (!token) return;
      token = undefined;
      employee = undefined;
      endedBy = reason;
      changed();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/** SessionView also describes a BackOffice account, so these are nullable; a login answer without them cannot be used. */
function toEmployee(view: SessionView): Employee {
  if (!view.employeeId || !view.storeId || view.name == null || view.storeName == null || !view.storeTimezone) {
    throw new Error('Incomplete login answer');
  }
  return {
    employeeId: view.employeeId,
    storeId: view.storeId,
    name: view.name,
    mustChangePin: view.mustChangePin,
    permissions: view.permissions,
    storeName: view.storeName,
    storeTimeZone: view.storeTimezone,
    terminalCode: view.terminalCode ?? undefined,
    terminalName: view.terminalName ?? undefined,
  };
}
