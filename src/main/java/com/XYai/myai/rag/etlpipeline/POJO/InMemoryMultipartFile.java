package com.XYai.myai.rag.etlpipeline.POJO;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;

/**
 * 小文件的内存实现：把内容保存在 byte[] 中，适合 <= 阈值 的场景。
 */
@RequiredArgsConstructor
@Getter
public class InMemoryMultipartFile implements MultipartFile {

    // 表单字段名
    @NonNull
    private final String name;

    // 原始文件名
    private final String originalFilename;

    // 内容类型（mime）
    private final String contentType;

    // 文件内容，非空（@NonNull 会在构造器中生成检查）
    @NonNull
    private final byte[] content;

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content == null || content.length == 0;
    }

    @Override
    public long getSize() {
        return content == null ? 0L : content.length;
    }

    @Override
    public byte[] getBytes() {
        return content == null ? new byte[0] : content.clone();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content == null ? new byte[0] : content);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        if (dest == null) throw new IllegalArgumentException("Destination file is null");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(getBytes());
            fos.flush();
        }
    }
}
