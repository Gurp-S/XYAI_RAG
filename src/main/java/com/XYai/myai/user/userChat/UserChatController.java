package com.XYai.myai.user.userChat;

import com.XYai.myai.config.Result;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import com.XYai.myai.user.userChat.pojo.UserChatMessageDTO;
import com.XYai.myai.user.userChat.pojo.UserChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 用户/群聊接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/user-chat")
public class UserChatController {

    private final UserChatService userChatService;

    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<UserChatService.UserChatSendResult> send(@RequestBody UserChatRequest request) {
        Long userId = requireLoginUserId();
        UserChatRequest sanitized = new UserChatRequest(
                request.message(),
                request.conversationId(),
                userId,
                request.targetType(),
                request.targetId(),
                request.targetName(),
                request.senderName(),
                null);
        return Result.success(userChatService.send(sanitized));
    }

    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<List<UserChatMessageDTO>> messages(
            @RequestParam("targetType") String targetType,
            @RequestParam("targetId") String targetId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Long userId = requireLoginUserId();
        return Result.success(userChatService.getMessages(userId, targetType, targetId, cursor, limit));
    }

    /**
     * 上传并分享本地文件到好友/群聊。
     */
    @PostMapping(value = "/share/local-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<UserChatService.UserChatSendResult> shareLocalFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetType") String targetType,
            @RequestParam("targetId") String targetId,
            @RequestParam(value = "senderName", defaultValue = "") String senderName) {
        Long userId = requireLoginUserId();

        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = "未知文件";
        }

        try {
            // 存储文件到本地磁盘
            String fileId = java.util.UUID.randomUUID().toString().replace("-", "");
            Path storageDir = userChatService.getLocalFileStorageDir();
            String safeFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path targetPath = storageDir.resolve(fileId + "_" + System.currentTimeMillis() + "_" + safeFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 发送文件分享消息
            UserChatService.UserChatSendResult result = userChatService.shareLocalFile(
                    userId, targetType, targetId, senderName, originalFileName, file.getSize(), fileId);

            return Result.success(result);
        } catch (IOException e) {
            return Result.error(500, "文件存储失败: " + e.getMessage());
        }
    }

    /**
     * 下载已分享的本地文件。
     */
    @GetMapping(value = "/files/{fileId}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.core.io.Resource> downloadFile(@PathVariable String fileId) {
        requireLoginUserId();
        Path filePath = userChatService.resolveLocalFilePath(fileId);
        if (filePath == null || !Files.exists(filePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        try {
            org.springframework.core.io.Resource resource = new org.springframework.core.io.UrlResource(filePath.toUri());
            String filename = filePath.getFileName().toString();
            // 提取原始文件名（去掉 fileId_ 前缀）
            int underscoreIdx = filename.indexOf('_');
            if (underscoreIdx >= 0) {
                filename = filename.substring(underscoreIdx + 1);
                int secondUnderscore = filename.indexOf('_');
                if (secondUnderscore >= 0) {
                    filename = filename.substring(secondUnderscore + 1);
                }
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8))
                    .body(resource);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件读取失败");
        }
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody UserChatRequest request) {
        Long userId = requireLoginUserId();
        UserChatRequest sanitized = new UserChatRequest(
                request.message(),
                request.conversationId(),
                userId,
                request.targetType(),
                request.targetId(),
                request.targetName(),
                request.senderName(),
                null);
        return userChatService.chat(sanitized);
    }

    @PostMapping("/messages/{messageId}/status")
    public Result<String> updateMessageStatus(
            @PathVariable Long messageId,
            @RequestParam String status) {
        requireLoginUserId();
        boolean updated = userChatService.updateMessageStatus(messageId, status);
        if (updated) {
            return Result.success("状态已更新");
        }
        return Result.error(500, "状态更新失败");
    }

    private Long requireLoginUserId() {
        User loginUser = LoginUserInfoManager.getUser();
        if (loginUser == null || loginUser.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return loginUser.getId();
    }
}