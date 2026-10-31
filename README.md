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
- **Actuator + Micrometer + Prometheus + Grafana** para observabilidade
- **Springdoc OpenAPI** (Swagger)
- **Docker / Docker Compose**

### Arquitetura
```
                ┌──────────────┐        ┌──────────────────┐
  Angular  ───▶ │  Nginx (UI)  │ ─────▶ │  taxify-service  │ ──▶ MySQL
                └──────┬───────┘        │                  │ ──▶ Disco (XMLs)
                       │                │  Schedulers      │
                       ▼                │  Feign ─────────────▶ API SIEG
              Grafana / Prometheus ◀────│  /actuator/prom. │
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
| `/companies` | Empresas clientes |
| `/plans`, `/subscriptions` | Planos e assinaturas |
| `/integrations/xmls` | Busca, detalhe, produtos, XML bruto, **sincronizar**, **importar**, **retificar**, **exportar** (lista/ZIP) |
| `/fiscal-xml-sync-control` | Status de importação por empresa |
| `/product-fiscal-rules` | Regras CFOP/NCM e `GET /divergencias` |
| `/invoice-audit-log` | Trilha de auditoria das notas |

Swagger: `/taxify-service/swagger-ui.html`

---

## ▶️ Como rodar

**Com Docker Compose** (MySQL + API + Front + Prometheus + Grafana):
```bash
cp .env.example .env    # preencha as variáveis
docker compose up -d
```

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
