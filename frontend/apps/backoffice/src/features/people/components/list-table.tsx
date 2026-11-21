import { Table, TableBody, TableCaption, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import type { ReactNode } from 'react';

type ListTableProps = {
  /** The table's accessible name. */
  caption: string;
  /** The visible column headers; a last, unnamed "Ações" column follows them. */
  headers: string[];
  children: ReactNode;
};

/** A tab's table: Stitch's uppercase headers, then a commands column that only screen readers name. */
export function ListTable({ caption, headers, children }: ListTableProps) {
  return (
    <Table>
      <TableCaption className="sr-only">{caption}</TableCaption>
      <TableHeader>
        <TableRow>
          {headers.map((header) => (
            <TableHead key={header}>{header}</TableHead>
          ))}
          <TableHead>
            <span className="sr-only">Ações</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>{children}</TableBody>
    </Table>
  );
}
