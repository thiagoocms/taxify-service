package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDestinatarioDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEmitenteDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlEnderecoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlIdentificacaoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotaisDTO;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds a DANFE-style PDF (Documento Auxiliar da NF-e) from what's already parsed out of
 * the stored XML (FiscalXmlQueryService#findDetail / #findProdutos) - so it goes through
 * the same tenant access check as the XML itself. It's a visual rendering of the note for
 * reading/printing; the authorization protocol isn't stored, so that field is left out.
 */
@Service
public class DanfePdfService {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final Font LABEL = new Font(Font.HELVETICA, 5.5f, Font.NORMAL, Color.DARK_GRAY);
    private static final Font VALUE = new Font(Font.HELVETICA, 7.5f, Font.NORMAL);
    private static final Font VALUE_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD);
    private static final Font SECTION = new Font(Font.HELVETICA, 6.5f, Font.BOLD);
    private static final Font ITEM = new Font(Font.HELVETICA, 6f, Font.NORMAL);
    private static final Font ITEM_HEADER = new Font(Font.HELVETICA, 5.5f, Font.BOLD);
    private static final Font TITLE = new Font(Font.HELVETICA, 12f, Font.BOLD);
    private static final Font ISSUER = new Font(Font.HELVETICA, 9f, Font.BOLD);

    private final FiscalXmlQueryService queryService;

    public DanfePdfService(FiscalXmlQueryService queryService) {
        this.queryService = queryService;
    }

    public record DanfePdf(String fileName, byte[] content) {
    }

    public DanfePdf generate(UUID documentId) {
        FiscalXmlDetailDTO detail = queryService.findDetail(documentId);
        List<FiscalXmlProdutoDTO> produtos = queryService.findProdutos(documentId);
        String baseName = StringUtils.hasText(detail.accessKey()) ? detail.accessKey() : detail.id().toString();
        return new DanfePdf(baseName + ".pdf", render(detail, produtos));
    }

    byte[] render(FiscalXmlDetailDTO detail, List<FiscalXmlProdutoDTO> produtos) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 18, 18, 18, 18);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        FiscalXmlIdentificacaoDTO ide = detail.identificacao();
        if (ide != null && "2".equals(ide.ambiente())) {
            writer.setPageEvent(new Watermark("SEM VALOR FISCAL - HOMOLOGAÇÃO"));
        }
        document.addTitle(titleOf(detail) + " " + safe(ide == null ? null : ide.numero()));
        document.open();

        document.add(header(detail, writer));
        document.add(naturezaOperacao(detail));
        document.add(sectionTitle("DESTINATÁRIO / REMETENTE"));
        document.add(destinatario(detail));
        document.add(sectionTitle("CÁLCULO DO IMPOSTO"));
        document.add(totais(detail.totais()));
        document.add(sectionTitle("DADOS DOS PRODUTOS / SERVIÇOS"));
        document.add(itens(produtos));
        document.add(sectionTitle("DADOS ADICIONAIS"));
        document.add(dadosAdicionais(detail));

        document.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- blocks

    private PdfPTable header(FiscalXmlDetailDTO detail, PdfWriter writer) {
        FiscalXmlEmitenteDTO emit = detail.emitente();
        FiscalXmlIdentificacaoDTO ide = detail.identificacao();

        PdfPTable table = table(new float[]{40, 18, 42});

        PdfPCell issuer = new PdfPCell();
        issuer.setPadding(4);
        issuer.setBorderWidth(0.5f);
        issuer.addElement(new Paragraph(emit == null ? "" : safe(emit.nome()), ISSUER));
        if (emit != null && emit.endereco() != null) {
            FiscalXmlEnderecoDTO e = emit.endereco();
            issuer.addElement(new Paragraph(join(", ", e.logradouro(), e.numero(), e.complemento()), VALUE));
            issuer.addElement(new Paragraph(join(" - ", e.bairro(), formatCep(e.cep())), VALUE));
            issuer.addElement(new Paragraph(join(" / ", e.municipio(), e.uf()) + (StringUtils.hasText(e.telefone()) ? "  Fone: " + e.telefone() : ""), VALUE));
        }
        table.addCell(issuer);

        PdfPCell danfe = new PdfPCell();
        danfe.setBorderWidth(0.5f);
        danfe.setPadding(3);
        Paragraph title = new Paragraph(titleOf(detail), TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        danfe.addElement(title);
        Paragraph subtitle = new Paragraph("Documento Auxiliar da\nNota Fiscal Eletrônica", LABEL);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        danfe.addElement(subtitle);
        String tipo = ide == null ? "" : safe(ide.tipoOperacao());
        Paragraph es = new Paragraph("0 - ENTRADA\n1 - SAÍDA      [ " + tipo + " ]", VALUE);
        es.setAlignment(Element.ALIGN_CENTER);
        danfe.addElement(es);
        Paragraph num = new Paragraph("Nº " + formatNumero(ide == null ? null : ide.numero())
                + "\nSÉRIE " + (ide == null ? "" : safe(ide.serie())), VALUE_BOLD);
        num.setAlignment(Element.ALIGN_CENTER);
        danfe.addElement(num);
        table.addCell(danfe);

        PdfPCell key = new PdfPCell();
        key.setBorderWidth(0.5f);
        key.setPadding(4);
        if (StringUtils.hasText(detail.accessKey())) {
            Barcode128 barcode = new Barcode128();
            barcode.setCodeType(Barcode128.CODE_C);
            barcode.setCode(detail.accessKey());
            barcode.setFont(null);
            barcode.setBarHeight(30);
            Image image = barcode.createImageWithBarcode(writer.getDirectContent(), Color.BLACK, Color.BLACK);
            image.scaleToFit(220, 32);
            image.setAlignment(Element.ALIGN_CENTER);
            key.addElement(image);
        }
        key.addElement(new Paragraph("CHAVE DE ACESSO", LABEL));
        Paragraph keyValue = new Paragraph(formatAccessKey(detail.accessKey()), VALUE_BOLD);
        keyValue.setAlignment(Element.ALIGN_CENTER);
        key.addElement(keyValue);
        Paragraph consulta = new Paragraph("Consulta de autenticidade no portal nacional da NF-e\nwww.nfe.fazenda.gov.br/portal ou no site da Sefaz autorizadora", LABEL);
        consulta.setAlignment(Element.ALIGN_CENTER);
        key.addElement(consulta);
        table.addCell(key);

        return table;
    }

    private PdfPTable naturezaOperacao(FiscalXmlDetailDTO detail) {
        FiscalXmlEmitenteDTO emit = detail.emitente();
        FiscalXmlIdentificacaoDTO ide = detail.identificacao();
        PdfPTable table = table(new float[]{100});
        table.addCell(field("NATUREZA DA OPERAÇÃO", ide == null ? null : ide.naturezaOperacao()));
        PdfPTable row = table(new float[]{25, 25, 25, 25});
        row.addCell(field("INSCRIÇÃO ESTADUAL", emit == null ? null : emit.inscricaoEstadual()));
        row.addCell(field("INSC. ESTADUAL DO SUBST. TRIB.", emit == null ? null : emit.inscricaoEstadualSubstitutoTributario()));
        row.addCell(field("INSCRIÇÃO MUNICIPAL", emit == null ? null : emit.inscricaoMunicipal()));
        row.addCell(field(emit != null && StringUtils.hasText(emit.cpf()) && !StringUtils.hasText(emit.cnpj()) ? "CPF" : "CNPJ",
                DocumentNumbers.format(emit != null ? firstNotBlank(emit.cnpj(), emit.cpf()) : detail.issuerCnpj())));
        PdfPCell wrapper = new PdfPCell(row);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(0);
        table.addCell(wrapper);
        return table;
    }

    private PdfPTable destinatario(FiscalXmlDetailDTO detail) {
        FiscalXmlDestinatarioDTO dest = detail.destinatario();
        FiscalXmlIdentificacaoDTO ide = detail.identificacao();
        FiscalXmlEnderecoDTO e = dest == null ? null : dest.endereco();
        LocalDateTime emissao = ide != null && ide.dataEmissao() != null ? ide.dataEmissao() : detail.issueDate();

        PdfPTable table = table(new float[]{52, 18, 15, 15});
        table.addCell(field("NOME / RAZÃO SOCIAL", dest == null ? null : dest.nome()));
        table.addCell(field(dest != null && StringUtils.hasText(dest.cpf()) && !StringUtils.hasText(dest.cnpj()) ? "CPF" : "CNPJ / CPF",
                dest == null ? DocumentNumbers.format(detail.recipientCnpj())
                        : firstNotBlank(DocumentNumbers.format(firstNotBlank(dest.cnpj(), dest.cpf())), dest.idEstrangeiro())));
        table.addCell(field("DATA DA EMISSÃO", emissao == null ? null : emissao.format(DATE)));
        table.addCell(field("DATA SAÍDA/ENTRADA", ide == null || ide.dataSaidaEntrada() == null ? null : ide.dataSaidaEntrada().format(DATE)));

        table.addCell(field("ENDEREÇO", e == null ? null : join(", ", e.logradouro(), e.numero(), e.complemento())));
        table.addCell(field("BAIRRO / DISTRITO", e == null ? null : e.bairro()));
        table.addCell(field("CEP", e == null ? null : formatCep(e.cep())));
        table.addCell(field("HORA SAÍDA/ENTRADA", ide == null || ide.dataSaidaEntrada() == null ? null
                : ide.dataSaidaEntrada().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));

        table.addCell(field("MUNICÍPIO", e == null ? null : e.municipio()));
        table.addCell(field("FONE / FAX", e == null ? null : e.telefone()));
        table.addCell(field("UF", e == null ? null : e.uf()));
        table.addCell(field("INSCRIÇÃO ESTADUAL", dest == null ? null : dest.inscricaoEstadual()));
        return table;
    }

    private PdfPTable totais(FiscalXmlTotaisDTO t) {
        PdfPTable table = table(new float[]{1, 1, 1, 1, 1, 1});
        if (t == null) {
            PdfPCell empty = field("", "Totais não disponíveis");
            empty.setColspan(6);
            table.addCell(empty);
            return table;
        }
        table.addCell(money("BASE DE CÁLC. DO ICMS", t.baseCalculoIcms()));
        table.addCell(money("VALOR DO ICMS", t.valorIcms()));
        table.addCell(money("BASE DE CÁLC. ICMS S.T.", t.baseCalculoIcmsSt()));
        table.addCell(money("VALOR DO ICMS SUBST.", t.valorIcmsSt()));
        table.addCell(money("V. APROX. TRIBUTOS", t.valorAproximadoTributos()));
        table.addCell(money("V. TOTAL PRODUTOS", t.valorProdutos()));

        table.addCell(money("VALOR DO FRETE", t.valorFrete()));
        table.addCell(money("VALOR DO SEGURO", t.valorSeguro()));
        table.addCell(money("DESCONTO", t.valorDesconto()));
        table.addCell(money("OUTRAS DESPESAS", t.valorOutrasDespesas()));
        table.addCell(money("VALOR TOTAL IPI", t.valorIpi()));
        PdfPCell total = field("V. TOTAL DA NOTA", formatMoney(t.valorTotalNota()));
        ((Paragraph) total.getCompositeElements().get(1)).getFont().setStyle(Font.BOLD);
        table.addCell(total);
        return table;
    }

    private PdfPTable itens(List<FiscalXmlProdutoDTO> produtos) {
        String[] headers = {"CÓDIGO", "DESCRIÇÃO DO PRODUTO / SERVIÇO", "NCM/SH", "CST", "CFOP", "UN", "QUANT.",
                "VALOR UNIT.", "VALOR TOTAL", "B.CÁLC. ICMS", "VALOR ICMS", "VALOR IPI", "ALÍQ. ICMS"};
        PdfPTable table = table(new float[]{8, 26, 6.5f, 4, 4.5f, 3.5f, 6.5f, 7, 7, 7, 6.5f, 6, 4.5f});
        table.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, ITEM_HEADER));
            cell.setBorderWidth(0.5f);
            cell.setBackgroundColor(new Color(235, 235, 235));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(2);
            table.addCell(cell);
        }
        if (produtos.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Nenhum item encontrado.", ITEM));
            empty.setColspan(headers.length);
            empty.setPadding(4);
            empty.setBorderWidth(0.5f);
            table.addCell(empty);
            return table;
        }
        for (FiscalXmlProdutoDTO p : produtos) {
            FiscalXmlImpostoItemDTO imp = p.impostos();
            item(table, p.codigo(), Element.ALIGN_LEFT);
            item(table, p.descricao(), Element.ALIGN_LEFT);
            item(table, p.ncm(), Element.ALIGN_CENTER);
            item(table, imp == null ? null : imp.situacaoTributariaIcms(), Element.ALIGN_CENTER);
            item(table, p.cfop(), Element.ALIGN_CENTER);
            item(table, p.unidadeComercial(), Element.ALIGN_CENTER);
            item(table, formatDecimal(p.quantidadeComercial(), "#,##0.####"), Element.ALIGN_RIGHT);
            item(table, formatDecimal(p.valorUnitarioComercial(), "#,##0.00##"), Element.ALIGN_RIGHT);
            item(table, formatMoney(p.valorTotal()), Element.ALIGN_RIGHT);
            item(table, imp == null ? null : formatMoney(imp.baseCalculoIcms()), Element.ALIGN_RIGHT);
            item(table, imp == null ? null : formatMoney(imp.valorIcms()), Element.ALIGN_RIGHT);
            item(table, imp == null ? null : formatMoney(imp.valorIpi()), Element.ALIGN_RIGHT);
            item(table, imp == null ? null : formatDecimal(imp.aliquotaIcms(), "#,##0.##"), Element.ALIGN_RIGHT);
        }
        return table;
    }

    private PdfPTable dadosAdicionais(FiscalXmlDetailDTO detail) {
        StringBuilder text = new StringBuilder();
        FiscalXmlTotaisDTO t = detail.totais();
        if (t != null && t.valorAproximadoTributos() != null && t.valorAproximadoTributos().signum() > 0) {
            text.append("Valor aproximado dos tributos: R$ ").append(formatMoney(t.valorAproximadoTributos())).append(". ");
        }
        if (detail.retificada()) {
            text.append("Existe uma versão retificada desta nota no Taxify; este documento reflete o XML original. ");
        }
        text.append("Documento gerado pelo Taxify a partir do XML armazenado em ").append(LocalDateTime.now().format(DATE_TIME)).append('.');
        PdfPTable table = table(new float[]{100});
        PdfPCell cell = field("INFORMAÇÕES COMPLEMENTARES", text.toString());
        cell.setMinimumHeight(40);
        table.addCell(cell);
        return table;
    }

    // ---------------------------------------------------------------- cells

    private static PdfPTable table(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setSpacingBefore(0);
        table.setSpacingAfter(0);
        return table;
    }

    private static Paragraph sectionTitle(String text) {
        Paragraph p = new Paragraph(text, SECTION);
        p.setSpacingBefore(5);
        p.setSpacingAfter(1);
        return p;
    }

    private static PdfPCell field(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderWidth(0.5f);
        cell.setPaddingTop(0);
        cell.setPaddingLeft(2);
        cell.setPaddingRight(2);
        cell.setPaddingBottom(3);
        Paragraph l = new Paragraph(label, LABEL);
        cell.addElement(l);
        Paragraph v = new Paragraph(StringUtils.hasText(value) ? value : " ", new Font(VALUE));
        cell.addElement(v);
        return cell;
    }

    private static PdfPCell money(String label, BigDecimal value) {
        PdfPCell cell = field(label, formatMoney(value));
        ((Paragraph) cell.getCompositeElements().get(1)).setAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static void item(PdfPTable table, String value, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), ITEM));
        cell.setBorderWidth(0.3f);
        cell.setPadding(2);
        cell.setHorizontalAlignment(alignment);
        table.addCell(cell);
    }

    /** Diagonal grey text on every page - homologação notes carry no fiscal value. */
    private static final class Watermark extends PdfPageEventHelper {
        private final String text;

        private Watermark(String text) {
            this.text = text;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContentUnder();
            PdfGState state = new PdfGState();
            state.setFillOpacity(0.15f);
            canvas.saveState();
            canvas.setGState(state);
            Font font = new Font(Font.HELVETICA, 40, Font.BOLD, Color.GRAY);
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase(new Chunk(text, font)),
                    page.getWidth() / 2, page.getHeight() / 2, 45);
            canvas.restoreState();
        }
    }

    // ---------------------------------------------------------------- formatting

    private static String titleOf(FiscalXmlDetailDTO detail) {
        if ("CTE".equals(detail.documentType())) {
            return "DACTE";
        }
        FiscalXmlIdentificacaoDTO ide = detail.identificacao();
        return ide != null && "65".equals(ide.modelo()) ? "DANFE NFC-e" : "DANFE";
    }

    private static String formatNumero(String numero) {
        if (!StringUtils.hasText(numero) || !numero.chars().allMatch(Character::isDigit)) {
            return safe(numero);
        }
        String padded = "0".repeat(Math.max(0, 9 - numero.length())) + numero;
        return padded.substring(0, 3) + "." + padded.substring(3, 6) + "." + padded.substring(6);
    }

    static String formatAccessKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String formatCep(String cep) {
        if (!StringUtils.hasText(cep)) {
            return null;
        }
        String d = cep.replaceAll("\\D", "");
        return d.length() == 8 ? d.substring(0, 5) + "-" + d.substring(5) : cep;
    }

    static String formatMoney(BigDecimal value) {
        return formatDecimal(value == null ? BigDecimal.ZERO : value, "#,##0.00");
    }

    private static String formatDecimal(BigDecimal value, String pattern) {
        if (value == null) {
            return "";
        }
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(PT_BR)).format(value);
    }

    private static String join(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    private static String firstNotBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
