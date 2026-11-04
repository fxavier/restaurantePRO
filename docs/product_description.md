# RestaurantPRO — Descrição do Produto

## 1. Visão e decisão de produto

Sistema integrado de gestão de restaurantes, com FrontOffice para atendimento e vendas e BackOffice para configuração, gestão, controlo e análise. Cada empresa tem a sua instalação independente: aplicação, PostgreSQL, ficheiros, credenciais, configurações, integrações e backups próprios.

**Decisão confirmada em 3 de novembro de 2026:** produto instalado por empresa, sem plataforma SaaS e sem base de dados partilhada entre empresas. Esta descrição substitui a anterior proposta multi-tenant desta página.

Este documento é a especificação única de FrontOffice e BackOffice. Os dois nomes identificam interfaces e perfis de utilização do mesmo sistema, com um único modelo de domínio. As páginas anteriores passam a remeter para esta especificação. O manual original não foi disponibilizado nesta revisão; o âmbito funcional foi consolidado a partir das duas páginas do Notion.

## 2. Instalação por empresa

| Elemento                 | Regra                                                                             |
| ---------------------------- | ------------------------------------------------------------------------------------- |
| Empresa                      | Uma entidade jurídica por instalação; perfil fiscal único configurado no arranque     |
| Lojas                        | Uma ou várias lojas da mesma empresa; store\_id distingue operações e permissões      |
| Backend                      | Um monólito modular dedicado à empresa                                                |
| Dados                        | PostgreSQL exclusivo; schemas organizam módulos, não empresas                         |
| Ficheiros e segredos         | Diretórios/volumes e credenciais exclusivos                                           |
| Identidade                   | Contas, perfis, sessões e dispositivos próprios                                       |
| Gestão comercial do software | Contrato/licença e manutenção por instalação; modelo comercial por definir            |
| Código                       | Base comum e releases versionadas para todas as instalações; evitar forks por cliente |

Não existem Tenant, TenantPlan, subscrições SaaS, seleção de empresa no login, tenant\_id em tabelas, tenant claims ou endpoints /tenants e /plans. O isolamento entre empresas acontece na infraestrutura. Manter controlo de acesso por loja dentro de cada instalação.

**Recomendação de implantação:** servidor local da empresa para continuidade na rede interna sem Internet. Hospedagem em servidor dedicado da empresa é uma alternativa, mas a operação passa a depender da ligação ao servidor remoto. Uma instalação por empresa não significa uma instalação por terminal.

Para várias lojas: uma instalação central da empresa funciona com ligação estável ou VPN. Continuidade autónoma de cada loja durante corte WAN exige servidores locais e sincronização, e constitui uma evolução separada; não está implicitamente incluída.

## 3. Utilizadores e responsabilidades

| Perfil               | Responsabilidades                                      |
| ------------------------ | ---------------------------------------------------------- |
| Administrador da empresa | Configuração, utilizadores, permissões e instalação        |
| Gerente                  | Supervisão, autorizações, catálogo, fechos e indicadores   |
| Empregado de sala        | Mesas, pedidos, consulta, transferências autorizadas       |
| Caixa                    | Recebimentos, documentos, movimentos e fecho de sessão     |
| Cozinha/bar              | Receção e preparação de pedidos por zona                   |
| Gestor de stock/compras  | Fornecedores, receções, inventário, receitas e desperdício |
| Contabilidade/auditor    | Consulta, reconciliação, exportação e auditoria            |

RBAC obrigatório no backend, com âmbito por loja e terminal quando aplicável. Ocultar botões não substitui autorização. Aprovações críticas identificam operador e aprovador.

## 4. Atendimento e operação — interface POS

### Sala e atendimento

Mapa de áreas e mesas com estado, empregado, número de pessoas, tempo de ocupação, consumo e saldo. Abertura, consulta, transferência parcial ou total, junção operacional de consumos e fecho controlado. Mesas/cartões bloqueados não aceitam novas operações, preservando resolução de consumos já existentes por gerente.

Reservas com calendário, contacto, horário, pessoas, observações e mesas associadas. Na versão base são informativas, sem garantia automática de capacidade; prevenção de sobreposição e capacidade é uma evolução explícita.

### Pedidos e catálogo operacional

Registo touch/teclado por família, subfamília, pesquisa, código e páginas rápidas. Suporte a variantes, quantidades/peso, observações de preparação, menus, pratos do dia, macros e artigos automáticos configurados.

Separar linhas em rascunho das confirmadas. Confirmar “Pedir” grava a operação e os trabalhos de produção. Novas rondas acrescentam lotes de linhas sem reenviar os lotes anteriores. Anulação após envio exige motivo, autorização e comunicação à produção. Desperdício é registado mesmo sem receita de venda.

