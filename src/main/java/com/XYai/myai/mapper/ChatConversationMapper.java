package com.XYai.myai.mapper;

import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天会话 Mapper 接口。
 * 负责与数据库交互，管理聊天会话 (ChatConversation) 的持久化。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
