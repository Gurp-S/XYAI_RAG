-- 修复 chat_conversation.created_at 类型与默认值
-- 解决: Field 'updated_at' doesn't have a default value
ALTER TABLE `chat_conversation`
MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE `chat_memory_summary`
MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;