Modalidades: sala, balcão, takeaway, pub, delivery por telefone e consumo próprio. Delivery inclui cliente, morada, zona/taxa, estado de entrega e histórico; marketplace público e frota com GPS não estão incluídos.

### Produção e impressão

Encaminhamento por artigo, loja e zona: cozinha, bar e copa. Fila persistente, consulta de falhas, pausa, redirecionamento, teste e reimpressão auditada. Ecrã de cozinha pode ser adicionado como interface do mesmo domínio.

A falha de impressora não perde nem desfaz o pedido confirmado. Reimpressão é identificada como cópia. Um envio com resultado desconhecido exige tratamento explícito: dedupe\_key evita duplicação lógica, mas não garante impressão física exatamente uma vez.

### Recebimentos, documentos e caixa

Múltiplos métodos, troco em numerário, pagamentos parciais e divisão por artigos/quantidades ou partes. Alocações impedem cobrar duas vezes a mesma quantidade. Descontos e ofertas obedecem a limites e ficam auditados.

Conta corrente é uma venda a crédito com dívida e limite; não é dinheiro recebido. Emissão fiscal, pagamento e liquidação têm estados distintos. Uma fatura pode estar emitida e por pagar.

Caixa com dia comercial, turnos, caixas e sessões diretas/bolsas; fundo inicial, entradas, retiradas, despesas autorizadas, contagem, apuramento e diferença justificada. Dia comercial pode atravessar a meia-noite. Fecho operacional e aprovação financeira são distintos.

## 5. Administração e controlo — interface de gestão

### Organização e acessos

Configurar o perfil único da empresa, lojas, horários, timezone, moeda, áreas, mesas, terminais e armazéns. A empresa é criada no bootstrap; não há cadastro de várias empresas na mesma instalação.

Gerir empregados, contas administrativas, funções, permissões, associação a lojas, PIN, bloqueio e sessões. Acesso de suporte é temporário, autorizado e auditado.

### Catálogo, preços e campanhas

Famílias, subfamílias, artigos, variantes, unidades, códigos de barras, impostos, listas de preço, disponibilidade e destino de produção. Promoções, happy hour, fidelização, regras de acumulação, ofertas e limites de desconto.

Publicação versionada: DRAFT → VALIDATED → PUBLISHED → RETIRED. Guardar vigência e autor; pedidos e documentos preservam snapshots de descrição, preço, imposto e desconto. Publicar nova configuração não recalcula consumos antigos.

### Clientes e crédito

Contactos normalizados, pesquisa por telefone/nome, moradas, identificação fiscal, histórico, extratos, limites e bloqueio de crédito. Consulta por sufixo deve devolver candidatos, nunca selecionar automaticamente uma pessoa. Fidelização usa movimentos rastreáveis, incluindo reversões.

### Stock, receitas e compras

Fornecedores, encomendas, receções parciais, devoluções, armazéns, transferências, inventários, ajustes, stock mínimo, custo médio, receitas e unidades/conversões. Fichas técnicas incluem rendimento, ingredientes, embalagem e versão.

Movimentos são o registo de referência; saldo é uma projeção reconciliável. Consumo é processado por evento persistido ou lote idempotente. A política distingue preparação, venda, oferta, cancelamento e desperdício; cancelar faturação não repõe automaticamente alimentos já consumidos.

Não bloquear a venda por indisponibilidade temporária do processamento de stock. Exibir atraso e exceções; política de stock negativo ou disponibilidade estrita é configurável e validada antes da venda quando necessária.

### Financeiro, fiscal e relatórios

Vendas, recebimentos, dívidas, diferenças, descontos, ofertas, estornos, reconciliação de adquirentes e aprovação de fechos. Uma reabertura autorizada gera rastreabilidade e ajustes; não reescreve documentos emitidos.

Séries, tipos de documento, numeração concorrente, identidade fiscal e exportações dependem do país de instalação. Portugal/SAF-T PT e exemplos de fornecedores nas páginas originais não definem automaticamente o mercado do produto. País, certificação, impostos, documentos e obrigações devem ser validados antes da entrada em produção. Integrações obrigatórias no mercado escolhido são condição de lançamento, não uma fase opcional posterior.

Relatórios por loja/período/empregado/artigo/zona/método: vendas, impostos, caixa, crédito, stock, margens, desperdício, compras e auditoria. Exportação autorizada para contabilidade. Não inclui contabilidade geral completa, salários ou RH completo.

## 6. Arquitetura proposta

