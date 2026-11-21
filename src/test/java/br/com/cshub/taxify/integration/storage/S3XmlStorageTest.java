package br.com.cshub.taxify.integration.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3XmlStorageTest {

    private final S3Client client = mock(S3Client.class);
    private final S3XmlStorage storage = new S3XmlStorage(client, "taxify-xml", "/fiscal-xml/");

    @Test
    void writesUnderThePrefixEncryptedAndReturnsTheUri() throws IOException {
        String location = storage.write("company-1/NFe123.xml", "<nfe/>".getBytes(StandardCharsets.UTF_8));

        assertThat(location).isEqualTo("s3://taxify-xml/fiscal-xml/company-1/NFe123.xml");
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo("fiscal-xml/company-1/NFe123.xml");
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    void readsTheObjectBackAndAMissingOneIsNoSuchFile() throws IOException {
        when(client.getObjectAsBytes(GetObjectRequest.builder().bucket("taxify-xml").key("fiscal-xml/c/a.xml").build()))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "<a/>".getBytes(StandardCharsets.UTF_8)));
        when(client.getObjectAsBytes(GetObjectRequest.builder().bucket("taxify-xml").key("fiscal-xml/c/gone.xml").build()))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThat(new String(storage.read("s3://taxify-xml/fiscal-xml/c/a.xml"), StandardCharsets.UTF_8)).isEqualTo("<a/>");
        assertThatThrownBy(() -> storage.read("s3://taxify-xml/fiscal-xml/c/gone.xml")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void deletingAMissingObjectIsANoOp() throws IOException {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThat(storage.delete("s3://taxify-xml/fiscal-xml/c/gone.xml")).isFalse();
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void parsesLocationsAndRejectsOthers() throws IOException {
        assertThat(S3XmlStorage.parse("s3://b/k/x.xml")).isEqualTo(new S3XmlStorage.Location("b", "k/x.xml"));
        assertThatThrownBy(() -> S3XmlStorage.parse("/data/fiscal-xml/x.xml")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> S3XmlStorage.parse("s3://bucket-only")).isInstanceOf(IOException.class);
        assertThat(storage.handles("s3://b/k")).isTrue();
        assertThat(storage.handles("/data/x.xml")).isFalse();
    }

    @Test
    void aBucketIsRequired() {
        assertThatThrownBy(() -> new S3XmlStorage(client, " ", "p")).isInstanceOf(IllegalStateException.class);
    }
}
