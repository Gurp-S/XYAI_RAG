package com.XYai.myai.rag.etlpipeline.POJO;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/**
 * 大文件持久化实现：持有你自己创建的临时 File。
 */
@RequiredArgsConstructor
@Getter
public class FileBackedMultipartFile implements MultipartFile {

    // 表单字段名
    @NonNull
    private final String name;

    // 原始文件名
    private final String originalFilename;

    // 内容类型（mime）
    private final String contentType;

    // 持久化文件，不能为空
    @NonNull
    private final File file;

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
        return file == null || file.length() == 0;
    }

    @Override
    public long getSize() {
        return file == null ? 0L : file.length();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        if (dest == null) throw new IllegalArgumentException("Destination file is null");
        Files.copy(file.toPath(), dest.toPath());
    }
}