Monólito modular Java/Spring Boot com Spring Modulith, PostgreSQL e Flyway. Dois frontends React POS e BackOffice, no mesmo workspace e com biblioteca visual partilhada. Esta escolha reduz variedade tecnológica; versões suportadas e compatíveis serão fixadas e testadas no arranque da implementação.

Um deploy do backend, fronteiras por domínio e interfaces públicas entre módulos. FrontOffice/BackOffice são canais de acesso aos mesmos casos de uso; não duplicar entidades e lógica por interface.

| Módulo   | Dono dos dados e regras                                           |
| ------------ | --------------------------------------------------------------------- |
| organization | Perfil da empresa, lojas, calendário, terminais                       |
| identity     | Utilizadores, empregados, RBAC, sessões                               |
| dining       | Áreas, mesas e reservas                                               |
| catalog      | Artigos, preços, impostos configuráveis, publicação                   |
| promotions   | Campanhas, descontos e fidelização                                    |
| customers    | Clientes, moradas e conta corrente                                    |
| orders       | Consumos, rondas, transferências e anulações                          |
| production   | Encaminhamento, impressão, trabalhos e dispositivos de produção       |
| settlement   | Tentativas de pagamento, alocações, liquidação e coordenação de fecho |
| fiscal       | Documentos, séries, numeração e adaptadores fiscais                   |
| cash         | Sessões, movimentos, apuramento e fechos                              |
| inventory    | Stock, receitas, compras e fornecedores                               |
| reporting    | Projeções, consultas e exportações                                    |
| audit        | Eventos funcionais e consulta autorizada                              |

Agrupar subdomínios em packages quando conveniente; o número de módulos não é uma meta. Usar API/application/domain/infrastructure onde houver complexidade real. Shared contém apenas tipos técnicos estáveis; não concentra regras de negócio.

Settlement coordena fecho chamando interfaces de Orders, Cash e Fiscal. Orders não depende de Settlement. Cash não depende de Orders. Produção, stock e reporting consomem factos por mecanismos persistentes. Validar ausência de ciclos e acesso indevido a implementações internas.

## 7. Consistência, pagamentos e falhas

1. Confirmar pedido valida versão e grava linhas/lote e trabalho de produção na mesma transação.
2. Worker só executa trabalhos confirmados; falhas têm retry, limite, alerta e intervenção.
3. Para pagamento eletrónico, persistir tentativa e referência antes de chamar o prestador. Não manter locks da base durante a chamada externa.
4. Timeout gera estado UNKNOWN na tentativa e pendência de reconciliação; consultar o prestador antes de repetir ou permitir cobrança alternativa.
5. Confirmação autenticada e idempotente regista recebimento e movimento de caixa uma única vez.
6. Emissão fiscal tem idempotência e numeração própria. Falha fiscal após pagamento gera pendência visível, sem nova cobrança; regra de conclusão depende do adaptador fiscal.
7. Crédito e documento emitido sem recebimento não criam movimento de entrada em caixa.
8. Fecho valida saldos, operações pendentes, permissões e estado da sessão dentro de transação.

Eventos assíncronos importantes precisam de publicação persistida/outbox ou registo de publicações do Modulith; um evento apenas em memória não garante recuperação após crash. Consumidores são idempotentes. Não introduzir Kafka no âmbito inicial.

## 8. Modelo de dados e invariantes

Dados estruturantes: company\_profile único, store, terminal, employee, employee\_store\_assignment, role, permission, session; item, item\_variant, price\_list, catalog\_version; customer, credit\_ledger; restaurant\_table, reservation; order\_header, order\_line, order\_batch, void, transfer; print\_job; payment\_attempt, payment, payment\_allocation; fiscal\_series, fiscal\_document, fiscal\_document\_line; cash\_day, cash\_shift, cash\_register, cash\_session, cash\_movement; supplier, purchase\_order, goods\_receipt, recipe\_version, stock\_movement e audit\_event.

- store\_id obrigatório em operações de loja; autorização validada no servidor e integridade das referências entre lojas.
- EmployeeStoreAssignment suporta várias lojas; não limitar empregado a um único store\_id.
- Valores monetários em decimal/BigDecimal, moeda explícita, regra única de arredondamento e rateio dos cêntimos na divisão.
- Snapshots de preço/imposto/receita preservam o histórico.
- Documentos e movimentos confirmados não são apagados; corrigir por eventos/documentos compensatórios permitidos pelo regime aplicável.
- Versionamento otimista para edição; locks curtos e constraints para pagamento, fecho e numeração.
- Uma mesa tem no máximo um pedido ativo; estados operacionais e de pagamento são separados.
- Mesa volta a FREE depois do fecho; CLOSED pertence ao histórico da ocupação.
- Índices guiados por consulta: pedidos por mesa/estado, linhas por pedido, movimentos por loja/dia, trabalhos por estado/data, referências externas únicas e chaves de idempotência únicas por operação.
- Pesquisa contains/sufixo requer índice adequado e plano verificado; B-tree de igualdade não resolve todos esses padrões.

