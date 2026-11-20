package br.com.cshub.taxify.integration.acessorias;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One company as {@code GET /companies/ListAll} returns it (with the departments,
 * registrationData and stateRegistrations flags). Dates come as {@code yyyy-MM-dd}, with
 * {@code 0000-00-00} meaning "not set".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcessoriasCompanyDTO(
        @JsonProperty("ID") String id,
        @JsonProperty("Identificador") String identificador,
        @JsonProperty("Razao") String razao,
        @JsonProperty("Fantasia") String fantasia,
        @JsonProperty("Status") String status,
        @JsonProperty("Telefone") String telefone,
        @JsonProperty("UF") String uf,
        @JsonProperty("ClienteDesde") String clienteDesde,
        @JsonProperty("ClienteAte") String clienteAte,
        @JsonProperty("Regime") String regime,
        @JsonProperty("GrupoDeEmpresas") String grupoDeEmpresas,
        @JsonProperty("InscricoesEstaduais") List<StateRegistration> inscricoesEstaduais,
        @JsonProperty("Departamentos") List<Department> departamentos
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StateRegistration(
            @JsonProperty("IE") String ie,
            @JsonProperty("UF") String uf
    ) {
    }

    /** A department (Fiscal, Contábil, Pessoal...) and the firm's team member responsible for it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Department(
            @JsonProperty("ID") String id,
            @JsonProperty("Nome") String nome,
            @JsonProperty("RespNome") String respNome,
            @JsonProperty("RespEmail") String respEmail
    ) {
    }
}
