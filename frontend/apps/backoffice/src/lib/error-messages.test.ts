import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { errorMessage } from './error-messages';

const conflict = (code: string) => new ApiError({ status: 409, code, title: 'Conflict' });

describe('coded 409s the BackOffice can meet (FE-F32)', () => {
  it.each([
    ['TERMINAL_CODE_TAKEN', 'Já existe um terminal com este código nesta loja.'],
    ['STORE_CODE_TAKEN', 'Já existe uma loja com este código.'],
    ['REGISTER_CODE_TAKEN', 'Já existe uma caixa com este código nesta loja.'],
    ['CATALOG_CODE_TAKEN', 'Este código já está a ser utilizado no catálogo.'],
    ['DINING_CODE_TAKEN', 'Já existe uma área ou mesa com este código nesta loja.'],
    ['EMPLOYEE_CODE_TAKEN', 'Já existe um empregado com este código.'],
    ['EMPLOYEE_ACCOUNT_TAKEN', 'Este empregado já tem uma conta.'],
    ['USERNAME_TAKEN', 'Este nome de utilizador já está a ser utilizado.'],
    ['ROLE_NAME_TAKEN', 'Já existe uma função com este nome.'],
    ['ROLE_ALREADY_ASSIGNED', 'O empregado já tem esta função nesta loja.'],
    ['SERIES_CODE_TAKEN', 'A loja já tem uma série com este código.'],
    ['ACTIVE_SERIES_EXISTS', 'A loja já tem uma série de venda ativa. Desative-a primeiro.'],
    ['ILLEGAL_TERMINAL_STATE', 'O terminal está revogado e não aceita esta ação.'],
    ['ILLEGAL_CASH_STATE', 'A caixa não permite esta operação no estado atual. Atualize e tente de novo.'],
    ['ILLEGAL_RELEASE_STATE', 'A publicação não permite esta operação no estado atual. Atualize e tente de novo.'],
    ['ILLEGAL_DOCUMENT_STATE', 'O documento não permite esta operação no estado atual.'],
    ['ILLEGAL_SERIES_STATE', 'A série não permite esta operação no estado atual. Atualize e tente de novo.'],
    ['REPRINT_IN_PROGRESS', 'Outra reimpressão está em curso. Tente novamente.'],
    ['VERSION_CONFLICT', 'Foi alterado por outra pessoa. Recarregue e tente de novo.'],
    ['IDEMPOTENCY_IN_PROGRESS', 'O pedido anterior ainda está a ser processado. Tente novamente dentro de instantes.'],
  ])('%s is explained in Portuguese', (code, text) => {
    expect(errorMessage(conflict(code))).toBe(text);
  });

  it.each(['HTTP_409', 'SOME_FUTURE_CONFLICT'])(
    'an unknown 409 (%s) gets a Portuguese generic, never the English title',
    (code) => {
      expect(errorMessage(conflict(code))).toBe(
        'Os dados foram alterados entretanto. Atualize e tente de novo.',
      );
    },
  );

  it('other unknown 4xx still show the server title', () => {
    expect(errorMessage(new ApiError({ status: 422, code: 'X', title: 'Dados inválidos' }))).toBe('Dados inválidos');
  });
});

describe("a feature's own messages", () => {
  const messages = {
    DELIVERY_ZONE_CODE_TAKEN: 'Já existe uma zona com este código nesta loja.',
    VERSION_CONFLICT: 'A zona foi alterada por outra pessoa. Recarregue e tente de novo.',
  };

  it('explain a 409 code the shared list does not know, instead of the generic', () => {
    expect(errorMessage(conflict('DELIVERY_ZONE_CODE_TAKEN'), { messages })).toBe(
      'Já existe uma zona com este código nesta loja.',
    );
  });

  it('win over the shared text, which every other code keeps', () => {
    expect(errorMessage(conflict('VERSION_CONFLICT'), { messages })).toBe(
      'A zona foi alterada por outra pessoa. Recarregue e tente de novo.',
    );
    expect(errorMessage(conflict('STORE_CODE_TAKEN'), { messages })).toBe('Já existe uma loja com este código.');
  });

  it('never hide "no connection"', () => {
    const down = new ApiError({ status: 502, code: 'VERSION_CONFLICT', title: 'Bad Gateway' });
    expect(errorMessage(down, { messages })).toBe('Sem ligação — a operação não foi confirmada.');
  });
});
