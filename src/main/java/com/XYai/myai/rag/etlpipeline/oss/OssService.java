package com.XYai.myai.rag.etlpipeline.oss;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public interface OssService {
    // 现有：String upload(MultipartFile file)
    String upload(MultipartFile file) throws IOException;

    // 新增：流式上传（自动根据 size 选择 single/multipart）
    String upload(InputStream in, long size, String objectKey, String contentType) throws IOException;

    // 新增：File 上传（封装为 File -> InputStream）
    String upload(File file, String objectKey) throws IOException;

    // 如需更底层：multipart upload with options
    String multipartUpload(InputStream in, long size, String objectKey, String contentType) throws IOException;
}
