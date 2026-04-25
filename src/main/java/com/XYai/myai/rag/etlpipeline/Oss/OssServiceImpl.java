package com.XYai.myai.rag.etlpipeline.Oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OssServiceImpl implements OssService {

    private static final long DEFAULT_PART_SIZE = 10L * 1024L * 1024L; // 10MB (>=5MB)
    private static final int DEFAULT_PART_CONCURRENCY = 4;
    private static final int MAX_RETRY = 3;
    private final OSS ossClient;
    private final com.XYai.myai.config.OssConfig ossConfig;
    private final String bucket;
    private final ExecutorService partUploadExecutor;

    @Autowired
    public OssServiceImpl(OSS ossClient, com.XYai.myai.config.OssConfig ossConfig) {
        this.ossClient = ossClient;
        this.ossConfig = ossConfig;
        this.bucket = ossConfig.getBucket();
        this.partUploadExecutor = Executors.newFixedThreadPool(DEFAULT_PART_CONCURRENCY);
    }

    @Override
    public String upload(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return upload(in, file.getSize(), file.getOriginalFilename(), file.getContentType());
        }
    }

    @Override
    public String upload(File file, String objectKey) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return upload(in, file.length(), objectKey, null);
        }
    }

    @Override
    public String upload(InputStream in, long size, String objectKey, String contentType) throws IOException {
        if (size <= DEFAULT_PART_SIZE) {
            ObjectMetadata meta = new ObjectMetadata();
            if (contentType != null) meta.setContentType(contentType);
            meta.setContentLength(size);
            PutObjectRequest putReq = new PutObjectRequest(bucket, objectKey, in, meta);
            PutObjectResult putRes = ossClient.putObject(putReq);
            // 返回文件 URL（可按需要生成 OSS 域名组合或 presigned）
            return buildUrl(objectKey);
        } else {
            return multipartUpload(in, size, objectKey, contentType);
        }
    }

    @Override
    public String multipartUpload(InputStream in, long size, String objectKey, String contentType) throws IOException {
        long partSize = DEFAULT_PART_SIZE; // 可配置
        int partCount = (int) ((size + partSize - 1) / partSize);
        if (partCount > 10000) {
            throw new IllegalArgumentException("too many parts: " + partCount);
        }

        // 1. Initiate
        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucket, objectKey);
        if (contentType != null) {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(contentType);
            initRequest.setObjectMetadata(meta);
        }
        InitiateMultipartUploadResult initResult = ossClient.initiateMultipartUpload(initRequest);
        String uploadId = initResult.getUploadId();

        List<PartETag> partETags = Collections.synchronizedList(new ArrayList<>(partCount));
        List<Future<Void>> futures = new ArrayList<>(partCount);

        byte[] buffer = new byte[(int) partSize];
        int partNumber = 1;
        try {
            while (partNumber <= partCount) {
                // read up to partSize
                int read = 0;
                int toRead = (int) Math.min(partSize, size - (long) (partNumber - 1) * partSize);
                int offset = 0;
                while (offset < toRead) {
                    int n = in.read(buffer, offset, toRead - offset);
                    if (n < 0) break;
                    offset += n;
                }
                if (offset <= 0) break;
                byte[] partData = Arrays.copyOf(buffer, offset);

                final int currentPartNumber = partNumber;
                Callable<Void> task = () -> {
                    int attempt = 0;
                    while (true) {
                        try {
                            UploadPartRequest uploadPartRequest = new UploadPartRequest();
                            uploadPartRequest.setBucketName(bucket);
                            uploadPartRequest.setKey(objectKey);
                            uploadPartRequest.setUploadId(uploadId);
                            uploadPartRequest.setInputStream(new ByteArrayInputStream(partData));
                            uploadPartRequest.setPartSize(partData.length);
                            uploadPartRequest.setPartNumber(currentPartNumber);
                            UploadPartResult uploadPartResult = ossClient.uploadPart(uploadPartRequest);
                            partETags.add(uploadPartResult.getPartETag());
                            break;
                        } catch (Exception ex) {
                            attempt++;
                            if (attempt > MAX_RETRY) throw ex;
                            // backoff
                            Thread.sleep(50L * attempt);
                        }
                    }
                    return null;
                };

                futures.add(partUploadExecutor.submit(task));
                partNumber++;
            }

            // wait for all parts
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    throw new IOException("upload part failed", ee.getCause());
                }
            }

            // Complete
            partETags.sort(Comparator.comparingInt(PartETag::getPartNumber));
            CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(bucket, objectKey, uploadId, partETags);
            CompleteMultipartUploadResult completeResult = ossClient.completeMultipartUpload(completeRequest);
            return buildUrl(objectKey);
        } catch (Exception e) {
            // on error abort multipart
            try {
                ossClient.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, objectKey, uploadId));
            } catch (Exception ignored) {
            }
            throw new IOException("Multipart upload failed", e);
        }
    }

    private String buildUrl(String objectKey) {
        // 使用 OssConfig 中的 endpoint 构建 URL
        String endpointHost = Optional.ofNullable(ossConfig.getEndpoint()).orElse("oss-cn-XXXX.aliyuncs.com");
        return String.format("https://%s.%s/%s", bucket, endpointHost, objectKey);
    }

    // shutdown executor on destroy (implement DisposableBean if needed)
}
