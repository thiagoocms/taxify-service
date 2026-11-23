package br.com.cshub.taxify.integration.fiscalxml;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalXmlPackageExtractorTest {

    private final FiscalXmlPackageExtractor extractor = new FiscalXmlPackageExtractor();

    @Test
    void treatsANonZipBodyAsASingleXml() {
        String xml = "<nfeProc></nfeProc>";

        List<byte[]> result = extractor.extract(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result).hasSize(1);
        assertThat(new String(result.get(0), StandardCharsets.UTF_8)).isEqualTo(xml);
    }

    @Test
    void unzipsAZipBodyAndKeepsOnlyXmlEntries() throws Exception {
        String xmlOne = "<nfeProc>1</nfeProc>";
        String xmlTwo = "<nfeProc>2</nfeProc>";

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("one.xml"));
            zos.write(xmlOne.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("ignore me".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("two.xml"));
            zos.write(xmlTwo.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        List<byte[]> result = extractor.extract(zipBytes.toByteArray());

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(b -> new String(b, StandardCharsets.UTF_8)))
                .containsExactlyInAnyOrder(xmlOne, xmlTwo);
    }

    @Test
    void returnsEmptyListForNullInput() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void returnsEmptyListForEmptyInput() {
        assertThat(extractor.extract(new byte[0])).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void streamsTheZipOneXmlAtATimeAndSkipsAnOversizedEntry() throws Exception {
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(zip)) {
            for (int i = 0; i < 250; i++) {
                out.putNextEntry(new java.util.zip.ZipEntry("nota-" + i + ".xml"));
                out.write(("<nfeProc>" + i + "</nfeProc>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new java.util.zip.ZipEntry("enorme.xml"));
            out.write(new byte[FiscalXmlPackageExtractor.MAX_XML_BYTES + 1]);
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("leia-me.txt"));
            out.write("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        java.util.List<Integer> sizes = new java.util.ArrayList<>();

        int count = extractor.forEachXml(new java.io.ByteArrayInputStream(zip.toByteArray()), xml -> sizes.add(xml.length));

        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(250);
        org.assertj.core.api.Assertions.assertThat(sizes).hasSize(250).allMatch(size -> size < 100);
    }

    @org.junit.jupiter.api.Test
    void aPlainXmlStreamIsHandedOverWhole() throws Exception {
        java.util.List<String> xmls = new java.util.ArrayList<>();

        int count = extractor.forEachXml(new java.io.ByteArrayInputStream("<nfeProc/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                xml -> xmls.add(new String(xml, java.nio.charset.StandardCharsets.UTF_8)));

        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(xmls).containsExactly("<nfeProc/>");
        org.assertj.core.api.Assertions.assertThat(extractor.forEachXml(new java.io.ByteArrayInputStream(new byte[0]), xml -> { })).isZero();
    }
}
