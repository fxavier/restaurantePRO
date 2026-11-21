import type { FeatureMessages } from '../../lib/error-messages';

/** Portuguese text for the identity codes the shared list (lib/error-messages) does not hold. */
export const peopleMessages: FeatureMessages = {
  SUPPORT_ACCOUNT_REQUIRED: 'Só uma conta de suporte pode receber um acesso de suporte.',
  SUPPORT_ACCOUNT_LINKED: 'Uma conta de suporte não pode ficar ligada a um empregado.',
  SUPPORT_GRANT_ACTIVE: 'Esta conta já tem um acesso de suporte ativo.',
  SUPPORT_GRANT_ENDED: 'Este acesso de suporte já terminou.',
  SUPPORT_GRANT_DURATION: 'Um acesso de suporte dura entre 1 minuto e 24 horas.',
  // use-people-commands MAYBE_HIRED: a taken code after a hire that got no answer
  EMPLOYEE_MAYBE_HIRED: 'O primeiro pedido pode já ter criado este empregado. Veja a lista antes de tentar de novo.',
};