### 8.1 Linguagem de domínio e propriedade das entidades

| Conceito canónico | Entidade / módulo proprietário                      | Significado e fronteira                                                                       |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Empresa               | CompanyProfile / organization                           | Perfil único da entidade jurídica da instalação; fiscal guarda snapshots nos documentos           |
| Loja                  | Store / organization                                    | Unidade operacional da empresa; “site”, “restaurante” e “local” não criam entidades paralelas     |
| Terminal              | Terminal / organization                                 | Posto registado; produção referencia o seu identificador, sem duplicar cadastro                   |
| Armazém               | Warehouse / inventory                                   | Local de stock associado à loja; não pertence simultaneamente a organization                      |
| Área e mesa           | RoomArea, RestaurantTable / dining                      | Sala/esplanada/balcão e mesa física; modo de pedido é atributo próprio                            |
| Empregado e conta     | Employee, UserAccount / identity                        | Pessoa operacional e credencial de acesso; atribuições por loja em EmployeeStoreAssignment        |
| Cliente               | Customer / customers                                    | Identidade/contactos; LoyaltyPointLedger pertence a promotions, não a customers                   |
| Crédito               | CustomerCreditAccount, CreditLedgerEntry / customers    | Limite, dívida e liquidações; settlement coordena a cobrança, sem duplicar saldo                  |
| Artigo de venda       | Item, ItemVariant / catalog                             | Produto comercial; StockItem e receita pertencem a inventory                                      |
| Preço e imposto       | ItemPrice, TaxCategory, TaxRate / catalog               | Configuração comercial versionada; fiscal aplica validações legais e preserva os valores emitidos |
| Pedido                | Order, OrderLine, OrderBatch / orders                   | Consumo com várias rondas; order\_header é o nome físico de Order, não outra entidade             |
| Ocupação              | TableOccupation / dining                                | Histórico de utilização da mesa associado ao pedido; conserva abertura e fecho                    |
| Produção              | ProductionTicket, PrintJob / production                 | Talão lógico e tentativas de entrega física distintos                                             |
| Conta dividida        | BillPartition, BillPartitionLine / settlement           | Partição de valores/quantidades para cobrança; não é documento fiscal                             |
| Pagamento             | PaymentAttempt, Payment, PaymentAllocation / settlement | Tentativa externa, recebimento confirmado e imputação a dívida/conta                              |
| Documento             | FiscalDocument, FiscalDocumentLine / fiscal             | Fatura/recibo/documento retificativo conforme país; subtotal é apresentação não fiscal            |
| Caixa                 | CashRegister, CashSession, CashMovement / cash          | Posto financeiro, responsabilidade de operador e movimento monetário                              |
| Fecho                 | CashClosing / cash                                      | Contagem, apuramento, divergência e aprovação; não duplica dados em finance                       |
| Reconciliação         | ReconciliationEntry / settlement                        | Compara pagamento interno e registo do prestador, incluindo taxas e diferenças                    |
| Venda                 | SalesRecord / reporting                                 | Projeção de documentos/vendas; nunca gera recebimento ou stock por iniciativa própria             |

“Finance” é uma área funcional da interface e reúne cash, settlement e reporting; não é um segundo módulo que mantém cópias dos movimentos. “Devices” é configuração de interface: terminal pertence a organization, impressora a production. “Payments & Billing” divide-se em settlement e fiscal, com responsabilidades explícitas. Não criar tabelas/entidades duplicadas por interface.

### 8.2 Agregados e relações essenciais

- CompanyProfile contém um único registo de configuração; Store e Terminal têm identidade própria e códigos únicos no âmbito definido.
- RestaurantTable pertence a uma RoomArea e loja. TableOccupation referencia mesa e pedido; uma ocupação aberta por mesa. Pedido de takeaway/delivery não exige mesa.
- Order contém OrderLine e OrderBatch. Uma linha tem quantidade decimal positiva, descrição, variante, preço, desconto, imposto, versão publicada e observações; lotes confirmados preservam exatamente o que foi enviado.
- Transfer regista origem, destino e quantidades; a transferência move o direito de cobrança sem duplicar consumo de produção/stock. Transferências entre lojas ficam fora do fluxo normal.
- BillPartition recebe quantidades ou valores explicitamente alocados. Quantidade anulada ou já faturada não pode ser novamente alocada. Novos consumos não alteram partições já emitidas.
- PaymentAllocation liga recebimento a documento/dívida ou partição ainda em liquidação; distinguir adiantamento de pagamento de documento emitido conforme adaptador fiscal.
- CashMovement referencia o pagamento ou ajuste que o originou. Referência de origem única impede duplicar movimentos.
- FiscalDocument guarda identidade da empresa/cliente, série, número, linhas, totais, moeda e referências corretivas. Documento emitido não depende de leitura do catálogo atual.
- StockMovement referencia origem, ingrediente, receita e armazém; a combinação de origem/linha/tipo impede consumo duplicado.
- Relações entre módulos usam IDs e APIs públicas; entidades JPA internas não atravessam fronteiras. Integridade é validada na aplicação e reforçada por constraints adequadas.

