# restaurantPRO — guia de uso local

Como pôr a aplicação a correr na sua máquina, carregar dados de demonstração e testar o POS e o BackOffice pela UI.
Tudo corre localmente e é descartável: nunca aponte esta stack a dados partilhados ou de produção.

Os comandos usam o prefixo `rtk` (ver `CLAUDE.md`). Corra-os a partir da raiz do repositório.

## 1. Pré-requisitos

- Docker com Compose v2, a correr. Confirme o espaço livre: `rtk docker system df`.
- `pnpm`, `jq`, `openssl` e `curl`.
- JDK 25 apenas se for correr os testes do backend fora da stack.
- Uma app de autenticação no telemóvel (Google Authenticator, Microsoft Authenticator, 1Password…), para o segundo
  fator do BackOffice. Em alternativa, use o comando da secção 4.

## 2. Iniciar a stack

```bash
rtk git pull                              # o main atual
rtk proxy deploy/e2e/stack.sh up          # compila as duas apps e o backend e arranca tudo (alguns minutos)
```

A stack inclui PostgreSQL, Keycloak, Mailpit, o backend, o BFF e as duas apps. Os segredos são gerados para cada
stack, ficam em `deploy/e2e/.run/restaurantpro-e2e/` (ignorado pelo git) e nunca são impressos no ecrã.

| Serviço | Endereço |
| --- | --- |
| POS | http://localhost:14173 |
| BackOffice | http://localhost:14174 |
| Keycloak | http://localhost:18180 |
| Backend | http://localhost:18080 |
| Mailpit (emails enviados) | http://localhost:18025 |

Para correr duas stacks lado a lado, dê a cada uma o seu `E2E_PROJECT` e as suas portas (`E2E_POS_PORT`,
`E2E_BACKOFFICE_PORT`, `E2E_KEYCLOAK_PORT`, `E2E_BACKEND_PORT`, `E2E_MAILPIT_PORT`) em todos os comandos dessa stack.
Os valores por omissão estão no cabeçalho de `deploy/e2e/stack.sh`.

## 3. Carregar os dados de demonstração

```bash
E2E_DEMO_SEED=1 rtk proxy deploy/e2e/stack.sh test --project=stack e2e/stack/demo-seed.spec.ts
```

A seed cria uma empresa nova com:

- os terminais Caixa 1 e Bar, um terminal usado pelo próprio script e uma ponte de impressão;
- as mesas Sala M1–M10, Esplanada E1–E6 e Bar B1–B4;
- um catálogo publicado com artigos em Entradas, Pratos, Bebidas, Sobremesas e Cafetaria, encaminhados para Cozinha,
  Bar e Copa;
- os empregados de sala JOAO e MARIA;
- algumas mesas abertas com rondas já enviadas.

As credenciais ficam em `deploy/e2e/.run/restaurantpro-e2e/manual-credentials.txt` (permissões 0600, ignorado pelo
git). Cada seed cria uma empresa nova e substitui esse ficheiro, por isso as credenciais antigas deixam de servir.

## 4. Entrar no BackOffice

1. Abra http://localhost:14174.
2. Introduza o email e a palavra-passe do ficheiro de credenciais.
3. O Keycloak pede um código de 6 dígitos. Gere-o **só nesse momento** e escreva-o logo:
   - **App de autenticação:** adicione uma conta com introdução manual da chave, tipo baseado na hora (TOTP), com o
     "TOTP secret" do ficheiro. A app mostra o código e o tempo que falta.
   - **Sem app**, a partir da raiz do repositório:

     ```bash
     rtk proxy python3 -c '
     import base64,hmac,hashlib,struct,time,re
     s=re.search(r"TOTP secret\s+(\S+)",open("deploy/e2e/.run/restaurantpro-e2e/manual-credentials.txt").read()).group(1)
     k=base64.b32decode(s+"="*(-len(s)%8)); t=int(time.time())//30
     h=hmac.new(k,struct.pack(">Q",t),hashlib.sha1).digest(); o=h[-1]&15
     print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%10**6), "- válido mais", 30-int(time.time())%30, "s")'
     ```

