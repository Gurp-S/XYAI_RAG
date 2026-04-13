
-- 用户表
CREATE TABLE IF NOT EXISTS xy_user (
    id bigint(20) NOT NULL,
    group_id varchar(64),
    user_rank bigint(20),
    name varchar(100) NOT NULL,
    password varchar(255) NOT NULL,
    PRIMARY KEY (id)
);

-- 用户组表
CREATE TABLE IF NOT EXISTS xy_user_group (
    group_id varchar(64) NOT NULL,
    group_name varchar(100) NOT NULL,
    create_time datetime,
    PRIMARY KEY (group_id)
);

-- 好友关系表（双向存储）
CREATE TABLE IF NOT EXISTS xy_user_friend (
    user_id bigint(20) NOT NULL,
    friend_id bigint(20) NOT NULL,
    create_time datetime,
    pair_low_id bigint(20) GENERATED ALWAYS AS (LEAST(user_id, friend_id)) STORED,
    pair_high_id bigint(20) GENERATED ALWAYS AS (GREATEST(user_id, friend_id)) STORED,
    PRIMARY KEY (user_id, friend_id),
    UNIQUE KEY uk_user_friend_pair (pair_low_id, pair_high_id)
);

-- ==================== 测试数据：组 ====================
INSERT IGNORE INTO xy_user_group(group_id, group_name, create_time) VALUES
('g-1001', '研发组', NOW()),
('g-1002', '产品组', NOW()),
('admins', '管理员组', NOW());

-- ==================== 测试数据：用户 ====================
INSERT IGNORE INTO xy_user(id, group_id, user_rank, name, password) VALUES
(1001, 'admins', 0, 'XY_admin', '123456'),
(1002, 'g-1001', 2, 'bob', '123456'),
(1003, 'g-1001', 2, 'carol', '123456'),
(1004, 'g-1002', 2, 'david', '123456'),
(1005, 'g-1002', 2, 'eric', '123456');

-- ==================== 测试数据：好友关系 ====================
INSERT IGNORE INTO xy_user_friend(user_id, friend_id, create_time) VALUES
(1001, 1002, NOW()),
(1001, 1003, NOW()),
(1004, 1005, NOW());
