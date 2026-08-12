package com.phadcalc.llm.tify.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.phadcalc.llm.tify.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    private Long agentId;

    private String title;

    /** ACTIVE / ARCHIVED */
    private String status;
}
