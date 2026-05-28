package com.XYai.myai.xyAdmin;

import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.GroupMapper;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.pojo.Group;
import com.XYai.myai.user.pojo.User;
import com.XYai.myai.xyAdmin.pojo.GroupMemberCount;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理后台
 * status 语义：true=在线, false=离线/禁用
 */
@Slf4j
@RestController
@RequestMapping("/xyAdmin/user")
public class userManager {

    @Resource
    private UserMapper userMapper;

    @Resource
    private GroupMapper groupMapper;

    @Resource
    private ChatConversationMapper chatConversationMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 分页获取用户信息（支持搜索、排序）
     * @param page 页码
     * @param size 每页大小
     * @param name 搜索用户名（模糊）
     * @param groupId 搜索组ID
     * @param userRank 搜索等级
     * @param sortBy 排序字段：id, name, user_rank, create_time
     * @param sortOrder asc/desc
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> getUsersPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Integer userRank,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder) {
        
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        
        // 搜索
        if (name != null && !name.isBlank()) {
            wrapper.like("name", name);
        }
        if (groupId != null) {
            wrapper.eq("group_id", groupId);
        }
        if (userRank != null) {
            wrapper.eq("user_rank", userRank);
        }
        
        // 排序
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortBy.toLowerCase()) {
            case "name" -> wrapper.orderBy(true, isAsc, "name");
            case "user_rank" -> wrapper.orderBy(true, isAsc, "user_rank");
            case "create_time" -> wrapper.orderBy(true, isAsc, "create_time");
            default -> wrapper.orderBy(true, isAsc, "id");
        }
        
        Page<User> pageResult = userMapper.selectPage(new Page<>(page, size), wrapper);

        // 清除密码
        pageResult.getRecords().forEach(u -> u.setPassword(null));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("records", pageResult.getRecords());
        return Result.success(result);
    }

    /**
     * 获取所有用户组信息（包含成员数）
     */
    @GetMapping("/groups")
    public Result<List<Map<String, Object>>> getAllGroups() {
        List<GroupMemberCount> counts = groupMapper.selectGroupMemberCounts();
        List<Map<String, Object>> result = counts.stream().map(c -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("groupId", c.getGroupId());
            info.put("groupName", c.getGroupId()); // 使用 groupId 作为名称
            info.put("memberCount", c.getMemberCount() != null ? c.getMemberCount() : 0L);
            return info;
        }).collect(Collectors.toList());
        return Result.success(result);
    }

    /**
     * 获取组详情（包含成员列表）
     */
    @GetMapping("/group/detail")
    public Result<Map<String, Object>> getGroupDetail(@RequestParam Long groupId) {
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            return Result.error(404, "组不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", group);
        
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("group_id", groupId);
        List<User> members = userMapper.selectList(wrapper);
        members.forEach(u -> u.setPassword(null));
        result.put("members", members);
        result.put("memberCount", members.size());
        return Result.success(result);
    }

    /**
     * 删除用户（真实物理删除，连带清理关联数据）
     */
    @PostMapping("/delete")
    public Result<String> deleteUser(@RequestParam Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        // 删除用户对话记录
        chatConversationMapper
                .delete(new QueryWrapper<com.XYai.myai.rag.memory.pojo.ChatConversation>().eq("user_id", userId));
        // 清理 Redis 用户数据
        cleanUserRedisData(userId);
        // 删除用户本身
        userMapper.deleteById(userId);
        log.info("管理员删除了用户: id={}, name={}", userId, user.getName());
        return Result.success("用户 [" + user.getName() + "] 及关联数据已删除");
    }

    /**
     * 更新用户状态（在线/离线）
     */
    @PostMapping("/status")
    public Result<String> updateUserStatus(@RequestParam Long userId, @RequestParam Boolean status) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        user.setStatus(status);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("管理员更新用户状态: id={}, status={}", userId, status);
        return Result.success("用户状态已更新为 " + (status ? "在线" : "离线"));
    }

    /**
     * 更新用户等级
     */
    @PostMapping("/rank")
    public Result<String> updateUserRank(@RequestParam Long userId, @RequestParam Integer userRank) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        user.setUserRank(Long.valueOf(userRank));
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("管理员更新用户等级: id={}, rank={}", userId, userRank);
        return Result.success("用户等级已更新为 " + userRank);
    }

    /**
     * 删除异常用户（name 为空的用户）
     */
    @PostMapping("/deleteError")
    public Result<String> deleteErrorUsers(@RequestParam(defaultValue = "true") boolean dryRun) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.isNull("name").or().eq("name", "").or().eq("name", "null");
        List<User> errorUsers = userMapper.selectList(wrapper);
        if (errorUsers.isEmpty()) {
            return Result.success("没有异常用户需要处理");
        }
        List<Long> ids = errorUsers.stream().map(User::getId).toList();
        if (dryRun) {
            return Result.success("预览模式：发现 " + errorUsers.size() + " 个异常用户 (ID: " + ids + ")，设置 dryRun=false 执行删除");
        }
        for (Long id : ids) {
            chatConversationMapper
                    .delete(new QueryWrapper<com.XYai.myai.rag.memory.pojo.ChatConversation>().eq("user_id", id));
            cleanUserRedisData(id);
            userMapper.deleteById(id);
        }
        log.info("管理员删除了 {} 个异常用户: {}", errorUsers.size(), ids);
        return Result.success("已删除 " + errorUsers.size() + " 个异常用户");
    }

    /**
     * 清理用户在 Redis 中的 ACL 数据
     */
    private void cleanUserRedisData(Long userId) {
        try {
            String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
            String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
            stringRedisTemplate.delete(loadKey);
            stringRedisTemplate.delete(unloadKey);
            Set<String> fileBitsKeys = stringRedisTemplate.keys(RedisKeyConfig.userFileBitKeyPatternByUser(userId));
            if (fileBitsKeys != null) {
                stringRedisTemplate.delete(fileBitsKeys);
            }
            log.debug("已清理用户 {} 的 Redis 数据", userId);
        } catch (Exception e) {
            log.warn("清理用户 {} Redis 数据异常: {}", userId, e.getMessage());
        }
    }
}
