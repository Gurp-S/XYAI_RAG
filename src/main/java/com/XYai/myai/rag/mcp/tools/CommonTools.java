package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class CommonTools {

    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Tool(name = "memery_search", description = "模糊查找会话/工具记忆(如果刚才没有收到)")
    public String memeryTools(@ToolParam(description = "查找的内容") String expr) {
        Long userId = LoginUserInfoManager.getUserId();
        // 根据用户id查找对话 从userMessage 和 assistantMessage 中模糊查
        LambdaQueryWrapper<ChatConversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversation::getUserId, userId)
                .and(q -> q.like(ChatConversation::getUserMessage, expr)
                        .or()
                        .like(ChatConversation::getAssistantMessage, expr))
                .orderByAsc(ChatConversation::getCreatedAt);
        List<ChatConversation> list = chatConversationMapper.selectList(wrapper);
        if (list == null || list.isEmpty()) {
            return "未找到相关记忆记录";
        }
        // 拼接结果
        StringBuilder memery = new StringBuilder();
        memery.append("找到历史记忆：\n");
        for (ChatConversation conv : list) {
            memery.append("------------------------\n");
            memery.append("用户：").append(conv.getUserMessage()).append("\n");
            memery.append("AI：").append(conv.getAssistantMessage()).append("\n");
        }
        return memery.toString();
    }

    @Tool(name = "now_time", description = "获取当前系统时间，格式：yyyy-MM-dd HH:mm:ss")
    public String timeNowTools() {
        // 修复你原来的错误格式：yyyy:dd:ss 是错的
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }

    @Tool(name = "用户可查的所有表和表内字段", description = "查用户可以访问的数据库表")
    public String mySqlTableNames(){
        return null;
    }


    @Tool(name = "数据库搜索", description = "用sql语句获取数据库")
    public String mySqlSearchTool(
            @ToolParam(description = "查找的表名字") String tableName,
            @ToolParam(description = "查找的sql语句") String sql) {

        // 1. 安全校验：只允许 SELECT 语句
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("select")) {
            return "错误：只允许执行 SELECT 查询语句";
        }
        boolean tableNameExists;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, tableName);
        tableNameExists = count != null && count > 0;
        // 2. 可选：校验 expr 是否为数据库中真实存在的表，防止随意查
        if (tableName != null && !tableName.isBlank()) {
            if (!tableNameExists) {
                return "错误：表名 " + tableName + " 不存在或无权限访问";
            }
        }
        // 3. 创建临时 JdbcTemplate 并限制最大返回行数
        DataSource dataSource = jdbcTemplate.getDataSource();
        JdbcTemplate safeTemplate = null;
        if (dataSource != null) {
            safeTemplate = new JdbcTemplate(dataSource);
        }
        if (safeTemplate != null) {
            safeTemplate.setMaxRows(5);
        }
        try {
            List<Map<String, Object>> rows = null;
            if (safeTemplate != null) {
                rows = safeTemplate.queryForList(sql);
            }
            // 4. 序列化为 JSON 字符串返回
            return JSON.toJSONString(rows);
        } catch (Exception e) {
            return "数据库查询出错: " + e.getMessage();
        }
    }

}
