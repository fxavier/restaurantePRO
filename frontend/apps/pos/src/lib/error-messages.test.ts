import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { errorMessage, isVersionConflict } from './error-messages';

const conflict = (code: string) => new ApiError({ status: 409, code, title: 'Conflict' });

describe('coded 409s (FE-F29)', () => {
  it.each([
    ['CASH_SESSION_ALREADY_OPEN', 'Esta caixa, ou este operador, já tem uma sessão de caixa aberta.'],
    ['ILLEGAL_CASH_STATE', 'A caixa não permite esta operação no estado atual. Atualize e tente de novo.'],
    ['RECEIPT_ALREADY_RECORDED', 'Este recebimento já está a ser registado. Tente novamente dentro de instantes.'],
    ['SALE_DRAFT_IN_PROGRESS', 'O documento de venda já está a ser criado. Tente novamente dentro de instantes.'],
    ['REPRINT_IN_PROGRESS', 'Outra reimpressão está em curso. Tente novamente.'],
    ['ILLEGAL_DOCUMENT_STATE', 'O documento não permite esta operação no estado atual.'],
  ])('%s is explained in Portuguese and is not a version conflict', (code, text) => {
    expect(errorMessage(conflict(code))).toBe(text);
    expect(isVersionConflict(conflict(code))).toBe(false);
  });

  it('an older server\'s uncoded 409 is still treated as a version conflict', () => {
    expect(isVersionConflict(conflict('HTTP_409'))).toBe(true);
  });
});