### 8.3 Máquinas de estados únicas

| Objeto           | Estados / transições                                                     | Regra                                                                                                                            |
| -------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Order.lifecycle      | OPEN → CLOSED ou CANCELLED                                                   | Continua OPEN durante várias rondas e pagamentos parciais; fechar exige liquidação ou crédito autorizado e situação fiscal resolvida |
| OrderLine            | DRAFT → CONFIRMED → parcialmente/totalmente anulada por registos de anulação | Não apagar nem sobrescrever quantidade já confirmada; saldo resulta dos factos                                                       |
| OrderBatch           | CONFIRMED → DISPATCHED                                                       | DISPATCHED significa trabalho entregue ao subsistema de produção; preparação e impressão têm estados próprios                        |
| TableOccupation      | OPEN → CLOSED                                                                | Mesa fica FREE quando não há ocupação aberta; reserva e bloqueio são indicadores independentes                                       |
| Reservation          | BOOKED → SEATED, CANCELLED ou NO\_SHOW                                       | Reserva informativa não bloqueia automaticamente venda                                                                               |
| ConfigurationRelease | DRAFT → VALIDATED → PUBLISHED → RETIRED                                      | Estados de publicação distintos da disponibilidade ACTIVE/INACTIVE do artigo                                                         |
| PaymentAttempt       | CREATED → PENDING → SUCCEEDED, FAILED, CANCELLED ou UNKNOWN                  | UNKNOWN pode resolver para SUCCEEDED/FAILED por reconciliação; nunca tratar timeout como falha definitiva                            |
| Payment              | CONFIRMED, com reversões separadas                                           | Devolução é Refund com referência ao pagamento, não edição do valor original                                                         |
| PrintJob             | QUEUED → PROCESSING → SENT, RETRY\_WAIT, FAILED ou UNKNOWN                   | SENT indica transporte aceite quando observável; não prova que o papel foi impresso                                                  |
| CashSession          | OPEN → CLOSED                                                                | Correções posteriores são movimentos auditados; não reescrever contagem original                                                     |
| CashDay              | OPEN → OPERATIONALLY\_CLOSED → FINANCIALLY\_CLOSED                           | Reabertura é evento autorizado que volta a OPEN e conserva fechos anteriores; não usar REOPENED como estado permanente               |
| CashClosing          | DRAFT → SUBMITTED → APPROVED ou REJECTED                                     | Nova submissão preserva a anterior; aprovação e fecho financeiro não são o mesmo comando                                             |
| FiscalDocument       | DRAFT → ISSUED                                                               | Anulação/retificação são eventos e documentos permitidos pelo país; não impor um CANCELLED genérico a todos os regimes               |

Estado financeiro do pedido é derivado: UNSETTLED, PARTIALLY\_SETTLED ou SETTLED, calculado a partir de total devido, pagamentos e crédito autorizado. O estado de emissão fiscal é separado. Não usar SENT/PAID como ciclo completo do pedido.

### 8.4 Regras comerciais sem ambiguidades

