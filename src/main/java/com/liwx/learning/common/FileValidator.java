package com.liwx.learning.common;

import com.liwx.learning.exception.BusinessException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传校验工具类：空文件、格式、大小
 * 用法：FileValidator.validate(file, "pdf,txt,doc,docx,md", 50);
 */
public class FileValidator {

    private static final long MB = 1024 * 1024;

    /**
     * 校验上传文件，不通过直接抛 BusinessException
     *
     * @param file          上传的文件
     * @param allowedExts   允许的扩展名（逗号分隔），如 "pdf,txt,doc,docx,md"
     * @param maxSizeInMB   最大文件大小（MB）
     * @return 文件扩展名（含点号，如 ".pdf"）
     */
    public static String validate(MultipartFile file, String allowedExts, int maxSizeInMB) {
        // 空文件
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件不能为空");
        }
        // 文件名
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "文件名不合法");
        }
        // 格式
        String ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        if (!ext.matches("\\.(" + allowedExts.replace(",", "|") + ")")) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "不支持的文件格式，仅支持 " + allowedExts.toUpperCase().replace(",", "/"));
        }
        // 大小
        if (file.getSize() > maxSizeInMB * MB) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "文件大小超过 " + maxSizeInMB + "MB 限制");
        }
        return ext;
    }
}
