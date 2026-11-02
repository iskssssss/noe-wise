package com.skillforge.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** Document parser format, decoding and upload-boundary coverage. */
class DocumentParserTest {

    @Test
    void parsesDirectTextFormatsCaseInsensitively() {
        DocumentParser.ParsedDocument txt = DocumentParser.parseBytes(
                "hello".getBytes(), "README.TXT");
        DocumentParser.ParsedDocument markdown = DocumentParser.parseBytes(
                "# title".getBytes(), "guide.markdown");

        assertThat(txt.content()).isEqualTo("hello");
        assertThat(txt.extension()).isEqualTo("txt");
        assertThat(txt.parsedBy()).isEqualTo("direct");
        assertThat(txt.sizeBytes()).isEqualTo(5L);
        assertThat(markdown.content()).isEqualTo("# title");
        assertThat(markdown.extension()).isEqualTo("markdown");
    }

    @Test
    void rejectsUnsupportedAndExtensionlessFiles() {
        assertThatThrownBy(() -> DocumentParser.parseBytes(new byte[0], "data.csv"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> DocumentParser.parseBytes(new byte[0], "README"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void parsesTikaFormatsAndMultipartUploads() throws Exception {
        assertThatThrownBy(() -> DocumentParser.parseBytes(new byte[0], "empty.pdf"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法解析文件: empty.pdf");

        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);
        when(file.getBytes()).thenReturn("upload".getBytes());
        assertThatThrownBy(() -> DocumentParser.parse(file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持的文件类型");

        MultipartFile valid = mock(MultipartFile.class);
        when(valid.getOriginalFilename()).thenReturn("upload.txt");
        when(valid.getBytes()).thenReturn("upload".getBytes());
        assertThat(DocumentParser.parse(valid).originalFilename()).isEqualTo("upload.txt");

        MultipartFile failing = mock(MultipartFile.class);
        when(failing.getOriginalFilename()).thenReturn("broken.txt");
        when(failing.getBytes()).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> DocumentParser.parse(failing))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法解析文件: broken.txt");
    }
}
