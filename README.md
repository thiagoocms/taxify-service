# 🧾 Taxify — API (taxify-service)

> Plataforma para **escritórios de contabilidade** que automatiza a captura de **XMLs de notas fiscais**, encontra **erros de classificação fiscal (CFOP/NCM)** e gera a **retificação** com trilha de auditoria.

Este repositório contém a **API** do Taxify. O front-end está em **[taxify-ui](https://github.com/thiagoocms/taxify-ui)**.

| Repositório | Papel |
|---|---|
| **[taxify-service](https://github.com/thiagoocms/taxify-service)** (este) | API REST, integrações, rotinas agendadas e regras fiscais |
| [taxify-ui](https://github.com/thiagoocms/taxify-ui) | Front-end web em Angular |

---

## 🎯 Visão de negócio

**Problema:** escritórios de contabilidade atendem dezenas ou centenas de empresas. Baixar notas fiscais de cada cliente é manual e repetitivo, e notas emitidas com **CFOP ou NCM errados** só são descobertas tarde, gerando retrabalho, multas e risco fiscal.

**Solução:** o Taxify automatiza esse ciclo:

1. **Captura automática de XMLs:** sincroniza periodicamente as notas fiscais de cada empresa cliente a partir da **SIEG** (hub de documentos fiscais). Também aceita importação manual de arquivos.
2. **Regras fiscais por produto:** o contador cadastra o **CFOP e o NCM corretos** de cada produto da empresa.
3. **Detecção de divergências:** o sistema compara cada item das notas com as regras e aponta **o que está errado, em qual nota e qual seria o valor correto**.
4. **Retificação automática:** gera um **novo XML corrigido** (o original nunca é alterado) e registra tudo no **log de auditoria**.
5. **Controle de importação:** painel por empresa com *"a SIEG tem 200 notas, já importamos 180, faltam 20"*.
6. **Exportação:** download das notas (originais ou retificadas) em lote ou em **ZIP**.

**Modelo comercial (SaaS):**
- **Multi-tenant:** cada **escritório de contabilidade** é isolado. Uma equipe nunca vê os dados de outro escritório.
- **Planos e assinaturas:** as funcionalidades são liberadas por plano (`FISCAL_XML_IMPORT`, `INVOICE_AUDIT`, `SIEG_INTEGRATION`, `MULTI_USER_ACCESS`, `REPORTS`) com vigência por empresa.
- **Equipe com permissões:** o `ADMIN` vê todas as empresas do escritório, e os demais usuários veem só as empresas às quais estão vinculados.

**Público-alvo:** escritórios contábeis e departamentos fiscais de empresas.

---

## 🛠️ Visão técnica

### Stack
- **Java 25** + **Spring Boot 4**
- **Spring Security** + **OAuth2 Resource Server** (JWT assinado com **RSA**)
- **Spring Data JPA / Hibernate** + **MySQL 8**
- **Spring Cloud OpenFeign** para a integração com a API da SIEG
- **Spring Scheduling** para as rotinas automáticas (sincronização, retificação e expurgo)
- **Actuator** (health) + **Amazon CloudWatch** (métricas da EC2, logs e alarmes) para observabilidade — ver [`monitoring/README.md`](monitoring/README.md)
- **Springdoc OpenAPI** (Swagger)
- **Docker / Docker Compose**

### Arquitetura
```
                ┌──────────────┐        ┌──────────────────┐
  Angular  ───▶ │  Nginx (UI)  │ ─────▶ │  taxify-service  │ ──▶ MySQL
                └──────┬───────┘        │                  │ ──▶ Disco (XMLs)
                       │                │  Schedulers      │
                       ▼                │  Feign ─────────────▶ API SIEG
              CloudWatch (logs/métricas)│  /actuator/health│
                                        └──────────────────┘
```

Camadas: `rest/` → `service/` → `validation/` → `repository/`, com `mapper/`, `search/` (filtros dinâmicos via JPA Specifications) e um **CRUD genérico** em `common/`.

### Destaques de engenharia
- **Multi-tenancy com isolamento por escritório** (`TenantAccessService`). Acesso a dados de outro escritório responde **404**, não 403, para não revelar que o recurso existe.
- **Strategy para provedores de XML** (`FiscalXmlProvider`): a SIEG é uma implementação. Adicionar outro provedor é só registrar um novo bean, sem mexer no scheduler.
- **Rate limiter próprio** (janela deslizante) para respeitar o limite de requisições/minuto da SIEG sem receber HTTP 429.
- **Sincronização incremental:** o controle por empresa (`FiscalXmlSyncControl`) retoma a paginação de onde parou e pula a contagem quando já se sabe que há pendências, economizando chamadas limitadas.
- **Retificação idempotente:** não gera arquivo nem log duplicado quando as correções são as mesmas da última execução.
- **Original preservado:** o XML retificado é um arquivo novo. Como a edição invalida a assinatura digital da NF-e, o próprio arquivo avisa que precisa ser reassinado no emissor.
- **Expurgo com retenção configurável:** remove os arquivos físicos antigos pela data de emissão e mantém os metadados no banco para relatórios.
- **Liberação por plano:** só empresas com assinatura vigente do serviço correspondente são sincronizadas.
- **Auditoria e soft delete** em todas as entidades.
- **Configuração 100% por variáveis de ambiente** para as rotinas (cron, lote, retenção, provedor).

### Modelo de domínio (resumo)
```
AccountingFirm (escritório / tenant)
 ├── User ──*── UserCompany (papel) ──*── Company
 └── Company
      ├── Document (CNPJ/CPF...)
      ├── Subscription ──▶ Plan (serviços liberados)
      ├── ProductFiscalRule (CFOP/NCM corretos por produto)
      ├── FiscalXmlSyncControl (total na SIEG × importado)
      └── FiscalXmlDocument ──* FiscalXmlDocumentItem
                 └── InvoiceAuditLog (OK, NEEDS_RECTIFICATION, RECTIFIED...)
```
Diagramas completos em [`docs/diagrams`](docs/diagrams/taxify-diagrams.md).

### 📡 Principais endpoints
Base: `/taxify-service/api/v1`

| Recurso | Descrição |
|---|---|
| `/auth` | Login, retorna JWT |
| `/accounting-firms`, `/users`, `/users/{userId}/companies` | Escritórios, usuários e vínculos com empresas |
| `/companies` | Empresas clientes; `/{id}/certificado` (A1) e `/{id}/sefaz/capturar` |
| `/plans`, `/subscriptions` | Planos e assinaturas |
| `/integrations/xmls` | Busca, detalhe, produtos, XML bruto, **sincronizar**, **importar**, **retificar**, **exportar** (lista/ZIP) |
| `/fiscal-xml-sync-control` | Status de importação por empresa |
| `/product-fiscal-rules` | Regras CFOP/NCM e `GET /divergencias` |
| `/invoice-audit-log` | Trilha de auditoria das notas |

Swagger: `/taxify-service/swagger-ui.html`

### 🔐 Captura pela SEFAZ (certificado A1)
Junto com o SIEG, as notas são buscadas direto no Ambiente Nacional (**NFeDistribuicaoDFe**) com o
certificado A1 (e-CNPJ) de cada empresa — sem captcha nem navegador:

- O certificado é enviado na tela da empresa. O backend confere a senha, a validade e a raiz do
  CNPJ; o `.pfx` vai para o S3 em `certificados/` e a senha para o banco.
  **Chave opcional** `CERT_ENCRYPTION_KEY` (`openssl rand -base64 32`): com ela, os dois são gravados
  cifrados (AES-256-GCM); sem ela, só com a criptografia padrão do bucket e a senha em texto no banco.
  Certificados guardados sem a chave continuam abrindo depois que ela for configurada. Guarde uma
  cópia da chave: sem ela os certificados cifrados precisam ser reenviados.
- Filiais usam o certificado da matriz (mesma raiz de CNPJ).
- Começa por **Pernambuco**: só empresas das UFs em `SEFAZ_DIST_UFS` (padrão `PE`; vazio = todas)
  entram na captura automática. O botão "Capturar agora" da tela vale para qualquer UF.
- **Qual fonte, pelo plano:** "Importação de XML fiscal" (`FISCAL_XML_IMPORT`) coloca a empresa no
  agendador; a fonte vem das integrações do mesmo plano — `SIEG_INTEGRATION` → SIEG,
  `SEFAZ_INTEGRATION` → SEFAZ, as duas → as duas (a mesma nota nunca é gravada duas vezes).
- De hora em hora (`SEFAZ_DIST_CRON`), para empresas com certificado e a SEFAZ como fonte no plano: lê a partir do último NSU, grava as notas completas e os eventos pelo mesmo caminho
  do SIEG (sem duplicar) e respeita a pausa de 1h da SEFAZ (cStat 656). A SEFAZ guarda os
  documentos por 90 dias.
- Entradas que ainda não tiveram "Ciência da Operação" chegam só como resumo (`resNFe`): são
  contadas na tela, mas não importadas.

---

## ▶️ Como rodar

**Com Docker Compose** (MySQL + API + Front):
```bash
cp .env.example .env    # preencha as variáveis
docker compose up -d
```

**Na EC2**, com os logs dos containers indo para o CloudWatch (ver [`monitoring/README.md`](monitoring/README.md)):
```bash
docker compose -f docker-compose.yml -f docker-compose.aws.yml up -d
```
Na EC2 o acesso é por **HTTPS** via [Caddy](Caddyfile), com certificado do Let's Encrypt renovado sozinho:
- `DOMAIN` no `.env`, com o registro A do domínio apontando para o Elastic IP da instância;
- Security Group com 80 e 443 abertas (5000 e 8080 deixam de ser expostas);
- o `Caddyfile` na mesma pasta do compose;
- `CONTA_AZUL_REDIRECT_URI=https://SEU_DOMINIO/integracoes/conta-azul/callback`.

**Local:**
```bash
# Java 25 + MySQL 8, com as variáveis do .env exportadas
./mvnw spring-boot:run
```

---

## 🗺️ Próximos passos
- [ ] Relatórios gerenciais por empresa/período
- [ ] Novos provedores de XML além da SIEG
- [ ] Suporte a notas de saída (POC em andamento)
- [ ] Testes automatizados de integração

---

Desenvolvido por **[Thiago](https://github.com/thiagoocms)**