- Fixar preço e imposto da linha no momento da confirmação. Mudanças do rascunho antes de confirmar exigem recálculo visível; alterações depois disso requerem operação explícita e auditada.
- Prioridade, acumulação e exclusão de promoções são determinísticas. Não ultrapassar base elegível, não permitir total negativo e guardar origem do desconto.
- Oferta reduz cobrança conforme regra fiscal aplicável, mas não elimina consumo de ingredientes. Consumo próprio é modalidade autorizada com classificação própria.
- Anulação de linha, cancelamento de tentativa, devolução de pagamento e correção fiscal são operações diferentes. Uma ação do operador pode coordená-las, mas cada efeito tem registo e autorização.
- Reimpressão não cria nova fatura, novo recebimento, novos pontos ou novo movimento de stock.
- Fidelização credita pontos sobre a base elegível quando a venda estiver liquidada, conforme política publicada; reversões são novos movimentos. Ajustes manuais exigem motivo.
- Reserva, bloqueio e impressão de subtotal não são estados mutuamente exclusivos da mesa. O mapa deriva cores desses indicadores com prioridade configurada.
- Compras suportam receção parcial; apenas a receção confirmada altera stock. Inventário regista instante de corte e movimentos posteriores para evitar divergências por vendas durante a contagem.
- Para pratos com receita, consumo é reconhecido uma única vez no facto de preparação/produção definido; artigos sem preparação podem usar venda confirmada. A política é versionada por categoria. Processar em lote muda a latência, não o facto económico.
- Estorno financeiro não significa reposição de stock. Devolução física utilizável, desperdício e reversão de erro são movimentos de stock distintos.
- Fecho de caixa considera todas as sessões da caixa; uma sessão não pode ser fechada com cobrança desconhecida sob sua responsabilidade sem resolução/reclassificação autorizada. Dia não fecha operacionalmente com sessões ou pedidos pendentes no seu âmbito.
- Débito a crédito usa validação e reserva transacional do limite, evitando que dois terminais ultrapassem o saldo disponível.
- Moeda base e timezone são definidos por instalação/loja; não misturar moedas num apuramento sem regra de conversão explícita. A primeira versão opera numa moeda base.

### 8.5 Fluxos completos entre as duas interfaces

| Fluxo            | Preparação administrativa                                              | Execução operacional                                               | Controlo e resultado                                   |
| -------------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------- |
| Nova instalação      | Configurar empresa, país, loja, utilizadores, dispositivos e séries        | Testar login, artigo, pedido, documento e impressão                    | Backup/restauro e aprovação do piloto                      |
| Publicar catálogo    | Criar artigo, unidade, imposto, preço, receita e destino; validar/publicar | POS carrega a versão e confirma linhas com snapshot                    | Auditoria de publicação e histórico preservado             |
| Atendimento          | Configurar áreas, mesas e políticas                                        | Abrir ocupação, registar rondas, enviar produção, emitir subtotal      | Gerente acompanha tempos, anulações e pendências           |
| Dividir/receber      | Métodos e políticas configurados                                           | Particionar, confirmar pagamentos, emitir documentos e fechar          | Reconciliar prestador, dívida e caixa sem duplicação       |
| Venda a crédito      | Aprovar cliente e limite                                                   | Identificar cliente, reservar limite, emitir documento e lançar dívida | Receber posteriormente e imputar ao extrato                |
| Anular após produção | Definir motivos/permissões                                                 | Aprovar anulação e notificar cozinha                                   | Registar desperdício e eventual correção financeira/fiscal |
| Compra/inventário    | Criar fornecedor e encomenda; iniciar contagem                             | Receber mercadoria ou contar físico                                    | Confirmar entradas/ajustes, custos e divergências          |
| Fecho diário         | Definir calendário e responsabilidades                                     | Fechar sessões, contar caixa e submeter                                | Aprovar divergências, fechar financeiramente e exportar    |

### 8.6 Cobertura funcional preservada na fusão

Incluídos: mesas/cartões e bloqueio, reservas informativas, takeaway normal/seguro por política, pub, delivery telefónico, consumo próprio, preços múltiplos, variantes/peso/códigos de barras, menus, páginas rápidas, pratos do dia, macros e automáticos. O modo “takeaway seguro” exige confirmação explícita antes do envio e impede alterações silenciosas após confirmação; detalhes de UX serão validados no piloto.

Incluídos: pessoas e tempos por mesa, empregado de abertura/serviço/fecho, transferências, ofertas, subtotal, parcial/split, troco, dados fiscais de cliente eventual ou regular, crédito, bolsas, envelopes, fundo, retiradas e despesas. Movimentos especiais usam tipos e permissões, sem criar motores financeiros paralelos.

Incluídos: impressoras Normal/Espera/Ignorar/Redirecionar, cópias e teste de hardware. Ignorar requer autorização e marca supressão explícita; não elimina trabalho sem rasto. Falha e supressão são situações distintas.

Incluídos: utilizadores/sessões, catálogo/campanhas, crédito, stock/receitas, compras/fornecedores, margem/custo médio, fechos/reconciliação, séries/documentos/exportações, integrações, auditoria e relatórios por dia/hora/loja/área/terminal/empregado/artigo/método. Relatórios incluem top artigos, ofertas/descontos/anulações, impostos por taxa, dívidas, rotação, mínimos, quebras e divergências.

## 9. API e atualização dos terminais

Padrão: /api/v1/pos e /api/v1/backoffice; autenticação comum em /api/v1/auth. Todos os exemplos abaixo usam este padrão único.

