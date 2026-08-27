package com.liwx.aiassistant.common;

import com.liwx.aiassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FileValidator 单元测试
 * 不启动 Spring 容器，用 Mockito 模拟 MultipartFile，纯逻辑验证，速度快
 */
class FileValidatorTest {

    private MultipartFile mockFile(String filename, long size, boolean isEmpty) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getSize()).thenReturn(size);
        when(file.isEmpty()).thenReturn(isEmpty);
        return file;
    }

    /** 正常 PDF 文件 → 返回扩展名 ".pdf" */
    @Test
    void shouldReturnExtensionWhenValidPdf() {
        MultipartFile file = mockFile("手册.pdf", 1024, false);

        String ext = FileValidator.validate(file, "pdf,txt,doc,docx,md", 50);

        assertEquals(".pdf", ext);
    }

    /** 正常 DOCX 文件 → 扩展名转小写 */
    @Test
    void shouldReturnLowercaseExtWhenUppercaseFilename() {
        MultipartFile file = mockFile("REPORT.DOCX", 1024, false);

        String ext = FileValidator.validate(file, "pdf,txt,doc,docx,md", 50);

        assertEquals(".docx", ext);
    }

    /** 空文件 → 抛 BusinessException */
    @Test
    void shouldThrowWhenFileIsEmpty() {
        MultipartFile file = mockFile("test.pdf", 0, true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(file, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("文件不能为空"));
    }

    /** file 为 null → 抛 BusinessException */
    @Test
    void shouldThrowWhenFileIsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(null, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("文件不能为空"));
    }

    /** 不支持的格式（.exe）→ 抛 BusinessException */
    @Test
    void shouldThrowWhenUnsupportedFormat() {
        MultipartFile file = mockFile("virus.exe", 1024, false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(file, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("不支持的文件格式"));
    }

    /** 文件大小超限 → 抛 BusinessException */
    @Test
    void shouldThrowWhenFileExceedsSizeLimit() {
        // 51MB，限制 50MB
        MultipartFile file = mockFile("big.pdf", 51L * 1024 * 1024, false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(file, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("文件大小超过"));
    }

    /** 文件名没有扩展名 → 抛 BusinessException */
    @Test
    void shouldThrowWhenFilenameHasNoExtension() {
        MultipartFile file = mockFile("noextension", 1024, false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(file, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("文件名不合法"));
    }

    /** 文件名为 null → 抛 BusinessException */
    @Test
    void shouldThrowWhenFilenameIsNull() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileValidator.validate(file, "pdf,txt,doc,docx,md", 50));
        assertTrue(ex.getMessage().contains("文件名不合法"));
    }

    /** 恰好等于大小限制（50MB）→ 通过（边界值） */
    @Test
    void shouldPassWhenFileExactlyAtSizeLimit() {
        MultipartFile file = mockFile("exact.pdf", 50L * 1024 * 1024, false);

        String ext = FileValidator.validate(file, "pdf,txt,doc,docx,md", 50);

        assertEquals(".pdf", ext);
    }
}
