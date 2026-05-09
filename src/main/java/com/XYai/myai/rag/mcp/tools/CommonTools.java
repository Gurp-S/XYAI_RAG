package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.user.LoginUserInfoManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CommonTools {

    @Resource
    private ChatConversationMapper chatConversationMapper;

    @Tool(name = "memery_search" ,description = "模糊查找会话/工具记忆(如果刚才没有收到)")
    public String memeryTools(@ToolParam(description = "查找的内容") String expr){
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



}
