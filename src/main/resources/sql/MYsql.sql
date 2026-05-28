# -- auto-generated definition
# create table chat_conversation
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
# )
#     collate = utf8mb4_unicode_ci;
#
# create index idx_chat_conversation_id_created
#     on chat_conversation (conversation_id, created_at);
#
# create index idx_conversation_id_msg
#     on chat_conversation (conversation_id, chat_message_id);
#
# create index idx_feedback
#     on chat_conversation (feedback);
#
# create index idx_user_conversation
#     on chat_conversation (user_id, conversation_id);
#
# -- auto-generated definition
# create table chat_memory_interaction
# (
#     conversation_id bigint                               not null comment '会话ID(雪花ID)'
#         primary key,
#     user_id         bigint                             null,
#     title           varchar(255)                       null,
#     summary_text    text                               null,
#     created_at      datetime default CURRENT_TIMESTAMP not null
# )
#     collate = utf8mb4_unicode_ci;
#
# create index idx_title
#     on chat_memory_interaction (title);
#
# create index idx_user_id
#     on chat_memory_interaction (user_id);
#
# -- auto-generated definition
# create table chat_message_id
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
# )
#     comment 'Token用量记录表';
#
# -- 清理重复索引，只保留必要组合
# create index idx_token_user_created
#     on chat_message_id (user_id, created_at);
#
# create index idx_token_conv_created
#     on chat_message_id (conversation_id, created_at);
#
# -- auto-generated definition
# create table intent_node
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
# create index idx_intent_node_node_id
#     on intent_node (node_id);
#
# -- auto-generated definition
# create table node_record
# (
#     node_id       varchar(64)  not null comment '节点唯一 ID'
#         primary key,
#     trace_id      varchar(64)  not null comment '所属 traceId',
#     node_name     varchar(255) null comment '节点名称',
#     node_type     varchar(64)  null comment '节点类型',
#     cost_time     bigint       null comment '节点耗时(毫秒)',
#     status        varchar(32)  null comment '节点状态(SUCCESS/ERROR)',
#     error_message text         null comment '节点异常信息',
#     start_time    datetime     null comment '节点开始时间',
#     end_time      datetime     null comment '节点结束时间'
# )
#     comment 'RAG 链路节点记录表';
#
# create index idx_node_record_start_time
#     on node_record (start_time);
#
# create index idx_node_record_trace_id
#     on node_record (trace_id);
#
# -- auto-generated definition
# create table trace_record
# (
#     trace_id      varchar(64)  not null comment '全链路唯一 traceId'
#         primary key,
#     name          varchar(255) null comment '根节点名称或任务名',
#     start_time    datetime     null comment '链路开始时间',
#     end_time      datetime     null comment '节点结束时间',
#     status        varchar(32)  null comment '链路状态(RUNNING/SUCCESS/ERROR)',
#     error_message text         null comment '错误消息',
#     cost_time     bigint       null comment '链路总耗时(毫秒)'
# )
#     comment 'RAG 全链路追踪记录表';
#
# create index idx_trace_record_name
#     on trace_record (name(64));
#
# create index idx_trace_record_start_time
#     on trace_record (start_time);
#
# -- auto-generated definition
# create table user_chat_message
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
# )
#     comment '用户聊天消息持久化表' collate = utf8mb4_unicode_ci;
#
# create index idx_conversation_id
#     on user_chat_message (conversation_id);
#
# create index idx_created_at
#     on user_chat_message (created_at);
#
# create index idx_status
#     on user_chat_message (status);
#
# create index idx_target
#     on user_chat_message (target_type, target_id);
#
# -- auto-generated definition
# create table xy_daily_file_usage
# (
#     date       date                               not null comment '日期'
#         primary key,
#     use_count  bigint   default 0                 null comment '当日文件使用次数',
#     updated_at datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
# )
#     comment '每日文件使用计数表';
#
# -- auto-generated definition
# create table xy_file_record
# (
#     file_chunk_id varchar(128)     not null
#         primary key,
#     use_count     bigint default 0 null comment '使用次数',
#     file_name     varchar(255)     null comment '来源文件名'
# )
#     comment '文件记录表';
#
# -- auto-generated definition
# create table xy_knowledge_triple
# (
#     id              bigint auto_increment
#         primary key,
#     head            varchar(500)                       not null,
#     relation        varchar(500)                       not null,
#     tail            varchar(500)                       not null,
#     chunk_id        varchar(100)                       not null,
#     file_id         varchar(100)                       null,
#     collection_name varchar(100)                       null,
#     create_time     datetime default CURRENT_TIMESTAMP null
# );
#
# -- auto-generated definition
# create table xy_model_candidate
# (
#     name                varchar(50)                             not null comment '模型唯一标识，如 qwen-max'
#         primary key,
#     display_name        varchar(100)                            null comment '前端显示名',
#     api_model           varchar(100)                            not null comment '实际调用模型名',
#     priority            int           default 5                 not null,
#     enabled             tinyint(1)    default 1                 not null,
#     weight              int           default 1                 not null,
#     temperature         decimal(3, 2) default 0.30              not null comment '温度参数',
#     max_tokens          int           default 2000              not null comment '最大 Token',
#     purpose             varchar(200)                            null comment '模型用途说明',
#     failure_threshold   int           default 50                null,
#     wait_duration_open  bigint        default 10000             null,
#     sliding_window_size int           default 10                null,
#     minimum_calls       int           default 5                 null,
#     created_at          datetime      default CURRENT_TIMESTAMP null,
#     updated_at          datetime      default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
#     constraint name
#         unique (name)
# );
#
# -- auto-generated definition
# create table xy_refresh_token
# (
#     id         bigint auto_increment
#         primary key,
#     user_id    bigint               not null,
#     token_hash varchar(512)         null,
#     issued_at  datetime             not null,
#     expires_at datetime             not null,
#     revoked    tinyint(1) default 0 not null,
#     constraint uk_token_hash
#         unique (token_hash)
# )
#     collate = utf8mb4_unicode_ci;
#
# create index idx_refresh_token_hash
#     on xy_refresh_token (token_hash);
#
# create index idx_refresh_token_user_id
#     on xy_refresh_token (user_id);
#
# -- auto-generated definition
# create table xy_system_config
# (
#     id           bigint auto_increment comment '主键ID'
#         primary key,
#     config_group varchar(64)                        not null comment '配置分组：feature_model/pipeline_node/system',
#     config_key   varchar(128)                       not null comment '配置键',
#     config_value text                               null comment '配置值(JSON格式)',
#     description  varchar(255)                       null comment '中文描述',
#     created_at   datetime default CURRENT_TIMESTAMP null,
#     updated_at   datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
#     constraint uk_config
#         unique (config_group, config_key)
# )
#     comment '系统配置持久化表';
#
# create index idx_config_group
#     on xy_system_config (config_group);
#
# -- auto-generated definition
# create table xy_system_evaluate
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
# )
#     comment 'RAG 系统评估表';
#
# create index idx_conversation
#     on xy_system_evaluate (conversation_id);
#
# create index idx_create_time
#     on xy_system_evaluate (create_time);
#
# create index idx_user
#     on xy_system_evaluate (user_id);
#
# -- auto-generated definition
# create table xy_user
# (
#     id              bigint auto_increment                        not null
#         primary key,
#     group_id        varchar(64)                        null,
#     user_rank       bigint                             null,
#     name            varchar(100)                       not null,
#     password        varchar(255)                       not null,
#     avatar          varchar(500)                       null comment '用户头像URL',
#     phone           varchar(20)                        null comment '手机号',
#     email           varchar(100)                       null comment '邮箱',
#     create_time     datetime default CURRENT_TIMESTAMP null comment '创建时间',
#     update_time     datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
#     last_login_time datetime                           null comment '最后登录时间',
#     remark          varchar(500)                       null comment '备注',
#     status          tinyint(1)                         null comment '账号状态：1启用 0禁用'
# );
#
# create index idx_user_group_id
#     on xy_user (group_id);
#
# -- auto-generated definition
# create table xy_user_evaluate
# (
#     message_id      bigint                               not null comment '消息雪花ID，主键'
#         primary key,
#     conversation_id bigint                               not null comment '会话雪花ID',
#     user_id         bigint                             not null comment '用户ID',
#     feedback        tinyint                            not null comment '1-赞,0-踩',
#     create_time     datetime default CURRENT_TIMESTAMP null comment '创建时间',
#     update_time     datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
# )
#     comment '用户点赞点踩评价表';
#
# create index idx_conversation
#     on xy_user_evaluate (conversation_id);
#
# create index idx_user
#     on xy_user_evaluate (user_id);
#
# -- auto-generated definition
# create table xy_user_friend
# (
#     user_id      bigint   not null,
#     friend_id    bigint   not null,
#     create_time  datetime default CURRENT_TIMESTAMP null,
#     pair_low_id  bigint as (least(user_id, friend_id)) stored,
#     pair_high_id bigint as (greatest(user_id, friend_id)) stored,
#     primary key (user_id, friend_id),
#     constraint uk_user_friend_pair
#         unique (pair_low_id, pair_high_id)
# );
#
# -- auto-generated definition
# create table xy_user_group
# (
#     group_id    varchar(64)  not null
#         primary key,
#     group_name  varchar(100) not null,
#     create_time datetime     null
# );
