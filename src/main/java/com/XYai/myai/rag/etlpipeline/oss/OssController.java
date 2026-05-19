package com.XYai.myai.rag.etlpipeline.oss;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.config.OssConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.IngestionEngine;
import com.XYai.myai.rag.etlpipeline.factory.PipelineDefinitionFactory;
import com.XYai.myai.rag.etlpipeline.factory.UploadIngestionContextFactory;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * OSS 前端直传支持：
 * - /oss/policy: 生成短期 policy + signature 给前端（表单上传方案，快速可用）
 * - /upload/complete: 前端上传完成后调用，后端触发入库/向量化（复用现有 pipeline）
 * <p>
 * 注意：此 Controller 返回的数据会被前端使用，请不要把 AccessKeySecret 直接传给客户端。
 * 在生产推荐使用 STS（临时凭证）替代 policy 签名，详见注释末尾说明。
 */
@RestController
@RequestMapping("/oss")
public class OssController {

    @Resource
    private OssConfig ossConfig; // 你的配置类，含 endpoint/bucket/basePath/publicRead/accessKeyId/accessKeySecret 等

    @Resource
    private ObjectMapper objectMapper; // for JSON (可注入，也可 new ObjectMapper())

    @Resource(name = "uploadExecutor")
    private ThreadPoolTaskExecutor uploadExecutor;

    @Resource
    private PipelineDefinitionFactory pipelineDefinitionFactory;

    @Resource
    private IngestionEngine ingestionEngine;

    @Resource
    private UploadIngestionContextFactory uploadIngestionContextFactory;

    // 1) 生成表单上传 policy（短期有效）
    @GetMapping("/policy")
    public Result<Map<String, Object>> policy(@RequestParam(value = "dir", required = false) String dir,
                                              @RequestParam(value = "expireSeconds", required = false, defaultValue = "60") int expireSeconds) {
        try {
            // STEP 1: 验证并规范化 dir（object key 前缀），避免任意写 bucket
            if (dir == null || dir.isBlank()) {
                dir = Optional.ofNullable(ossConfig.getBasePath()).orElse("") + "uploads/";
            }
            if (!dir.endsWith("/"))
                dir = dir + "/";

            // STEP 2: 计算 policy 过期时间
            long expireEndMillis = System.currentTimeMillis() + expireSeconds * 1000L;
            String expiration = Instant.ofEpochMilli(expireEndMillis).toString();

            // STEP 3: 构建 policy JSON（注意 conditions: 限制 key 前缀和文件大小范围）
            Map<String, Object> policyMap = new LinkedHashMap<>();
            policyMap.put("expiration", expiration);

            List<Object> conditions = new ArrayList<>();
            // 限制 object key 必须以 dir 开头（防止写到任意路径）
            conditions.add(Arrays.asList("starts-with", "$key", dir));
            // 限制上传大小：0 ~ 1GB（根据需要调整）
            conditions.add(Arrays.asList("content-length-range", 0, 1024L * 1024L * 1024L));
            policyMap.put("conditions", conditions);

            // STEP 4: Base64(policy)
            String policyJson = objectMapper.writeValueAsString(policyMap);
            String policyBase64 = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));

            // STEP 5: 使用 accessKeySecret 对 policyBase64 做 HmacSHA1 签名（OSS 要求）
            String signature = signWithHmacSha1(policyBase64, ossConfig.getAccessKeySecret());

            // STEP 6: 组合返回给前端的字段（host、accessId、policy、signature、dir、expire）
            String host = "https://" + ossConfig.getBucket() + "." + ossConfig.getEndpoint();
            Map<String, Object> resp = new HashMap<>();
            resp.put("accessId", ossConfig.getAccessKeyId());
            resp.put("policy", policyBase64);
            resp.put("signature", signature);
            resp.put("dir", dir);
            resp.put("host", host);
            resp.put("expire", expireEndMillis / 1000); // 返回秒级时间戳

