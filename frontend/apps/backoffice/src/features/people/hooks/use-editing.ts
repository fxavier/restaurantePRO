import { useState } from 'react';

/**
 * Which form a list tab has open: the new one, or the edit of one row. The edited row is looked up in the rows as the
 * list holds them now, so the refetch after a conflict reaches the open form.
 */
export function useEditing<Row extends { id?: string }>(rows: readonly Row[] | undefined) {
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  return {
    creating,
    startCreating: () => setCreating(true),
    stopCreating: () => setCreating(false),
    editing: rows?.find((row) => row.id === editingId),
    startEditing: (row: Row) => setEditingId(row.id ?? null),
    stopEditing: () => setEditingId(null),
  };
}
