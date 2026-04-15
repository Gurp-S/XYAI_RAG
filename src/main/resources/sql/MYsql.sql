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