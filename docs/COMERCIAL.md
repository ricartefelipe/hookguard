# Condição comercial

Este texto descreve o que o software faz hoje. Não fixa preço, não cita cliente, não concede licença e não substitui contrato.

## Quem compra e qual dor

Comprador típico: time pequeno que recebe webhooks de provedores (pagamento, loja, ERP) e não quer operar fila, retry e DLQ só para isso. Usuário: quem configura o projeto e quem implementa o endpoint de destino.

Dor verificável no produto: o provedor não reenvia para sempre, o app de destino cai, e o time precisa de aceite durável, tentativa observável e replay sem misturar dados de outro cliente.

## O que está incluído

- Ingestão HTTP com `202` depois da persistência, limite de corpo, tipo e taxa por projeto.
- Fila no Postgres, lease entre instâncias, retry, DLQ e replay com motivo, sem segunda cobrança de uso.
- Assinatura própria da entrega e rotação de segredo com janela.
- Allowlist de headers de provedor. Credencial de origem não segue para o destino.
- Bloqueio de destino interno na conexão, sem seguir redirect.
- Conta por magic link (uso único, com expiração) ou GitHub OAuth quando configurado. Sessão revogável.
- Isolamento por conta em projeto, evento, replay e exclusão.
- Painel para criar projeto, rotacionar chave e segredo, pausar, enviar evento de teste, ver tentativas mascaradas e replay.
- Cota mensal por plano já codificada (free bloqueia; pro/business medem overage se o Stripe estiver configurado).
- Retenção configurável e expurgo. Backup é operacional, documentado em `docs/OPERACAO.md`.

## O que não está incluído

- Exactly-once no endpoint de terceiro.
- Transformação de payload com código arbitrário.
- Broker genérico no lugar de Kafka ou RabbitMQ.
- Multi-região, SSO enterprise, domínio próprio além do proxy já descrito, SDK oficial, fan-out com cobrança própria.
- Criptografia de payload com chave do cliente.
- Checkout com preço definido por este repositório. A tabela em `docs/STRIPE.md` é exemplo de script de setup.
- Licença de uso: o repositório não tem arquivo de licença. Publicar ou vender exige decisão do proprietário.

## Como operar a oferta

Modalidade coerente com o código atual: instalação gerenciada (um Postgres, a API e o painel) ou imagem no ambiente do cliente. Suporte inicial é o runbook em `docs/OPERACAO.md`. Onboarding: criar conta, criar projeto, copiar `projectKey` e `signingSecret` uma vez, apontar o provedor para `/v1/ingest/{projectKey}` e o destino `https`. Offboarding: excluir o projeto no painel ou apagar a conta no banco e encerrar o backup conforme o prazo combinado.

Demo curta, sem terceiro real: subir a stack local, criar projeto com destino em um listener seu, enviar o evento de teste no painel e mostrar a tentativa. Não dispare webhook para um endpoint de cliente durante o teste.

## Quando chamar de comercialmente apto

Piloto técnico: os testes da API passam no commit publicado, o restore foi lido de volta, e um destino controlado recebeu o evento com a assinatura nova. Isso não é homologação com Stripe live, GitHub OAuth de produção ou carga real. Esses três dependem de credencial do proprietário.

Não está apto a ser vendido como serviço público enquanto não houver licença, preço, responsável por suporte e um backup agendado fora desta sessão.
