-- auto-generated definition
create table intent_node
(
    name            varchar(255)                        not null
        primary key,
    kb_id           varchar(255)                        null,
    node_id              varchar(255)                        not null,
    description     text                                null,
    parent_name     varchar(255)                        null,
    examples        json                                null,
    collection_name varchar(255)                        null,
    mcp_tool_id     varchar(255)                        null,
    top_k           int                                 null,
    prompt_template text                                null,
    children_count  int       default 0                 not null,
    created_at      timestamp default CURRENT_TIMESTAMP null,
    updated_at      timestamp default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
    constraint fk_intent_parent
        foreign key (parent_name) references intent_node (name)
            on update cascade on delete set null
)
    collate = utf8mb4_unicode_ci;

create index idx_children_count
    on intent_node (children_count);

create index idx_node_id
    on intent_node (node_id);

create index idx_parent_id
    on intent_node (parent_name);

