# -- ============================================================
# -- 数据库增量迁移脚本（可重复执行，不丢数据）
# -- 使用方法: source migration.sql;
# -- ============================================================
#
# -- -----------------------------------------------------------
# -- 1. 添加缺失的表
# -- -----------------------------------------------------------
#
# -- Token 用量记录表（对应 TokenRecord 实体）
# create table if not exists chat_message_id
# (
#     chat_message_id   varchar(128)  not null comment '消息ID'
#         primary key,
#     conversation_id   varchar(128)  null comment '会话ID',
#     user_id           bigint        null comment '用户ID',
#     model_name        varchar(64)   null comment '模型名称',
#     prompt_tokens     int           null comment 'Prompt tokens',
#     completion_tokens int           null comment 'Completion tokens',
#     total_tokens      int           null comment 'Total tokens',
#     cost_ms           bigint        null comment '耗时(毫秒)',
#     call_type         varchar(32)   null comment '调用类型：chat/mcp/fast/enhance',
#     created_at        datetime      null comment '创建时间'
# )
#     comment 'Token 用量记录表' collate = utf8mb4_unicode_ci;
#
# -- 意图节点表（对应 IntentNode 实体）
# create table if not exists intent_node
# (
#     name            varchar(128)  not null comment '唯一标识，展示名称'
#         primary key,
#     node_id         varchar(128)  null comment 'id节点名如 group-hr',
#     kb_id           varchar(128)  null comment '知识库ID',
#     description     varchar(500)  null comment '语义说明',
#     parent_name     varchar(128)  null comment '父节点名称',
#     examples        json          null comment '示例问题',
#     collection_name varchar(128)  null comment '向量数据库集合名称',
#     mcp_tool_id     varchar(128)  null comment 'MCP工具ID',
#     top_k           int           null comment '节点级TopK',
#     prompt_template text          null comment 'Prompt模板',
#     children_count  int default 0 null comment '子节点数量',
#     created_at      datetime      null comment '创建时间',
#     updated_at      datetime      null comment '更新时间',
#     constraint fk_intent_parent
#         foreign key (parent_name) references intent_node (name)
# )
#     comment '意图节点表';
#
# -- -----------------------------------------------------------
# -- 2. 添加缺失的索引（使用 PROCEDURE 避免重复索引报错）
# -- -----------------------------------------------------------
#
# delimiter //
#
# drop procedure if exists sp_add_index //
# create procedure sp_add_index(
#     in p_table_name varchar(128),
#     in p_index_name varchar(128),
#     in p_index_sql  text
# )
# begin
#     declare existing_count int;
#     select count(1) into existing_count
#     from information_schema.statistics
#     where table_schema = database()
#       and table_name = p_table_name
#       and index_name = p_index_name;
#     if existing_count = 0 then
#         set @ddl = p_index_sql;
#         prepare stmt from @ddl;
#         execute stmt;
#         deallocate prepare stmt;
#     end if;
# end //
#
# drop procedure if exists sp_drop_index //
# create procedure sp_drop_index(
#     in p_table_name varchar(128),
#     in p_index_name varchar(128)
# )
# begin
#     declare existing_count int;
#     select count(1) into existing_count
#     from information_schema.statistics
#     where table_schema = database()
#       and table_name = p_table_name
#       and index_name = p_index_name;
#     if existing_count > 0 then
#         set @ddl = concat('drop index ', p_index_name, ' on ', p_table_name);
#         prepare stmt from @ddl;
#         execute stmt;
#         deallocate prepare stmt;
#     end if;
# end //
#
# delimiter ;
#
# -- chat_message_id 索引
# call sp_add_index('chat_message_id', 'idx_token_record_conversation',
#     'create index idx_token_record_conversation on chat_message_id (conversation_id)');
# call sp_add_index('chat_message_id', 'idx_token_record_created_at',
#     'create index idx_token_record_created_at on chat_message_id (created_at)');
# call sp_add_index('chat_message_id', 'idx_token_record_user_id',
#     'create index idx_token_record_user_id on chat_message_id (user_id)');
#
# -- intent_node 索引
# call sp_add_index('intent_node', 'idx_intent_node_node_id',
#     'create index idx_intent_node_node_id on intent_node (node_id)');
#
# -- -----------------------------------------------------------
# -- 3. 删除冗余的重复索引
# -- -----------------------------------------------------------
#
# -- chat_conversation: idx_conversation_id 被复合索引覆盖
# call sp_drop_index('chat_conversation', 'idx_conversation_id');
#
# -- xy_refresh_token: idx_user_id 与 idx_refresh_token_user_id 重复
# call sp_drop_index('xy_refresh_token', 'idx_user_id');
#
# -- node_record: idx_trace_id 与 idx_node_record_trace_id 重复
# call sp_drop_index('node_record', 'idx_trace_id');
#
# -- -----------------------------------------------------------
# -- 4. 清理辅助存储过程
# -- -----------------------------------------------------------
#
# drop procedure if exists sp_add_index;
# drop procedure if exists sp_drop_index;
#
# -- -----------------------------------------------------------
# -- 4. 清理 chat_message_id 冗余索引（只保留必要复合索引）
# -- -----------------------------------------------------------
#
# DROP INDEX idx_token_record_user_id ON chat_message_id;
# DROP INDEX idx_token_record_conversation ON chat_message_id;
# DROP INDEX idx_token_record_created_at ON chat_message_id;
#
# -- -----------------------------------------------------------
# -- 5. 雪花算法 ID 迁移
# -- -----------------------------------------------------------
#
# -- 5.1 user_chat_message: id 改为 bigint（配合雪花算法 long 值存储）
# ALTER TABLE user_chat_message MODIFY id BIGINT NOT NULL COMMENT '消息ID(雪花ID)';
# -- 清理 conversation_id 中旧的非数字数据（如 'user:1001:1002'），避免转为 BIGINT 失败
# UPDATE user_chat_message SET conversation_id = '0' WHERE conversation_id IS NOT NULL AND conversation_id REGEXP '[^0-9]';
# ALTER TABLE user_chat_message MODIFY conversation_id BIGINT NOT NULL COMMENT '会话ID(雪花ID)';
#
# -- 5.2 chat_conversation: 改为 bigint
# ALTER TABLE chat_conversation MODIFY chat_message_id BIGINT NOT NULL COMMENT '雪花ID';
# ALTER TABLE chat_conversation MODIFY conversation_id BIGINT NULL COMMENT '会话ID(雪花ID)';
#
# -- 5.3 chat_memory_interaction: 改为 bigint
# ALTER TABLE chat_memory_interaction MODIFY conversation_id BIGINT NOT NULL COMMENT '会话ID(雪花ID)';
#
# -- 5.4 chat_message_id: 改为 bigint
# ALTER TABLE chat_message_id MODIFY chat_message_id BIGINT NOT NULL COMMENT '消息ID(雪花ID)';
# ALTER TABLE chat_message_id MODIFY conversation_id BIGINT NULL COMMENT '会话ID(雪花ID)';
#
# -- 5.5 xy_system_evaluate: 改为 bigint
# UPDATE xy_system_evaluate SET chat_message_id = '0' WHERE chat_message_id IS NOT NULL AND chat_message_id REGEXP '[^0-9]';
# ALTER TABLE xy_system_evaluate MODIFY chat_message_id BIGINT NOT NULL COMMENT '对话消息雪花ID';
# UPDATE xy_system_evaluate SET conversation_id = '0' WHERE conversation_id IS NOT NULL AND conversation_id REGEXP '[^0-9]';
# ALTER TABLE xy_system_evaluate MODIFY conversation_id BIGINT NOT NULL COMMENT '所属会话雪花ID';
#
# -- 5.6 xy_user_evaluate: 改为 bigint
# UPDATE xy_user_evaluate SET message_id = '0' WHERE message_id IS NOT NULL AND message_id REGEXP '[^0-9]';
# ALTER TABLE xy_user_evaluate MODIFY message_id BIGINT NOT NULL COMMENT '消息雪花ID，主键';
# UPDATE xy_user_evaluate SET conversation_id = '0' WHERE conversation_id IS NOT NULL AND conversation_id REGEXP '[^0-9]';
# ALTER TABLE xy_user_evaluate MODIFY conversation_id BIGINT NOT NULL COMMENT '会话雪花ID';
#
# -- -----------------------------------------------------------
# -- 6. 完成
# -- -----------------------------------------------------------
#
# select 'Migration completed successfully.' as status;
# DROP TABLE IF EXISTS user_chat_message;
# DROP TABLE IF EXISTS xy_user_evaluate;
# DROP TABLE IF EXISTS xy_system_evaluate;
# DROP TABLE IF EXISTS chat_message_id;
# DROP TABLE IF EXISTS chat_conversation;
# DROP TABLE IF EXISTS chat_memory_interaction;
# CREATE TABLE chat_conversation
# (
#     chat_message_id   bigint                               not null comment '雪花ID'
#         primary key,
#     user_id           bigint                             not null comment '用户ID',
#     conversation_id   bigint                             null comment '会话ID(雪花ID)',
#     assistant_message text                               null,
#     user_message      text                               null,
#     created_at        datetime default CURRENT_TIMESTAMP not null,
#     feedback          int      default -1                not null comment '反馈：1-点赞，0-点踩，-1-未评价',
#     prompt_tokens     int      default 0                 null comment 'Prompt tokens',
#     completion_tokens int      default 0                 null comment 'Completion tokens',
#     total_tokens      int      default 0                 null comment 'Total tokens',
#     model_name        varchar(64)                        null comment '模型名称',
#     cost_ms           bigint   default 0                 null comment '耗时(毫秒)'
# ) collate = utf8mb4_unicode_ci;
#
# CREATE INDEX idx_chat_conversation_id_created ON chat_conversation (conversation_id, created_at);
# CREATE INDEX idx_conversation_id_msg ON chat_conversation (conversation_id, chat_message_id);
# CREATE INDEX idx_feedback ON chat_conversation (feedback);
# CREATE INDEX idx_user_conversation ON chat_conversation (user_id, conversation_id);
#
# CREATE TABLE chat_memory_interaction
# (
#     conversation_id bigint                               not null comment '会话ID(雪花ID)'
#         primary key,
#     user_id         bigint                             null,
#     title           varchar(255)                       null,
#     summary_text    text                               null,
#     created_at      datetime default CURRENT_TIMESTAMP not null
# ) collate = utf8mb4_unicode_ci;
#
# CREATE INDEX idx_title ON chat_memory_interaction (title);
# CREATE INDEX idx_user_id ON chat_memory_interaction (user_id);
#
# CREATE TABLE chat_message_id
# (
#     chat_message_id   bigint                                not null comment '消息ID（雪花ID）'
#         primary key,
#     conversation_id   bigint                                null comment '会话ID(雪花ID)',
#     user_id           bigint                                null comment '用户ID',
#     model_name        varchar(64)                           null comment '模型名称',
#     prompt_tokens     int         default 0                 null comment 'Prompt tokens',
#     completion_tokens int         default 0                 null comment 'Completion tokens',
#     total_tokens      int         default 0                 null comment 'Total tokens',
#     cost_ms           bigint      default 0                 null comment '耗时(毫秒)',
#     call_type         varchar(32) default 'chat'            null comment '调用类型：chat/mcp/fast/enhance',
#     created_at        datetime    default CURRENT_TIMESTAMP null
# ) comment 'Token用量记录表';
#
# CREATE INDEX idx_token_user_created ON chat_message_id (user_id, created_at);
# CREATE INDEX idx_token_conv_created ON chat_message_id (conversation_id, created_at);
#
# CREATE TABLE user_chat_message
# (
#     id              bigint                               not null comment '消息ID(雪花ID)'
#         primary key,
#     conversation_id bigint                               not null comment '会话ID(雪花ID)',
#     target_type     varchar(20)  default 'user'          not null comment '目标类型：user/group',
#     target_id       varchar(100)                         not null comment '目标ID',
#     sender_id       varchar(100)                         not null comment '发送方用户ID',
#     sender_name     varchar(100) default ''              null comment '发送方显示名',
#     content         text                                 null comment '消息内容(纯文本或JSON)',
#     created_at      bigint                               not null comment '创建时间戳(毫秒)',
#     status          varchar(20)  default 'active'        null comment '消息状态：active/accepted/rejected'
# ) comment '用户聊天消息持久化表' collate = utf8mb4_unicode_ci;
#
# CREATE INDEX idx_conversation_id ON user_chat_message (conversation_id);
# CREATE INDEX idx_created_at ON user_chat_message (created_at);
# CREATE INDEX idx_status ON user_chat_message (status);
# CREATE INDEX idx_target ON user_chat_message (target_type, target_id);
#
# CREATE TABLE xy_system_evaluate
# (
#     chat_message_id        bigint                               not null comment '对话消息雪花ID'
#         primary key,
#     conversation_id        bigint                               not null comment '所属会话雪花ID',
#     user_id                bigint                                null comment '用户 ID',
#     overall_score          decimal(5, 4)                         null comment '综合 F1',
#     retrieval_score        decimal(5, 4)                         null comment '检索 F1',
#     faithfulness_score     decimal(5, 4)                         null comment '忠实度 F1',
#     answer_relevance_score decimal(5, 4)                         null comment '答案相关性 F1',
#     completeness_score     decimal(5, 4)                         null comment '完整性 F1',
#     rule_score             decimal(5, 4) default 0.0000          null comment '规则评估分数',
#     rerank_score           decimal(5, 4) default 0.0000          null comment '重排评估分数',
#     llm_score              decimal(5, 4) default 0.0000          null comment '大模型评估分数',
#     retrieved_doc_count    int                                     null comment '检索到的文档数',
#     latency_ms             bigint                                  null comment '响应延迟(毫秒)',
#     model_name             varchar(50)                             null comment '使用的模型名',
#     create_time            datetime      default CURRENT_TIMESTAMP null comment '创建时间',
#     update_time            datetime                                null on update CURRENT_TIMESTAMP comment '最后更新时间',
#     extra_json             json                                    null comment '扩展评估指标'
# ) comment 'RAG 系统评估表';
#
# CREATE INDEX idx_conversation ON xy_system_evaluate (conversation_id);
# CREATE INDEX idx_create_time ON xy_system_evaluate (create_time);
# CREATE INDEX idx_user ON xy_system_evaluate (user_id);
#
# CREATE TABLE xy_user_evaluate
# (
#     message_id      bigint                               not null comment '消息雪花ID，主键'
#         primary key,
#     conversation_id bigint                               not null comment '会话雪花ID',
#     user_id         bigint                             not null comment '用户ID',
#     feedback        tinyint                            not null comment '1-赞,0-踩',
#     create_time     datetime default CURRENT_TIMESTAMP null comment '创建时间',
#     update_time     datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
# ) comment '用户点赞点踩评价表';
#
# CREATE INDEX idx_conversation ON xy_user_evaluate (conversation_id);
# CREATE INDEX idx_user ON xy_user_evaluate (user_id);