POS: mesas, pedidos, lotes, anulações, transferências, subtotais, divisão, pagamentos, documentos, sessões e movimentos. BackOffice: company-profile singleton via GET/PUT, lojas, catálogo, publicação, acessos, stock, compras, relatórios, reconciliação, fiscal e auditoria.

Respostas de erro estruturadas, validação, paginação e filtros; conflito de versão devolve 409. Idempotency-Key em operações financeiras e confirmações com efeitos repetíveis, com hash do pedido e resposta guardada; mesma chave com payload diferente é rejeitada.

SSE ou WebSocket para notificar mudanças de mesa/pedido/configuração; o cliente refaz leitura autorizada. Reconexão e polling de contingência recuperam estado; notificação não é fonte de verdade.

### Contratos principais por capacidade

| Capacidade           | Interface / exemplos de endpoints                                                                                         |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| Sessões                  | POST /api/v1/auth/login; POST /api/v1/auth/logout; GET /api/v1/auth/session                                                   |
| Empresa e lojas          | GET/PUT /api/v1/backoffice/company-profile; GET/POST /api/v1/backoffice/stores                                                |
| Acessos                  | /api/v1/backoffice/users, /employees, /roles; POST /employees/{id}/reset-pin                                                  |
| Mesas e reservas         | GET /api/v1/pos/tables; POST /api/v1/pos/tables/{id}/open; /api/v1/pos/reservations                                           |
| Configuração de sala/POS | /api/v1/backoffice/areas, /tables, /terminals, /pos-profiles                                                                  |
| Catálogo                 | /api/v1/backoffice/catalog/items, /price-lists, /releases; POST /releases/{id}/publish                                        |
| Catálogo publicado       | GET /api/v1/pos/catalog; versão publicada e vigência explícitas                                                               |
| Pedidos                  | POST /api/v1/pos/orders; POST /orders/{id}/lines; POST /orders/{id}/batches                                                   |
| Ajustes de consumo       | POST /api/v1/pos/orders/{id}/voids, /transfers, /discounts; POST /subtotal/print                                              |
| Divisão e cobrança       | POST /api/v1/pos/orders/{id}/partitions; POST /api/v1/pos/payment-attempts; GET /payment-attempts/{id}                        |
| Fecho e documentos       | POST /api/v1/pos/orders/{id}/close; GET /api/v1/pos/documents/{id}; POST /documents/{id}/reprints                             |
| Crédito e clientes       | /api/v1/backoffice/customers; GET /customers/{id}/statement; POST /customers/{id}/credit-limit-changes                        |
| Produção                 | /api/v1/backoffice/printers, /print-jobs; POST /print-jobs/{id}/retry; ações POS restritas por permissão                      |
| Caixa                    | /api/v1/pos/cash/sessions, /movements, /closings; aprovação em /api/v1/backoffice/cash/closings/{id}/approve                  |
| Fiscal                   | /api/v1/backoffice/fiscal/series, /documents, /exports; correções por comandos específicos do regime                          |
| Stock e compras          | /api/v1/backoffice/inventory/warehouses, /stock-items, /movements, /counts, /recipes, /suppliers, /purchase-orders, /receipts |
| Controlo                 | /api/v1/backoffice/reconciliation, /reports, /audit/events                                                                    |

Quando uma célula abrevia o prefixo, os caminhos seguintes usam o mesmo prefixo da primeira rota da célula. São contratos de desenho, não endpoints implementados; o OpenAPI deverá detalhar DTOs, permissões, erros e idempotência. Escrita administrativa e operacional chama a mesma regra de domínio. Não expor mutação direta de saldos, numeração fiscal ou estados para contornar comandos.

## 10. Segurança

Sessões server-side e cookies Secure/HttpOnly/SameSite, HTTPS também na LAN e CSRF nos pedidos autenticados por cookie. POS identifica empregado e terminal; PIN individual protegido por hash adaptativo, limitação de tentativas e bloqueio. Administração usa credenciais fortes; MFA para acesso remoto e funções sensíveis, conforme implantação.

Privilégio mínimo por função/loja; autorização adicional para anulação, descontos, crédito, reabertura e exportações. Base de dados não exposta à rede pública. Segredos fora do código, permissões de ficheiros restritas, backups cifrados e logs sem PIN, palavras-passe ou dados completos de pagamento.

Auditoria append-only para a aplicação, com ator, aprovador, ação, entidade, loja, terminal, instante e motivo. Antes/depois apenas quando necessário e sem segredos. Acesso de administrador da infraestrutura é risco distinto, controlado por segregação e cópias protegidas.

## 11. Operação, continuidade e manutenção

