package com.XYai.myai.mapper;

import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

/**
 * 聊天会话 Mapper 接口。
 * 负责与数据库交互，管理聊天会话 (ChatConversation) 的持久化。
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
    @Delete("DELETE FROM chat_conversation WHERE conversation_id = #{conversationId} ORDER BY created_at ASC LIMIT #{limit}")
    void deleteOldestMessages(@Param("conversationId") Long conversationId, @Param("limit") int limit);
}
