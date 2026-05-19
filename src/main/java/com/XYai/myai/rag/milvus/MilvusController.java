package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import com.XYai.myai.user.userChat.UserChatService;
import com.XYai.myai.user.userChat.pojo.FileMessage;
import com.XYai.myai.user.userChat.pojo.UserChatRequest;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Milvus 向量数据库管理控制器。
 * 提供集合列表、元数据查询、搜索、创建、加载/卸载、删除、重建等接口。
 */
@Slf4j
@RestController
@RequestMapping("/milvus")
public class MilvusController {

    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource
    private MilvusCollectionService milvusCollectionService;
    @Resource
    private UserChatService userChatService;
    @Resource
    private UserMapper userMapper;

    /**
     * 查看数据库的数据(实际为redis下user的所有数据)
     *
     * @return 返回数据库集合列表
     */
    @GetMapping("/list")
    public Result<List<String>> listCollections() {
        return Result.success(milvusCollectionService.getAllCollectionNames());
    }

    /**
     * 获取集合数据
     *
     * @param collectionName 集合名称
     * @return 元数据列表
     */
    @PostMapping("/metadata")
    public Result<List<Map<String, Object>>> getCollectionsFiles(String collectionName) {
        //是否有权限（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("getCollectionsMetadataNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        if (!milvusAclManager.userCollectionLoadAcl(collectionName)) {
            return Result.error(1, "未加载");
        }
        return Result.success(milvusFileManager.getUserCollectionFiles(collectionName));
    }

    @GetMapping("/metadata/user")
    public Result<List<Map<String, Object>>> getUserFiles() {
        //是否有权限（若没有权限则返回错误）
        return Result.success(milvusFileManager.getUserFiles());
    }

    /**
     * 搜索集合
     *
     * @param str 搜索字符串
     * @return 匹配的集合名称列表
     */
    @PostMapping("/search")
    public Result<List<String>> search(String str) {//权限隔离
        List<String> searchCollectionNames = milvusCollectionService.search(str);
        return Result.success(searchCollectionNames);
    }

    /**
     * 创建集合（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/create")
    public Result<String> createCollection(String collectionName) {//增加权限
        milvusCollectionService.createCollectionIfAbsent(collectionName);
        return Result.success("集合创建成功");
    }

    /**
     * 获取集合状态（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 是否已加载
     */
    @PostMapping("/status")
    public Result<Boolean> getStatus(String collectionName) {
        //集合文件读取权力（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("getStatusNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        boolean isLoad = milvusCollectionService.isLoaded(collectionName);
        return Result.success(isLoad);
    }

    /**
     * 加载卸载集合
     *
     * @param collectionName 集合
     * @return 更新
     * @throws Exception 错误
     */
    @PostMapping("/loadOrunload")
    public Result<String> unloadOrLoadCollection(String collectionName) throws Exception {//权限隔离
        //读取权开关（若没有权限则返回错误）
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("unloadOrLoadCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        if (!milvusCollectionService.isLoaded(collectionName)) {
            milvusCollectionService.loadCollection(collectionName);
            log.info("loadCollectionName:{}", collectionName);
        } else {
            milvusCollectionService.unloadCollection(collectionName);
            log.info("unLoadCollectionName:{}", collectionName);
        }
        return Result.success();
    }

    /**
     * 删除集合（有参数，使用 POST）
     *
     * @param collectionName 集合名称
     * @return 结果
     */
    @PostMapping("/delete")
    public Result<String> dropCollection(@RequestParam String collectionName) {
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("dropCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        return milvusCollectionService.drop(collectionName);
    }

    @PostMapping("/delete/doc")
    public Result<String> dropFileChunk(
            @RequestParam Long chunkId,
            @RequestParam String fileId,
            @RequestParam String collectionName) {
        log.info("文件:{}集合:{}", fileId, collectionName);
        if (!milvusAclManager.getCollectionAcl(collectionName) || !milvusAclManager.getFileChunkAcl(fileId, Math.toIntExact(chunkId))) {
            log.info("dropDocumentNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        milvusFileManager.deleteDocument(chunkId, fileId);
        return Result.success("删除成功");
    }

    @PostMapping("/rebuild")
    public Result<String> rebuildCollection(@RequestParam String collectionName) {
        if (!milvusAclManager.getCollectionAcl(collectionName)) {
            log.info("rebuildCollectionNoACl:{}", collectionName);
            return Result.error(1, "无权限访问");
        }
        return milvusCollectionService.rebuild(collectionName);
    }

    /**
     * 分享文件的消息
     * @param collectionName
     * @param fileId
     * @param userId
     * @param chunkId
     * @return
     */
    @PostMapping("/share/message")
    public Result<FileMessage> shareFilesMessage(
            @RequestParam String collectionName,
            @RequestParam String fileId,
            @RequestParam Long userId,
            @RequestParam(required = false) Integer chunkId,
            @RequestParam(required = false) String fileName) {
        User sender = LoginUserInfoManager.getUser();
        if (sender == null || sender.getId() == null) {
            return Result.error(401, "未登录");
        }
        if (userId == null || userId <= 0) {
            return Result.error(400, "userId 不能为空");
        }
        if (sender.getId().equals(userId)) {
            return Result.error(400, "不能分享给自己");
        }
        if (userMapper.selectById(userId) == null) {
            return Result.error(404, "用户不存在");
        }
        Integer normalizedChunkId = chunkId != null && chunkId > 0 ? chunkId : null;
        String safeFileName = (fileName != null && !fileName.isBlank()) ? fileName.replace("\"", "\\\"").replace("\\", "\\\\") : "";
        String payload = "{\"type\":\"file_share\",\"collectionName\":\"" + collectionName
                + "\",\"fileId\":\"" + fileId + "\",\"chunkId\":"
                + (normalizedChunkId == null ? "null" : normalizedChunkId)
                + ",\"fileName\":\"" + safeFileName + "\"}";

        UserChatRequest request = new UserChatRequest(
                payload,
                null,
                sender.getId(),
                "user",
                String.valueOf(userId),
                null,
                sender.getName(),
                "pending"
        );
        UserChatService.UserChatSendResult sendResult = userChatService.send(request);

        FileMessage message = FileMessage.builder()
                .id(sendResult.messageId())
                .conversationId(sendResult.conversationId())
                .targetType("user")
                .targetId(String.valueOf(userId))
                .senderId(String.valueOf(sender.getId()))
                .senderName(sender.getName())
                .content(payload)
                .timestamp(sendResult.timestamp())
                .acceptOrReject(null)
                .collectionName(collectionName)
                .fileId(fileId)
                .chunkId(normalizedChunkId)
                .fromUserId(sender.getId())
                .toUserId(userId)
                .status("PENDING")
                .createdAt(sendResult.timestamp())
                .build();
        return Result.success(message);
    }

    /**
     * 确认接受后进行权限的设定
     * @param collectionName
     * @param fileId
     * @param userId
     * @param chunkId
     * @return
     */
    @PostMapping("/share")
    public Result<String> shareFiles(String collectionName ,String fileId , Long userId , int chunkId){
        return milvusFileManager.shareFiles(collectionName, fileId , userId , chunkId);
    }

    /**
     * 接收方接受文件分享 — 由接收方调用，直接授予权限
     * @param collectionName 集合名称
     * @param fileId 完整 doc_id（格式 filePart:000042）或 fileId
     * @param chunkId 分块 ID（可选）
     * @param senderId 发送方用户 ID（用于验证）
     * @return
     */
    @PostMapping("/share/accept")
    public Result<String> acceptShare(
            @RequestParam String collectionName,
            @RequestParam String fileId,
            @RequestParam(required = false) Integer chunkId,
            @RequestParam Long senderId) {
        User recipient = LoginUserInfoManager.getUser();
        if (recipient == null || recipient.getId() == null) {
            return Result.error(401, "未登录");
        }

        String actualFileId = fileId;
        int actualChunkId = (chunkId != null && chunkId > 0) ? chunkId : 0;
        int colonIdx = fileId.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < fileId.length() - 1) {
            actualFileId = fileId.substring(0, colonIdx);
            if (actualChunkId == 0) {
                try {
                    actualChunkId = Integer.parseInt(fileId.substring(colonIdx + 1));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (recipient.getId().equals(senderId)) {
            return Result.error(400, "不能接受自己的分享");
        }

        log.info("接受文件分享: recipient={} collection={} fileId={} chunkId={}",
                recipient.getId(), collectionName, actualFileId, actualChunkId);
        return milvusFileManager.shareFiles(collectionName, actualFileId, recipient.getId(), actualChunkId);
    }

    /**
     * 接收方拒绝文件分享 — 删除聊天消息
     * @param conversationId 对话 ID
     * @param messageId 消息 ID
     * @return
     */
    @PostMapping("/share/reject")
    public Result<String> rejectShare(
            @RequestParam String conversationId,
            @RequestParam Long messageId) {
        User recipient = LoginUserInfoManager.getUser();
        if (recipient == null || recipient.getId() == null) {
            return Result.error(401, "未登录");
        }
        boolean deleted = userChatService.deleteMessage(conversationId, messageId);
        if (deleted) {
            log.info("拒绝文件分享，已删除消息: conversationId={} messageId={}", conversationId, messageId);
            return Result.success("已拒绝并删除消息");
        }
        return Result.error(1, "消息不存在或已被删除");
    }
}
