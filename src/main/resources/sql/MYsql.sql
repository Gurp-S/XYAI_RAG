-- auto-generated definition
create table chat_memory_interaction
(
    conversation_id varchar(128)                       not null
        primary key,
    user_id         bigint                      null,
    title           varchar(255)                       null,
    summary_text    text                               null,
    created_at      datetime default CURRENT_TIMESTAMP not null
)
    collate = utf8mb4_unicode_ci;

create index idx_title
    on chat_memory_interaction (title);

create index idx_user_id
    on chat_memory_interaction (user_id);