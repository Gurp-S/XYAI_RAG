package com.XYai.myai.xyAdmin;

import com.XYai.myai.security.annotation.AdminOnly;

import com.XYai.myai.config.Result;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 公告/消息后台管理
 * 使用 Redis List 存储公告消息
 */
@Slf4j
@AdminOnly
@RestController
@RequestMapping("/xyAdmin/announcement")
public class Announcement {

    private static final String REDIS_KEY = "xyai:admin:announcement:list";
    private static final long MAX_MESSAGES = 200;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送公告消息
     */
    @PostMapping("/send")
    public Result<Map<String, Object>> sentMessage(@RequestParam String content) {
        AnnouncementMsg msg = new AnnouncementMsg(
                UUID.randomUUID().toString().replace("-", ""),
                content,
                System.currentTimeMillis());
        String json = JSON.toJSONString(msg);
        stringRedisTemplate.opsForList().leftPush(REDIS_KEY, json);

        // 裁剪列表长度
        Long size = stringRedisTemplate.opsForList().size(REDIS_KEY);
        if (size != null && size > MAX_MESSAGES) {
            stringRedisTemplate.opsForList().trim(REDIS_KEY, 0, MAX_MESSAGES - 1);
        }

        log.info("管理员发送了公告: {}", msg.getId());
        return Result.success(Map.of(
                "id", msg.getId(),
                "content", msg.getContent(),
                "timestamp", msg.getTimestamp()));
    }

    /**
     * 获取历史公告列表
     */
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistoryMessage() {
        List<String> jsons = stringRedisTemplate.opsForList().range(REDIS_KEY, 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return Result.success(List.of());
        }

        List<Map<String, Object>> result = jsons.stream()
                .map(json -> {
                    try {
                        AnnouncementMsg msg = JSON.parseObject(json, AnnouncementMsg.class);
                        if (msg != null) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("id", msg.getId());
                            map.put("content", msg.getContent());
                            map.put("timestamp", msg.getTimestamp());
                            return map;
                        }
                    } catch (Exception e) {
                        log.debug("公告JSON解析失败", e);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 删除指定公告
     */
    @PostMapping("/delete")
    public Result<String> deleteMessage(@RequestParam String id) {
        long removed = removeById(id);
        if (removed > 0) {
            log.info("管理员删除了公告: {}", id);
            return Result.success("公告已删除");
        }
        return Result.error(404, "公告不存在: " + id);
    }

    /**
     * 更新公告内容
     */
    @PostMapping("/update")
    public Result<String> updateMessage(@RequestParam String id, @RequestParam String content) {
        List<String> jsons = stringRedisTemplate.opsForList().range(REDIS_KEY, 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return Result.error(404, "公告不存在: " + id);
        }

        boolean found = false;
        for (int i = 0; i < jsons.size(); i++) {
            try {
                AnnouncementMsg msg = JSON.parseObject(jsons.get(i), AnnouncementMsg.class);
                if (msg != null && id.equals(msg.getId())) {
                    AnnouncementMsg updated = new AnnouncementMsg(id, content, msg.getTimestamp());
                    stringRedisTemplate.opsForList().set(REDIS_KEY, i, JSON.toJSONString(updated));
                    found = true;
                    break;
                }
            } catch (Exception e) {
                log.debug("公告Redis操作失败", e);
            }
        }

        if (found) {
            log.info("管理员更新了公告: {}", id);
            return Result.success("公告已更新");
        }
        return Result.error(404, "公告不存在: " + id);
    }

    /**
     * 按 ID 删除公告（遍历匹配）
     */
    private long removeById(String id) {
        List<String> jsons = stringRedisTemplate.opsForList().range(REDIS_KEY, 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return 0;
        }

        long count = 0;
        // 使用新列表重建（Redis List 没有按值删除指定位置的原子操作）
        for (String json : jsons) {
            try {
                AnnouncementMsg msg = JSON.parseObject(json, AnnouncementMsg.class);
                if (msg != null && id.equals(msg.getId())) {
                    stringRedisTemplate.opsForList().remove(REDIS_KEY, 1, json);
                    count++;
                }
            } catch (Exception e) {
                log.debug("公告Redis操作失败", e);
            }
        }
        return count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class AnnouncementMsg {
        private String id;
        private String content;
        private long timestamp;
    }
}