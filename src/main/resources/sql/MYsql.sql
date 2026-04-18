#
# -- 用户表
# CREATE TABLE IF NOT EXISTS xy_user (
#     id bigint(20) NOT NULL,
#     group_id varchar(64),
#     user_rank bigint(20),
#     name varchar(100) NOT NULL,
#     password varchar(255) NOT NULL,
#     PRIMARY KEY (id)
# );
#
# -- 用户组表
# CREATE TABLE IF NOT EXISTS xy_user_group (
#     group_id varchar(64) NOT NULL,
#     group_name varchar(100) NOT NULL,
#     create_time datetime,
#     PRIMARY KEY (group_id)
# );
#
# -- 好友关系表（双向存储）
# CREATE TABLE IF NOT EXISTS xy_user_friend (
#     user_id bigint(20) NOT NULL,
#     friend_id bigint(20) NOT NULL,
#     create_time datetime,
#     pair_low_id bigint(20) GENERATED ALWAYS AS (LEAST(user_id, friend_id)) STORED,
#     pair_high_id bigint(20) GENERATED ALWAYS AS (GREATEST(user_id, friend_id)) STORED,
#     PRIMARY KEY (user_id, friend_id),
#     UNIQUE KEY uk_user_friend_pair (pair_low_id, pair_high_id)
# );
#
# -- ==================== 测试数据：组 ====================
# INSERT IGNORE INTO xy_user_group(group_id, group_name, create_time) VALUES
# ('g-1001', '研发组', NOW()),
# ('g-1002', '产品组', NOW()),
# ('admins', '管理员组', NOW());
#
# -- ==================== 测试数据：用户 ====================
# INSERT IGNORE INTO xy_user(id, group_id, user_rank, name, password) VALUES
# (1001, 'admins', 0, 'XY_admin', '123456'),
# (1002, 'g-1001', 2, 'bob', '123456'),
# (1003, 'g-1001', 2, 'carol', '123456'),
# (1004, 'g-1002', 2, 'david', '123456'),
# (1005, 'g-1002', 2, 'eric', '123456');
#
# -- ==================== 测试数据：好友关系 ====================
# INSERT IGNORE INTO xy_user_friend(user_id, friend_id, create_time) VALUES
# (1001, 1002, NOW()),
# (1001, 1003, NOW()),
# (1004, 1005, NOW());
# #
# ALTER TABLE xy_user ADD COLUMN avatar VARCHAR(500) DEFAULT NULL COMMENT '用户头像URL';
#
# ALTER TABLE xy_user
#     ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
#     ADD COLUMN email VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
#     ADD COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
#     ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
#     ADD COLUMN last_login_time DATETIME DEFAULT NULL COMMENT '最后登录时间',
#     ADD COLUMN remark VARCHAR(500) DEFAULT NULL COMMENT '备注';
-- 只添加缺失的 status 字段
# ALTER TABLE xy_user ADD COLUMN status tinyint(1) NULL COMMENT '账号状态：1启用 0禁用';
#
# CREATE TABLE IF NOT EXISTS xy_refresh_token (
#                                                 id BIGINT NOT NULL AUTO_INCREMENT,
#                                                 user_id BIGINT NOT NULL,
#                                                 token_hash VARBINARY(32) NOT NULL,  -- 存二进制 SHA-256（32 字节）
#                                                 issued_at DATETIME NOT NULL,
#                                                 expires_at DATETIME NOT NULL,
#                                                 revoked TINYINT(1) NOT NULL DEFAULT 0,
#                                                 PRIMARY KEY (id),
#                                                 UNIQUE KEY uk_token_hash (token_hash(32)),
#                                                 INDEX idx_user_id (user_id)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

# ALTER TABLE xy_refresh_token MODIFY COLUMN token_hash VARCHAR(512);

