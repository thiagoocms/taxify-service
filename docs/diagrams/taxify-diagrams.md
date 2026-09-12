# Taxify - Diagramas

## Diagrama de Casos de Uso

Mermaid não possui um tipo nativo de diagrama de casos de uso, então ele é
representado abaixo como um fluxograma (atores em círculo, casos de uso em
elipse, dentro do limite do sistema).

```mermaid
flowchart LR
    Usuario((Usuário))
    Admin((Administrador))
    Sieg((Sistema SIEG)):::external

    subgraph Taxify["Sistema Taxify"]
        UC1([Autenticar-se])
        UC2([Gerenciar Usuários])
        UC3([Gerenciar Empresas])
        UC4([Vincular Usuário a Empresa<br/>definir papel/role])
        UC5([Baixar XMLs Fiscais da SIEG])
        UC6([Obter Token OAuth da SIEG])
        UC7([Gerenciar Log de Auditoria<br/>de Notas Fiscais])
    end

    Admin -. é um .-> Usuario

    Usuario --> UC1
    Usuario --> UC5
    Usuario --> UC7

    Admin --> UC2
    Admin --> UC3
    Admin --> UC4

    UC5 -. include .-> UC1
    UC5 -. include .-> UC6
    UC2 -. include .-> UC1
    UC3 -. include .-> UC1
    UC4 -. include .-> UC1
    UC7 -. include .-> UC1

    UC5 --> Sieg
    UC6 --> Sieg

    classDef external fill:#eee,stroke:#999,stroke-dasharray: 4 4
```

## Diagrama de Classes (Domínio)

```mermaid
classDiagram
    class AbstractAuditingEntity {
        <<abstract>>
        -String createdBy
        -LocalDateTime createdDate
        -String lastModifiedBy
        -LocalDateTime lastModifiedDate
        -boolean deleted
    }

    class User {
        -UUID id
        -String name
        -String login
        -String password
        -String email
        -UserProfileEnum profile
    }

    class Auth {
        +getAuthorities() Collection~GrantedAuthority~
        +getUsername() String
    }

    class Company {
        -UUID id
        -String name
    }

    class Document {
        -UUID id
        -String number
        -DocumentTypeEnum type
    }

    class UserCompany {
        -UserCompanyId id
        -String role
    }

    class UserCompanyId {
        <<embeddable>>
        -UUID userId
        -UUID companyId
    }

    class CompanyAuthority {
        -Company company
        -String role
        +getAuthority() String
    }

    class InvoiceAuditLog {
        -UUID id
        -String externalInvoiceKey
        -InvoiceAuditStatus status
        -String notes
    }

    class FiscalXmlDocument {
        -UUID id
        -String accessKey
        -String documentType
        -String issuerCnpj
        -String recipientCnpj
        -LocalDateTime issueDate
        -String xmlContent
        -String sourceSystem
    }

    class UserProfileEnum {
        <<enumeration>>
        ADMIN
        EMPLOYEE
        USER
    }

    class DocumentTypeEnum {
        <<enumeration>>
        CPF
        RG
        CNH
        CNPJ
    }

    class InvoiceAuditStatus {
        <<enumeration>>
        OK
        NEEDS_RECTIFICATION
        PENDING_DOCUMENTATION
        REJECTED
    }

    class UserDetails {
        <<interface>>
    }

    class GrantedAuthority {
        <<interface>>
    }

    AbstractAuditingEntity <|-- User
    AbstractAuditingEntity <|-- Company
    AbstractAuditingEntity <|-- Document
    AbstractAuditingEntity <|-- UserCompany
    AbstractAuditingEntity <|-- InvoiceAuditLog
    AbstractAuditingEntity <|-- FiscalXmlDocument

    User <|-- Auth
    UserDetails <|.. Auth
    GrantedAuthority <|.. CompanyAuthority

    User "1" -- "1" Document : possui
    Company "1" -- "1" Document : possui

    User "1" o-- "*" UserCompany : userCompanies
    Company "1" o-- "*" UserCompany : companies
    UserCompany "1" *-- "1" UserCompanyId : id

    User "1" ..> "*" InvoiceAuditLog : registra
    Company "1" ..> "*" InvoiceAuditLog : possui

    Company "1" ..> "*" FiscalXmlDocument : possui

    Auth ..> CompanyAuthority : cria
    CompanyAuthority --> "1" Company

    User --> UserProfileEnum
    Document --> DocumentTypeEnum
    InvoiceAuditLog --> InvoiceAuditStatus
```
