package br.com.cshub.taxify.dto.fiscalrule;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * A product's fiscal rule. Besides CFOP/NCM, every tax field is optional - empty means
 * "don't check it". {@code aliquotaIcms} is the internal-operation rate; rates in percent.
 */
public record ProductFiscalRuleDTO(
        String id,
        @NotBlank
        String companyId,
        @NotBlank
        String codigoProduto,
        String descricaoProduto,
        String ncmCorreto,
        @NotBlank
        String cfopCorreto,
        String observacoes,
        Boolean deleted,
        @Pattern(regexp = "(|[0-8])", message = "deve ser um dígito de 0 a 8")
        String origemMercadoria,
        @Pattern(regexp = "(|\\d{2})", message = "deve ter 2 dígitos")
        String cstIcms,
        @Pattern(regexp = "(|\\d{3})", message = "deve ter 3 dígitos")
        String csosn,
        @DecimalMin(value = "0", message = "não pode ser negativa") @DecimalMax(value = "100", message = "não pode passar de 100")
        BigDecimal aliquotaIcms,
        @Pattern(regexp = "(|\\d{7})", message = "deve ter 7 dígitos")
        String cest,
        @Pattern(regexp = "(|\\d{2})", message = "deve ter 2 dígitos")
        String cstIpi,
        @DecimalMin(value = "0", message = "não pode ser negativa") @DecimalMax(value = "100", message = "não pode passar de 100")
        BigDecimal aliquotaIpi,
        @Pattern(regexp = "(|\\d{2})", message = "deve ter 2 dígitos")
        String cstPis,
        @DecimalMin(value = "0", message = "não pode ser negativa") @DecimalMax(value = "100", message = "não pode passar de 100")
        BigDecimal aliquotaPis,
        @Pattern(regexp = "(|\\d{2})", message = "deve ter 2 dígitos")
        String cstCofins,
        @DecimalMin(value = "0", message = "não pode ser negativa") @DecimalMax(value = "100", message = "não pode passar de 100")
        BigDecimal aliquotaCofins,
        @Pattern(regexp = "(|\\d{3})", message = "deve ter 3 dígitos")
        String cstIbsCbs,
        @Pattern(regexp = "(|\\d{6})", message = "deve ter 6 dígitos")
        String classificacaoTributaria
) {
}