#
# CREATE TABLE `xy_file_record` (
#                                   `file_id` BIGINT NOT NULL COMMENT '文件ID',
#                                   `file_name` VARCHAR(255) NOT NULL COMMENT '文件名',
#                                   `kb_id` VARCHAR(64) NOT NULL COMMENT '知识库ID',
#                                   `owner_id` VARCHAR(64) NOT NULL COMMENT '创建者/拥有者ID',
#                                   `group_id` VARCHAR(64) DEFAULT NULL COMMENT '所属组ID',
#                                   `visibility` VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT '可见性：private/group/public',
#                                   `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
#                                   PRIMARY KEY (`file_id`),
#                                   KEY `idx_kb_id` (`kb_id`),
#                                   KEY `idx_owner_id` (`owner_id`),
#                                   KEY `idx_group_id` (`group_id`),
#                                   KEY `idx_visibility` (`visibility`)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件主记录表';
# CREATE TABLE `xy_file_permission` (
#                                       `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
#                                       `file_id` BIGINT NOT NULL COMMENT '文件ID',
#                                       `user_id` VARCHAR(64) NOT NULL COMMENT '授权用户ID',
#                                       `permission_type` VARCHAR(16) NOT NULL COMMENT '权限类型：read/write',
#                                       `expire_time` BIGINT DEFAULT NULL COMMENT '过期时间（毫秒时间戳）',
#                                       PRIMARY KEY (`id`),
#                                       UNIQUE KEY `uk_file_user_perm` (`file_id`, `user_id`, `permission_type`),
#                                       KEY `idx_file_id` (`file_id`),
#                                       KEY `idx_user_id` (`user_id`)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件权限关联表';
# CREATE TABLE `xy_file_milvus` (
#                                   `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
#                                   `group_id` BIGINT DEFAULT NULL COMMENT '组ID',
#                                   `owner_id` BIGINT NOT NULL COMMENT '上传者用户ID',
#                                   `shared_with` JSON DEFAULT NULL COMMENT '共享用户ID列表',
#                                   `visibility` VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT '可见范围：private/group/public',
#                                   PRIMARY KEY (`id`),
#                                   KEY `idx_owner_id` (`owner_id`),
#                                   KEY `idx_group_id` (`group_id`),
#                                   KEY `idx_visibility` (`visibility`)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Milvus 文档权限主体表';

# ALTER TABLE xy_file_record
#     ADD COLUMN collection_name VARCHAR(255) DEFAULT NULL COMMENT '集合名';
# ALTER TABLE `xy_file_record`
#     MODIFY COLUMN `file_id` VARCHAR(64) NOT NULL COMMENT '文件ID',
#     MODIFY COLUMN `kb_id` VARCHAR(64) DEFAULT NULL COMMENT '知识库ID',
#     MODIFY COLUMN `owner_id` VARCHAR(64) DEFAULT NULL COMMENT '拥有者ID',
#     MODIFY COLUMN `group_id` VARCHAR(64) DEFAULT NULL COMMENT '组ID',
#     ADD COLUMN `collection_name` VARCHAR(255) DEFAULT NULL COMMENT '集合名';
-- 保证 canonical hash 唯一（如果你希望以 hash 为主键），否则调整为适合你现有 schema
# ALTER TABLE xy_file_record
#     ADD UNIQUE INDEX ux_xy_file_record_file_id (file_id);
#
# -- 保证短 id 唯一（用于快速查重/展示）
# CREATE UNIQUE INDEX ux_xy_file_record_short_id ON xy_file_record (short_id);