**Cuidados com o código:**

- O segredo não é o código: o Keycloak pede os 6 dígitos que o segredo gera.
- Este Keycloak só aceita o código dos 30 segundos em que é válido, sem margem. Se faltarem menos de 10 s, espere pelo
  código seguinte.
- O Keycloak não aceita o mesmo código duas vezes. Depois de uma falha, use o código do período seguinte.
- Ações sensíveis (emitir o código de um terminal, publicar o catálogo, conceder permissões sensíveis) pedem o
  segundo fator outra vez.

## 5. Entrar no POS

1. Abra http://localhost:14173.
2. Registe o terminal com um código de registo do ficheiro. Cada código só serve uma vez. Para um terminal novo, emita
   outro código em BackOffice › Organização › Terminais › "Emitir código".
3. Entre com um código de empregado e o PIN do ficheiro. O código de empregado não distingue maiúsculas de minúsculas.

Para simular um segundo POS, use outro perfil do navegador e outro código de registo.

## 6. O que a seed não cria

Antes de testar estes ecrãs, crie no BackOffice:

| O quê | Onde |
| --- | --- |
| Armazéns, artigos de stock, contagem de abertura, fichas técnicas, fornecedor, encomenda | Stocks |
| Uma segunda loja, para testar transferências | Organização › Lojas |
| Clientes com crédito, para a conta corrente | Clientes |
| Zonas e taxas de entrega | Delivery › Zonas |
| Um estafeta: um papel com "Fazer entregas como estafeta", atribuído a um empregado (Gerente e Caixa já o têm) | Pessoas › Funções |
| "Ver a produção" no papel de quem usa o KDS, se o `/kds` recusar | Pessoas › Funções |

## 7. O que testar

### POS — Sala & Mesas

- [ ] A barra de estado mostra loja · terminal · empregado e, se houver, o aviso de impressão pendente.
- [ ] As mesas têm cor por estado: livre a verde, ocupada a âmbar, conta pedida a azul, bloqueada a vermelho.
- [ ] Cada mesa ocupada mostra o valor da conta.
- [ ] Uma mesa ocupada há mais de 30 min sem ronda fica vermelha com "N min sem ronda" (conta desde a última ronda,
      ou desde a abertura se ainda não houve nenhuma).
- [ ] Ao tocar numa mesa, o pedido aparece à direita: rondas enviadas com "em preparação", "pronta" ou "servida", o
      rascunho com −/+ e o total.
- [ ] "Subtotal" imprime a conta prévia e a mesa passa a "Conta pedida".
- [ ] "Dividir" abre a conta já na divisão.
- [ ] "Enviar ronda N" envia o rascunho. "Adicionar artigos" abre o ecrã do pedido.
- [ ] O rodapé mostra os avisos de pagamentos por confirmar e de rondas atrasadas na cozinha, com ligação ao ecrã que
      os resolve.
- [ ] Atalhos: F2 nova reserva (com permissão de reservas), F3 novo takeaway, "/" procurar mesa.

### POS — restantes ecrãs

- [ ] **Pedido:** catálogo por família, pesquisa, notas e envio da ronda ("Pedir").
- [ ] **Pagamento:** conta total, partes iguais ou por artigos, meios de pagamento, troco e fecho.
- [ ] **Caixa:** abrir sessão, movimentos e fecho às cegas.
- [ ] **Reservas:** lista com cor por estado ("Por chegar" a verde, "Sentada" a âmbar), "Nova reserva" e sentar.
- [ ] **Delivery:** quadro, novo pedido de takeaway ou entrega, "Expedir" com escolha de estafeta, entregue ou
      devolvida.