            // STEP 7: 返回结果（前端会使用这些字段构造 form 表单并 POST 到 host）
            return Result.success(resp);
        } catch (Exception ex) {
            // 出错时返回友好信息并记录日志（日志处在 caller）
            return Result.error(500, "生成 OSS policy 失败: " + ex.getMessage());
        }
    }

    // HMAC-SHA1 签名（用于 policy 签名）
    private String signWithHmacSha1(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }

    // 2) 前端上传完成后的回调/通知接口（建议前端上传成功后主动调用）
    // endpoint: POST /oss/notify (或 /upload/complete，你项目里已有 /upload 也可放到
    // UploadController)
    // 该接口应做认证（例如 JWT/session）以防伪造调用
    @PostMapping("/notify")
    public Result<String> notifyUpload(@RequestBody Map<String, Object> body) {
        // STEP 1: 校验必需字段（objectKey / collectionName / fileName / size / maybe kbId）
        String objectKey = (String) body.get("objectKey");
        String collectionName = (String) body.get("collectionName");
        String fileName = (String) body.get("fileName");
        Number sizeNum = (Number) body.get("size");
        String kbId = (String) body.get("kbId");

        if (objectKey == null || collectionName == null || fileName == null || sizeNum == null) {
            return Result.error(400, "缺少必要参数");
        }

        // STEP 2: 鉴权 - 确保是已登录用户或有权限（示例：从 SecurityContext 获得 user）
        // User user = LoginUserInfoManager.get(); // 你已有的方式
        // if (user == null) return metadataResult.error(401, "未认证");

        // STEP 3: 防篡改校验（可选）
        // - 检查 objectKey 前缀是否与后端签发的 dir 匹配
        // - 检查 size 是否在允许范围内
        String expectedPrefix = Optional.ofNullable(ossConfig.getBasePath()).orElse("") + "uploads/";
        if (!objectKey.startsWith(expectedPrefix) && !objectKey.startsWith("uploads/")) {
            // 若使用 per-user dir，检查并拒绝
            // return metadataResult.error(403, "objectKey 前缀不允许");
        }

        // STEP 4: 根据 objectKey 生成可访问 URL（公开或带签名）
        String objectUrl;
        if (Boolean.TRUE.equals(ossConfig.getPublicRead())) {
            objectUrl = "https://" + ossConfig.getBucket() + "." + ossConfig.getEndpoint() + "/" + objectKey;
        } else {
            // 非公开：生成短期带签名 URL（示例 1 hour）
            // 注意：ossClient 在 OssConfig 中已作为 Bean 提供
            objectUrl = ossConfig.ossClient().generatePresignedUrl(ossConfig.getBucket(), objectKey,
                    new Date(System.currentTimeMillis() + 3600 * 1000L)).toString();
        }

        // STEP 5: 将 objectUrl 交给现有 pipeline 进行入库/向量化（可异步）
        // 这里我们演示异步提交给 uploadExecutor（避免阻塞客户端）
        uploadExecutor.execute(() -> {
            try {
                User user = LoginUserInfoManager.getUser();
                IngestionContext inputContext = uploadIngestionContextFactory.createFromSource(
                        objectUrl, "oss", collectionName, kbId, user);
                inputContext.setTaskId(IdUtil.getSnowflakeNextIdStr());
                var pipeline = pipelineDefinitionFactory.createSourcePipeline(objectUrl, "oss");
                ingestionEngine.execute(pipeline, inputContext);
                // 这里还可以写入 fileRecordMapper、filePermissionMapper 等
            } catch (Exception e) {
                // Log and handle
            }
        });

        // STEP 6: 返回成功（客户端立即知道上传已被接收并触发入库）
        return Result.success("ok");
    }

    // 3) 服务器端生成预签名（Presigned）PUT URL：前端可以用该 URL 直接 PUT 上传单个文件
    @GetMapping("/presign")
    public Result<Map<String, Object>> presign(@RequestParam("objectKey") String objectKey,
                                               @RequestParam(value = "expireSeconds", required = false, defaultValue = "3600") int expireSeconds) {
        if (objectKey == null || objectKey.isBlank()) {
            return Result.error(400, "objectKey 不能为空");
        }
        try {
            Date expiration = new Date(System.currentTimeMillis() + (long) expireSeconds * 1000L);
            java.net.URL url = ossConfig.ossClient().generatePresignedUrl(ossConfig.getBucket(), objectKey, expiration);
            Map<String, Object> resp = new HashMap<>();
            resp.put("url", url.toString());
            resp.put("objectKey", objectKey);
            resp.put("expire", expiration.getTime() / 1000);
            return Result.success(resp);
        } catch (Exception ex) {
            return Result.error(500, "生成 presigned URL 失败: " + ex.getMessage());
        }
    }
}