-- 先备份表
-- ALTER TABLE 前建议备份
# ALTER TABLE `xy_file_record`
#     MODIFY COLUMN `file_id` VARCHAR(128) NOT NULL COMMENT '文件ID（canonical hash）';
#
# -- 可选索引
# CREATE UNIQUE INDEX ux_xy_file_record_file_id ON `xy_file_record` (`file_id`);
# CREATE UNIQUE INDEX ux_xy_file_record_short_id ON `xy_file_record` (`short_id`);
-- =========================
-- xy_file_record 建表 + 兼容性迁移脚本
-- 说明：
--  - file_id 存放 canonical hash (SHA-256 hex = 64 chars)
--  - short_id 为展示用短ID (UUID)
--  - kb_id/owner_id/group_id: 允许 NULL，避免 insert 因无默认值失败
--  - extra_metadata: JSON 字段，用于存放较大 metadata（可选，减少 Milvus metadata 体积）
-- 运行环境建议：MySQL 8.0+
-- 备份提醒：执行前请先备份 xy_file_record 表
-- =========================

-- 1) 如果表不存在，则创建表（新环境）
# CREATE TABLE IF NOT EXISTS `xy_file_record` (
#                                                 `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
#                                                 `file_id` VARCHAR(64) NOT NULL COMMENT '文件ID（canonical SHA-256 hex）',
#                                                 `short_id` VARCHAR(36) DEFAULT NULL COMMENT '短 ID（展示用，UUID）',
#                                                 `file_name` VARCHAR(255) DEFAULT NULL COMMENT '文件名',
#                                                 `collection_name` VARCHAR(128) DEFAULT NULL COMMENT 'Milvus/Collection 名称',
#                                                 `kb_id` BIGINT DEFAULT NULL COMMENT '知识库 id，可为空',
#                                                 `owner_id` BIGINT DEFAULT NULL COMMENT '拥有者 id，可为空',
#                                                 `group_id` BIGINT DEFAULT NULL COMMENT '分组 id，可为空',
#                                                 `visibility` TINYINT DEFAULT 1 COMMENT '可见性，默认 1',
#                                                 `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
#                                                 `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
#                                                 `extra_metadata` JSON DEFAULT NULL COMMENT '额外元数据（可存较大 JSON）',
#                                                 PRIMARY KEY (`id`)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件记录表';
#
# -- 2) 针对已有表的兼容性修改（按需执行）
# -- 修改 file_id 长度为 64（如果已经是或更大则不会丢信息）
# ALTER TABLE `xy_file_record`
#     MODIFY COLUMN `file_id` VARCHAR(64) NOT NULL COMMENT '文件ID（canonical SHA-256 hex）';
#
# -- 增加 short_id 字段（若不存在）
# ALTER TABLE `xy_file_record`
#     ADD COLUMN IF NOT EXISTS `short_id` VARCHAR(36) DEFAULT NULL COMMENT '短 ID（展示用）';
#
# -- 增加 kb_id/owner_id/group_id 字段的 NULL 默认（若这些字段存在但非 NULL，略过）
# ALTER TABLE `xy_file_record`
#     MODIFY COLUMN `kb_id` BIGINT DEFAULT NULL,
#     MODIFY COLUMN `owner_id` BIGINT DEFAULT NULL,
#     MODIFY COLUMN `group_id` BIGINT DEFAULT NULL;
#
# -- 增加 extra_metadata 字段（若不存在）
# ALTER TABLE `xy_file_record`
#     ADD COLUMN IF NOT EXISTS `extra_metadata` JSON DEFAULT NULL COMMENT '额外元数据（可选）';
#
# -- 3) 索引：先删除可能存在的旧索引，再创建期望的索引
# -- 注意：DROP INDEX IF EXISTS 在 MySQL 8 可用；如果你的 MySQL 不支持 IF EXISTS，请手动确认并删除旧索引
# DROP INDEX IF EXISTS `ux_xy_file_record_file_id` ON `xy_file_record`;
# CREATE UNIQUE INDEX `ux_xy_file_record_file_id` ON `xy_file_record` (`file_id`);
#
# -- short_id 可能不是全局唯一，使用普通索引以便查询/展示，不强制唯一
# DROP INDEX IF EXISTS `ux_xy_file_record_short_id` ON `xy_file_record`;
# CREATE INDEX `idx_xy_file_record_short_id` ON `xy_file_record` (`short_id`);
#
# -- 为 collection_name 建索引（便于根据 collection 查找）
# DROP INDEX IF EXISTS `idx_xy_file_record_collection_name` ON `xy_file_record`;
# CREATE INDEX `idx_xy_file_record_collection_name` ON `xy_file_record` (`collection_name`);
#
# -- 4) 为已有行生成 short_id（仅在 short_id 为空时）
# -- 使用 UUID() 生成，保证展示短 id 有值
# UPDATE `xy_file_record`
# SET `short_id` = UUID()
# WHERE `short_id` IS NULL;

