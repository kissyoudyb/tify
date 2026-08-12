package com.phadcalc.llm.tify.chat.mapper;

import com.phadcalc.llm.tify.chat.entity.ChatMessage;
import com.phadcalc.llm.tify.common.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND deleted = 0 ORDER BY created_at ASC LIMIT #{limit}")
    List<ChatMessage> selectRecentBySessionId(@Param("sessionId") Long sessionId, @Param("limit") int limit);
}
