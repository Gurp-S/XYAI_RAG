-- 管道定义表
CREATE TABLE t_ingestion_pipeline (
                                      id          bigint(20) NOT NULL,
                                      name        varchar(100) NOT NULL,  -- 管道名称
                                      description text,                  -- 管道描述
                                      created_by  varchar(64),           -- 创建人
                                      create_time datetime,              -- 创建时间
                                      PRIMARY KEY (id)
);

-- 节点配置表（关联管道）
CREATE TABLE t_ingestion_pipeline_node (
                                           id             bigint(20) NOT NULL,
                                           pipeline_id    bigint(20) NOT NULL,  -- 所属管道ID
                                           node_id        varchar(64) NOT NULL,  -- 节点标识
                                           node_type      varchar(30) NOT NULL,  -- 节点类型
                                           next_node_id   varchar(64),            -- 下一个节点ID
                                           settings_json  json,                   -- 节点配置参数（JSON格式）
                                           condition_json json,                  -- 执行条件（JSON格式）
                                           PRIMARY KEY (id)
);