-- 5) 可选：如果你想保留一个更短的可读 id（例如 8 字符哈希），你可以另外生成并保存
-- 例如保存前 8 字符的 base36/hex 再转 UUID name 或直接保存短串（注意短串可能冲突）
-- 下面示例仅做参考（如果需要，请在团队讨论后启用）：
-- ALTER TABLE `xy_file_record` ADD COLUMN IF NOT EXISTS `display_id` VARCHAR(16) DEFAULT NULL;
-- UPDATE `xy_file_record` SET `display_id` = LEFT(file_id, 8) WHERE display_id IS NULL;

-- 6) 小结：现在表结构为：
-- id (PK), file_id (canonical hash, unique), short_id (display), file_name, collection_name, kb_id, owner_id, group_id, visibility, create_time, update_time, extra_metadata

-- =========================
-- 额外说明（建议/注意）
-- - canonical hash (file_id) 使用 SHA-256 hex（长度 64）。如果未来改用其他更长的 ID，请同步调整 VARCHAR 长度并考虑对索引性能的影响。
-- - short_id 用于 UI 展示（UUID），避免直接在前端展示长 file_id；如果你需要更短可读（比如 base62(8)），需要在插入/查询处保证冲突检测。
-- - 为避免将大量 metadata 写入 Milvus 的每个文档 metadata 字段（导致 milvus 查询慢或索引大），建议：
--    1) 在 Milvus metadata 只存非常必要的字段（例如 short_id / file_id / doc_id），
--    2) 把大 metadata 存到 DB（`xy_file_record.extra_metadata` 或单独表），并通过 short_id/file_id 关联检索完整信息。
-- - 在复制文档到其它 collection 时，确保传给 Milvus 的 metadata 字段包含 "fileId"（与 DB 中 file_id 对应），不要把整个大 JSON 当成 metadata 写入 Milvus。
-- =========================
# CREATE TABLE IF NOT EXISTS `xy_file_record` (
#                                                 `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键 id',
#                                                 `file_id` VARCHAR(128) NOT NULL COMMENT '文件 ID (canonical hash hex)',
#                                                 `short_id` VARCHAR(32) DEFAULT NULL COMMENT '短 id（用于 UI 展示，非唯一判断依据）',
#                                                 `file_name` VARCHAR(512) DEFAULT NULL COMMENT '原始文件名',
#                                                 `collection_name` VARCHAR(255) DEFAULT NULL COMMENT 'Milvus collection 名',
#                                                 `kb_id` VARCHAR(64) DEFAULT NULL COMMENT '知识库 ID，可空',
#                                                 `owner_id` BIGINT DEFAULT NULL COMMENT '文件所有者 userId，可空',
#                                                 `group_id` BIGINT DEFAULT NULL COMMENT '组织/分组 id，可空',
#                                                 `visibility` TINYINT DEFAULT 1 COMMENT '可见性，1=公开 0=私有',
#                                                 `meta` JSON DEFAULT NULL COMMENT '可扩展的元信息（可选）',
#                                                 `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
#                                                 `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
#                                                 PRIMARY KEY (`id`),
#                                                 UNIQUE KEY `ux_xy_file_record_file_id` (`file_id`),
#                                                 KEY `idx_xy_file_record_short_id` (`short_id`),
#                                                 KEY `idx_xy_file_record_collection` (`collection_name`)
# ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件记录表';
