package com.XYai.myai.mapper;

import com.XYai.myai.RAG.Memory.ChatSessionRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatSessionRecordMapper extends BaseMapper<ChatSessionRecord> {

    @Select("SELECT COUNT(1) FROM chat_memory_interaction WHERE conversation_id = #{conversationId}")
    long countByConversationId(@Param("conversationId") String conversationId);

    @Delete("""
            DELETE FROM chat_memory_interaction
            WHERE conversation_id = #{conversationId}
            ORDER BY created_at
            LIMIT #{deleteCount}
            """)
    int deleteOldestByLimit(@Param("conversationId") String conversationId,
                            @Param("deleteCount") int deleteCount);
}
