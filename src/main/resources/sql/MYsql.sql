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
# INSERT INTO intent_node (name, node_id, description, parent_name, top_k, children_count)
# VALUES
# -- 问候
# ('问候', '聊天-问候', '问候总入口', NULL, NULL, 9),
# ('你好', '聊天-问候', '你好类问候', '问候', 5, 0),
# ('早上好', '聊天-问候', '早晨问候', '问候', 5, 0),
# ('下午好', '聊天-问候', '下午问候', '问候', 5, 0),
# ('晚上好', '聊天-问候', '晚上问候', '问候', 5, 0),
# ('在吗', '聊天-问候', '在线询问', '问候', 5, 0),
# ('你是谁', '聊天-问候', '身份询问', '问候', 5, 0),
# ('谢谢', '聊天-问候', '感谢表达', '问候', 5, 0),
# ('再见', '聊天-问候', '告别表达', '问候', 5, 0),
# ('辛苦了', '聊天-问候', '礼貌问候', '问候', 5, 0),
#
# -- 请假
# ('请假', '人事-请假', '请假总入口', NULL, NULL, 9),
# ('年假', '人事-请假', '年假相关', '请假', 8, 0),
# ('病假', '人事-请假', '病假相关', '请假', 8, 0),
# ('事假', '人事-请假', '事假相关', '请假', 8, 0),
# ('调休', '人事-请假', '调休相关', '请假', 8, 0),
# ('婚假', '人事-请假', '婚假相关', '请假', 8, 0),
# ('产假', '人事-请假', '产假相关', '请假', 8, 0),
# ('陪产假', '人事-请假', '陪产假相关', '请假', 8, 0),
# ('丧假', '人事-请假', '丧假相关', '请假', 8, 0),
# ('远程办公', '人事-请假', '远程办公相关', '请假', 8, 0),
#
# -- 报销
# ('报销', '财务-报销', '报销总入口', NULL, NULL, 9),
# ('交通报销', '财务-报销', '交通报销相关', '报销', 8, 0),
# ('差旅报销', '财务-报销', '差旅报销相关', '报销', 8, 0),
# ('餐补报销', '财务-报销', '餐补报销相关', '报销', 8, 0),
# ('招待报销', '财务-报销', '招待报销相关', '报销', 8, 0),
# ('办公报销', '财务-报销', '办公报销相关', '报销', 8, 0),
# ('培训报销', '财务-报销', '培训报销相关', '报销', 8, 0),
# ('住宿报销', '财务-报销', '住宿报销相关', '报销', 8, 0),
# ('发票', '财务-报销', '发票相关', '报销', 8, 0),
# ('打款时间', '财务-报销', '打款时间相关', '报销', 8, 0),
#
# -- 工资
# ('工资', '人事-工资', '工资总入口', NULL, NULL, 9),
# ('工资条', '人事-工资', '工资条相关', '工资', 8, 0),
# ('发薪日', '人事-工资', '发薪日相关', '工资', 8, 0),
# ('奖金', '人事-工资', '奖金相关', '工资', 8, 0),
# ('个税', '人事-工资', '个税相关', '工资', 8, 0),
# ('社保', '人事-工资', '社保相关', '工资', 8, 0),
# ('公积金', '人事-工资', '公积金相关', '工资', 8, 0),
# ('加班费', '人事-工资', '加班费相关', '工资', 8, 0),
# ('调薪', '人事-工资', '调薪相关', '工资', 8, 0),
# ('年终奖', '人事-工资', '年终奖相关', '工资', 8, 0),
#
# -- 招聘
# ('招聘', '人事-招聘', '招聘总入口', NULL, NULL, 9),
# ('校招', '人事-招聘', '校招相关', '招聘', 8, 0),
# ('社招', '人事-招聘', '社招相关', '招聘', 8, 0),
# ('面试', '人事-招聘', '面试相关', '招聘', 8, 0),
# ('简历', '人事-招聘', '简历相关', '招聘', 8, 0),
# ('offer', '人事-招聘', 'offer相关', '招聘', 8, 0),
# ('背调', '人事-招聘', '背调相关', '招聘', 8, 0),
# ('入职', '人事-招聘', '入职相关', '招聘', 8, 0),
# ('试用期', '人事-招聘', '试用期相关', '招聘', 8, 0),
# ('转正', '人事-招聘', '转正相关', '招聘', 8, 0),
#
# -- 技术支持
# ('技术支持', '技术-支持', '技术支持总入口', NULL, NULL, 9),
# ('登录', '技术-支持', '登录相关', '技术支持', 8, 0),
# ('密码', '技术-支持', '密码相关', '技术支持', 8, 0),
# ('账号冻结', '技术-支持', '账号冻结相关', '技术支持', 8, 0),
# ('软件安装', '技术-支持', '软件安装相关', '技术支持', 8, 0),
# ('电脑故障', '技术-支持', '电脑故障相关', '技术支持', 8, 0),
# ('打印机', '技术-支持', '打印机相关', '技术支持', 8, 0),
# ('VPN', '技术-支持', 'VPN相关', '技术支持', 8, 0),
# ('邮箱', '技术-支持', '邮箱相关', '技术支持', 8, 0),
# ('工单', '技术-支持', '工单相关', '技术支持', 8, 0),
#
# -- 网络
# ('网络', '技术-网络', '网络总入口', NULL, NULL, 9),
# ('断网', '技术-网络', '断网相关', '网络', 8, 0),
# ('速度慢', '技术-网络', '速度慢相关', '网络', 8, 0),
# ('WiFi', '技术-网络', 'WiFi相关', '网络', 8, 0),
# ('内网', '技术-网络', '内网相关', '网络', 8, 0),
# ('外网', '技术-网络', '外网相关', '网络', 8, 0),
# ('DNS', '技术-网络', 'DNS相关', '网络', 8, 0),
# ('代理', '技术-网络', '代理相关', '网络', 8, 0),
# ('防火墙', '技术-网络', '防火墙相关', '网络', 8, 0),
# ('路由器', '技术-网络', '路由器相关', '网络', 8, 0),
#
# -- 账号
# ('账号', '技术-账号', '账号总入口', NULL, NULL, 9),
# ('注册', '技术-账号', '注册相关', '账号', 8, 0),
# ('绑定手机', '技术-账号', '绑定手机相关', '账号', 8, 0),
# ('重置密码', '技术-账号', '重置密码相关', '账号', 8, 0),
# ('修改邮箱', '技术-账号', '修改邮箱相关', '账号', 8, 0),
# ('修改手机号', '技术-账号', '修改手机号相关', '账号', 8, 0),
# ('登录异常', '技术-账号', '登录异常相关', '账号', 8, 0),
# ('权限申请', '技术-账号', '权限申请相关', '账号', 8, 0),
# ('账号注销', '技术-账号', '账号注销相关', '账号', 8, 0),
# ('多因子认证', '技术-账号', '多因子认证相关', '账号', 8, 0),
#
# -- 客服
# ('客服', '服务-支持', '客服总入口', NULL, NULL, 9),
# ('退款', '服务-支持', '退款相关', '客服', 8, 0),
# ('退货', '服务-支持', '退货相关', '客服', 8, 0),
# ('物流', '服务-支持', '物流相关', '客服', 8, 0),
# ('发货', '服务-支持', '发货相关', '客服', 8, 0),
# ('签收异常', '服务-支持', '签收异常相关', '客服', 8, 0),
# ('修改地址', '服务-支持', '修改地址相关', '客服', 8, 0),
# ('取消订单', '服务-支持', '取消订单相关', '客服', 8, 0),
# ('投诉', '服务-支持', '投诉相关', '客服', 8, 0),
# ('售后', '服务-支持', '售后相关', '客服', 8, 0),
#
# -- 教务
# ('教务', '教育-教务', '教务总入口', NULL, NULL, 9),
# ('课程', '教育-教务', '课程相关', '教务', 8, 0),
# ('报名', '教育-教务', '报名相关', '教务', 8, 0),
# ('退课', '教育-教务', '退课相关', '教务', 8, 0),
# ('考试', '教育-教务', '考试相关', '教务', 8, 0),
# ('成绩', '教育-教务', '成绩相关', '教务', 8, 0),
# ('补考', '教育-教务', '补考相关', '教务', 8, 0),
# ('证书', '教育-教务', '证书相关', '教务', 8, 0),
# ('学费', '教育-教务', '学费相关', '教务', 8, 0),
# ('课表', '教育-教务', '课表相关', '教务', 8, 0);
-- 只加 user_id 字段（如果你之前没加）
# ALTER TABLE chat_conversation
#     ADD COLUMN `user_id` bigint NOT NULL COMMENT '用户ID' AFTER `chat_message_id`;
#
# -- 加上索引
# ALTER TABLE chat_conversation
#     ADD INDEX idx_user_conversation (`user_id`,`conversation_id`);





