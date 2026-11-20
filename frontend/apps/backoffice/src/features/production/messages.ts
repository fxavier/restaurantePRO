import type { FeatureMessages } from '../../lib/error-messages';

/** Portuguese text for the production module's codes (ProductionRule, its 409s); passed to `errorMessage`. */
export const productionMessages: FeatureMessages = {
  PRINTER_CODE_TAKEN: 'Já existe uma impressora com este código nesta loja.',
  PRINTER_REDIRECT_INVALID:
    'Não é possível redirecionar para essa impressora: tem de ser outra impressora da loja que não esteja a redirecionar.',
  PRINTER_NOT_IN_STORE: 'Uma das impressoras escolhidas não pertence a esta loja.',
  PRINTER_PROFILE_INVALID: 'As definições do perfil não são válidas para esta impressora.',
  PRINTER_IGNORED: 'A impressora está em modo Ignorar e não aceita trabalhos.',
  DUPLICATE_ROUTE: 'Cada destino e cada terminal só pode aparecer uma vez.',
  ROUTES_CHANGED_CONCURRENTLY: 'O encaminhamento foi alterado por outra pessoa. Reveja e guarde de novo.',
  TERMINAL_ASSIGNED_ELSEWHERE: 'Um destes terminais já tem impressora noutra loja.',
  ILLEGAL_PRINT_JOB_STATE: 'O trabalho mudou de estado entretanto. A fila foi atualizada.',
  PRINT_JOB_ALREADY_RESOLVED: 'Este trabalho já foi resolvido por outra pessoa. A fila foi atualizada.',
  KICK_NOT_REPEATABLE: 'Uma abertura de gaveta nunca é repetida.',
  STILL_UNROUTED: 'O talão ainda não tem rota: configure uma rota para o seu destino primeiro.',
  TICKET_ALREADY_ROUTED: 'Este talão já foi encaminhado.',
};