Pacote de instalação reproduzível: reverse proxy, frontend estático, backend, PostgreSQL e worker/ponte de impressão quando exigido pelo hardware. Docker Compose é a opção inicial proposta; instalação nativa pode ser suportada conforme ambiente. Versões fixadas, volumes persistentes, health checks, arranque automático, rotação de logs e UPS.

Sem Internet, o servidor local continua pedidos, impressão local, numerário e consulta; serviços externos ficam indisponíveis ou pendentes conforme contrato. Sem LAN ou servidor, os terminais não confirmam novas operações nem simulam sucesso. Escrita offline por terminal e sincronização posterior ficam fora do MVP.

Backups automáticos de base, ficheiros, configurações e material necessário à recuperação; cópia cifrada fora do equipamento e testes de restauro. Backups não são alta disponibilidade. Plano de substituição do servidor e procedimento de contingência operacional.

**Metas propostas, a validar no piloto:** RPO até 15 minutos com estratégia WAL/PITR e RTO até 2 horas com equipamento de substituição disponível. Sem essa infraestrutura, declarar os objetivos efetivamente atingíveis. Retenção depende do contrato e do regime aplicável.

Atualizar cada empresa em janela acordada, após backup e ensaio em cópia. Migrações compatíveis, histórico de versões, smoke tests e plano de recuperação de dados; voltar à imagem anterior não desfaz automaticamente uma migração incompatível.

## 12. Qualidade e observabilidade

Logs estruturados e correlation ID; Actuator e métricas com acesso restrito. Alertas para backup em atraso, espaço em disco, fila de impressão, eventos falhados, pagamentos desconhecidos, divergência de caixa e falha de exportação. Não usar order\_id em labels de métricas.

Testes: regras de dinheiro/IVA configurável, promoções, estados, split, crédito, receitas, permissões, concorrência, idempotência, migrações e restauro. Integração PostgreSQL real em testes, contratos de prestadores e testes com impressoras reais. Testes arquiteturais verificam fronteiras do monólito.

**Meta de desempenho proposta:** piloto com 10 terminais concorrentes, p95 inferior a 500 ms para consultas e comandos locais simples, excluindo serviços externos/impressão física; medir com volume de dados e hardware acordados.

## 13. Entregas propostas

| Etapa              | Resultado verificável                                                                |
| ---------------------- | ---------------------------------------------------------------------------------------- |
| 1 — Base instalada     | Instalação por empresa, acessos, loja, catálogo, configuração e backup/restauro          |
| 2 — Operação integrada | Sala, pedidos por rondas, produção, pagamentos base, caixa e documentos exigidos no país |
| 3 — Controlo avançado  | Split/parcial, crédito, reconciliação, auditoria, relatórios e recuperação de falhas     |
| 4 — Gestão e extensões | Compras, receitas, inventários, promoções, fidelização, reservas e delivery              |

O âmbito completo inclui as quatro etapas. Não representa implementação concluída, compromisso de prazo ou certificação fiscal.

## 14. Critérios de aceitação essenciais

- Instalar empresas A e B com processos, base, volumes e segredos separados; credenciais de A não abrem B e restauro de A não altera B.
- Dois terminais na mesma loja veem alterações confirmadas; confirmação concorrente não perde linhas nem duplica lotes.
- Repetir uma cobrança com a mesma chave não gera outro pagamento ou movimento; timeout externo permanece reconciliável.
- Dividir a conta preserva total, impostos e quantidades; crédito não aparece como dinheiro recebido.
- Desligar impressora mantém pedido e trabalho pendente; recuperar após reinício sem duplicação lógica.
- Cortar Internet com servidor local mantém operação local; cortar servidor/LAN mostra indisponibilidade sem confirmação falsa.
- Publicar preço novo não altera vendas/documentos anteriores.
- Restaurar backup em ambiente isolado demonstra os objetivos de recuperação acordados.
- Operador sem permissão não altera fiscal, preços ou fechos através da API.
- Cumprir testes e validação fiscal do país antes de utilização de faturação em produção.

## 15. Decisões pendentes

Confirmar país inicial, moeda, regime fiscal/certificação, modelos de impressora, integração de pagamentos, hardware, número de lojas/terminais, alojamento local ou dedicado e necessidade real de autonomia entre lojas. São parâmetros de implementação; não alteram a decisão de instalação independente por empresa.

## 16. Referências técnicas

- [Spring Modulith — eventos e registo de publicações](https://docs.spring.io/spring-modulith/reference/events.html)
- [Spring Security — CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [PostgreSQL — backup e restauro](https://www.postgresql.org/docs/current/backup.html)

Estas fontes sustentam mecanismos técnicos; não certificam o produto nem substituem validação fiscal local.