- [ ] **KDS (`/kds`):**
  - [ ] Cartões por estado: na fila a azul, em preparação a âmbar, pronta a verde, "Atrasada" a vermelho.
  - [ ] Filtros por estação.
  - [ ] Iniciar → Marcar pronto → Retirar, e o histórico "Retirados".
  - [ ] Uma ronda retirada em todas as estações aparece como "servida" no painel da mesa.

### BackOffice

- [ ] **Geral:** tema claro, menu com a área atual a verde e barra de topo com empresa, loja e utilizador.
- [ ] **Organização:** lojas, terminais (emitir código pede o segundo fator e mostra o código uma só vez), salas e
      mesas.
- [ ] **Catálogo:** artigos, famílias, preços e versões. O código `COZINHA` aparece como "Cozinha". Publicar pede o
      segundo fator.
- [ ] **Produção:** encaminhamento, impressoras (modos Normal, Espera, Ignorar, Redirecionar) e fila de impressão.
- [ ] **Stocks:**
  - [ ] O resumo aparece em caixas coloridas.
  - [ ] Na receção de uma transferência com falta, o motivo da diferença passa a ser obrigatório.
- [ ] **Financeiro:**
  - [ ] Resumo, Fechos, Sessões de caixa (com "Ajustes" numa sessão fechada), Reconciliação, Fiscal, Meios e
        Auditoria.
  - [ ] Abre no primeiro separador que o utilizador pode ver.
- [ ] **Clientes:** dados, crédito, conta corrente e fidelização.
- [ ] **Pessoas:** empregados, utilizadores e funções. A matriz de permissões está em português, com o código ao lado
      e as permissões sensíveis marcadas.
- [ ] **Delivery:** zonas e relatório de entregas.
- [ ] **Pedidos:** pesquisa e detalhe de um pedido.

## 8. Problemas comuns

| Sintoma | Causa e solução |
| --- | --- |
| "Código inválido" no Keycloak | Colou o segredo em vez do código, o código expirou ou já foi usado. Ver a secção 4. |
| As credenciais deixaram de funcionar | Correu a seed outra vez: o ficheiro de credenciais tem a empresa nova. |
| "Código de registo inválido" no POS | O código já foi usado. Emita um novo em Organização › Terminais. |
| "Sem ligação" no registo do terminal | A stack está sobrecarregada. Repita: o ecrã oferece nova tentativa com o mesmo código. |
| O `/kds` recusa o empregado | Falta "Ver a produção" no papel dele (Pessoas › Funções). |
| "Expedir" não lista ninguém | Nenhum empregado da loja tem um papel com "Fazer entregas como estafeta". |
| Erro ao iniciar contentores | Pouco espaço no Docker ou portas ocupadas: veja `rtk docker system df` e use outras portas (secção 2). |

Para ver o que se passa nos serviços:

```bash
rtk proxy deploy/e2e/stack.sh logs
```

## 9. Parar e limpar

```bash
rtk proxy deploy/e2e/stack.sh down    # remove os contentores, os dados e .run/ (incluindo as credenciais)
```

## 10. Testes automáticos

| O quê | Comando |
| --- | --- |
| Backend, suite completa (a partir de `backend/`) | `rtk mvn test` |
| Backend, uma classe | `rtk mvn test -Dtest=NomeDaClasse` |
| Frontend, tipos (confirme "Done" em cada pacote) | `rtk proxy pnpm -r typecheck` (em `frontend/`) |
| Frontend, lint | `rtk lint` (em `frontend/`) |
| Frontend, testes unitários de uma app | `rtk test pnpm --filter pos test` ou `rtk test pnpm --filter backoffice test` |
| End-to-end sobre as apps compiladas | `rtk playwright test --project=pos --project=backoffice` (em `frontend/`) |
| End-to-end contra a stack real | `rtk proxy deploy/e2e/stack.sh test` |

O CI corre o backend completo (`.github/workflows/backend.yml`) e o frontend com o end-to-end contra a stack real
(`.github/workflows/frontend.yml`) em cada pull request.
