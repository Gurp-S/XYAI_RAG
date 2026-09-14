-- MySQL dump 10.13  Distrib 8.0.43, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: my_ai
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `chat_conversation`
--

DROP TABLE IF EXISTS `chat_conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_conversation` (
  `chat_message_id` bigint NOT NULL COMMENT '雪花ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `conversation_id` bigint DEFAULT NULL COMMENT '会话ID(雪花ID)',
  `assistant_message` text COLLATE utf8mb4_unicode_ci,
  `user_message` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `feedback` int NOT NULL DEFAULT '-1' COMMENT '反馈：1-点赞，0-点踩，-1-未评价',
  `prompt_tokens` int DEFAULT '0' COMMENT 'Prompt tokens',
  `completion_tokens` int DEFAULT '0' COMMENT 'Completion tokens',
  `total_tokens` int DEFAULT '0' COMMENT 'Total tokens',
  `model_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '模型名称',
  `cost_ms` bigint DEFAULT '0' COMMENT '耗时(毫秒)',
  PRIMARY KEY (`chat_message_id`),
  KEY `idx_chat_conversation_id_created` (`conversation_id`,`created_at`),
  KEY `idx_conversation_id_msg` (`conversation_id`,`chat_message_id`),
  KEY `idx_feedback` (`feedback`),
  KEY `idx_user_conversation` (`user_id`,`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_memory_interaction`
--

DROP TABLE IF EXISTS `chat_memory_interaction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_memory_interaction` (
  `conversation_id` bigint NOT NULL COMMENT '会话ID(雪花ID)',
  `user_id` bigint DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `summary_text` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`conversation_id`),
  KEY `idx_title` (`title`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chat_message_id`
--

DROP TABLE IF EXISTS `chat_message_id`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chat_message_id` (
  `chat_message_id` bigint NOT NULL COMMENT '消息ID（雪花ID）',
  `conversation_id` bigint DEFAULT NULL COMMENT '会话ID(雪花ID)',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `model_name` varchar(64) DEFAULT NULL COMMENT '模型名称',
  `prompt_tokens` int DEFAULT '0' COMMENT 'Prompt tokens',
  `completion_tokens` int DEFAULT '0' COMMENT 'Completion tokens',
  `total_tokens` int DEFAULT '0' COMMENT 'Total tokens',
  `cost_ms` bigint DEFAULT '0' COMMENT '耗时(毫秒)',
  `call_type` varchar(32) DEFAULT 'chat' COMMENT '调用类型：chat/mcp/fast/enhance',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`chat_message_id`),
  KEY `idx_token_user_created` (`user_id`,`created_at`),
  KEY `idx_token_conv_created` (`conversation_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token用量记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `intent_node`
--

DROP TABLE IF EXISTS `intent_node`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `intent_node` (
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `kb_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `node_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `parent_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `examples` json DEFAULT NULL,
  `collection_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `mcp_tool_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `top_k` int DEFAULT NULL,
  `prompt_template` text COLLATE utf8mb4_unicode_ci,
  `children_count` int NOT NULL DEFAULT '0',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`name`),
  KEY `idx_children_count` (`children_count`),
  KEY `idx_node_id` (`node_id`),
  KEY `idx_parent_id` (`parent_name`),
  KEY `idx_intent_node_node_id` (`node_id`),
  CONSTRAINT `fk_intent_parent` FOREIGN KEY (`parent_name`) REFERENCES `intent_node` (`name`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `node_record`
--

DROP TABLE IF EXISTS `node_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `node_record` (
  `node_id` varchar(64) NOT NULL COMMENT '节点唯一 ID',
  `trace_id` varchar(64) NOT NULL COMMENT '所属 traceId',
  `node_name` varchar(255) DEFAULT NULL COMMENT '节点名称',
  `node_type` varchar(64) DEFAULT NULL COMMENT '节点类型',
  `cost_time` bigint DEFAULT NULL COMMENT '节点耗时(毫秒)',
  `status` varchar(32) DEFAULT NULL COMMENT '节点状态(SUCCESS/ERROR)',
  `error_message` text COMMENT '节点异常信息',
  `start_time` datetime DEFAULT NULL COMMENT '节点开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '节点结束时间',
  PRIMARY KEY (`node_id`),
  KEY `idx_node_record_trace_id` (`trace_id`),
  KEY `idx_node_record_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 链路节点记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `trace_record`
--

DROP TABLE IF EXISTS `trace_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trace_record` (
  `trace_id` varchar(64) NOT NULL COMMENT '全链路唯一 traceId',
  `name` varchar(255) DEFAULT NULL COMMENT '根节点名称或任务名',
  `start_time` datetime DEFAULT NULL COMMENT '链路开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '节点结束时间',
  `status` varchar(32) DEFAULT NULL COMMENT '链路状态(RUNNING/SUCCESS/ERROR)',
  `error_message` text COMMENT '错误消息',
  `cost_time` bigint DEFAULT NULL COMMENT '链路总耗时（毫秒）',
  PRIMARY KEY (`trace_id`),
  KEY `idx_trace_record_name` (`name`(64)),
  KEY `idx_trace_record_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 全链路追踪记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_chat_message`
--

DROP TABLE IF EXISTS `user_chat_message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_chat_message` (
  `id` bigint NOT NULL COMMENT '消息ID(雪花ID)',
  `conversation_id` bigint NOT NULL COMMENT '会话ID(雪花ID)',
  `target_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '目标类型：user/group',
  `target_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标ID',
  `sender_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '发送方用户ID',
  `sender_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '发送方显示名',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '消息内容(纯文本或JSON)',
  `created_at` bigint NOT NULL COMMENT '创建时间戳(毫秒)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT 'active' COMMENT '消息状态：active/accepted/rejected',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_status` (`status`),
  KEY `idx_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户聊天消息持久化表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_daily_file_usage`
--

DROP TABLE IF EXISTS `xy_daily_file_usage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_daily_file_usage` (
  `date` date NOT NULL COMMENT '日期',
  `use_count` bigint DEFAULT '0' COMMENT '当日文件使用次数',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日文件使用计数表（持久化存储，供仪表盘文件使用趋势图使用）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_file_record`
--

DROP TABLE IF EXISTS `xy_file_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_file_record` (
  `file_chunk_id` varchar(128) NOT NULL,
  `use_count` bigint DEFAULT '0' COMMENT '使用次数',
  `file_name` varchar(255) DEFAULT NULL COMMENT '来源文件名',
  PRIMARY KEY (`file_chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_knowledge_triple`
--

DROP TABLE IF EXISTS `xy_knowledge_triple`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_knowledge_triple` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `head` varchar(500) NOT NULL,
  `relation` varchar(500) NOT NULL,
  `tail` varchar(500) NOT NULL,
  `chunk_id` varchar(100) NOT NULL,
  `file_id` varchar(100) DEFAULT NULL,
  `collection_name` varchar(100) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_model_candidate`
--

DROP TABLE IF EXISTS `xy_model_candidate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_model_candidate` (
  `name` varchar(50) NOT NULL COMMENT '模型唯一标识，如 qwen-max',
  `display_name` varchar(100) DEFAULT NULL COMMENT '前端显示名',
  `api_model` varchar(100) NOT NULL COMMENT '实际调用模型名',
  `priority` int NOT NULL DEFAULT '5',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `weight` int NOT NULL DEFAULT '1',
  `temperature` decimal(3,2) NOT NULL DEFAULT '0.30' COMMENT '温度参数',
  `max_tokens` int NOT NULL DEFAULT '2000' COMMENT '最大 Token',
  `purpose` varchar(200) DEFAULT NULL COMMENT '模型用途说明（如：通用对话、代码生成、翻译）',
  `failure_threshold` int DEFAULT '50',
  `wait_duration_open` bigint DEFAULT '10000',
  `sliding_window_size` int DEFAULT '10',
  `minimum_calls` int DEFAULT '5',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`name`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_refresh_token`
--

DROP TABLE IF EXISTS `xy_refresh_token`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_refresh_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `issued_at` datetime NOT NULL,
  `expires_at` datetime NOT NULL,
  `revoked` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token_hash` (`token_hash`),
  KEY `idx_refresh_token_hash` (`token_hash`),
  KEY `idx_refresh_token_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2059875809986936834 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_system_config`
--

DROP TABLE IF EXISTS `xy_system_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `config_group` varchar(64) NOT NULL COMMENT '配置分组：feature_model/pipeline_node/system',
  `config_key` varchar(128) NOT NULL COMMENT '配置键',
  `config_value` text COMMENT '配置值（JSON格式）',
  `description` varchar(255) DEFAULT NULL COMMENT '中文描述',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config` (`config_group`,`config_key`),
  KEY `idx_config_group` (`config_group`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置持久化表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_system_evaluate`
--

DROP TABLE IF EXISTS `xy_system_evaluate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_system_evaluate` (
  `chat_message_id` bigint NOT NULL COMMENT '对话消息雪花ID',
  `conversation_id` bigint NOT NULL COMMENT '所属会话雪花ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户 ID',
  `overall_score` decimal(5,4) DEFAULT NULL COMMENT '综合 F1',
  `retrieval_score` decimal(5,4) DEFAULT NULL COMMENT '检索 F1',
  `faithfulness_score` decimal(5,4) DEFAULT NULL COMMENT '忠实度 F1',
  `answer_relevance_score` decimal(5,4) DEFAULT NULL COMMENT '答案相关性 F1',
  `completeness_score` decimal(5,4) DEFAULT NULL COMMENT '完整性 F1',
  `rule_score` decimal(5,4) DEFAULT '0.0000' COMMENT '规则评估分数',
  `rerank_score` decimal(5,4) DEFAULT '0.0000' COMMENT '重排评估分数',
  `llm_score` decimal(5,4) DEFAULT '0.0000' COMMENT '大模型评估分数',
  `retrieved_doc_count` int DEFAULT NULL COMMENT '检索到的文档数',
  `latency_ms` bigint DEFAULT NULL COMMENT '响应延迟(毫秒)',
  `model_name` varchar(50) DEFAULT NULL COMMENT '使用的模型名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  `extra_json` json DEFAULT NULL COMMENT '扩展评估指标',
  PRIMARY KEY (`chat_message_id`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 系统评估表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_user`
--

DROP TABLE IF EXISTS `xy_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_user` (
  `id` bigint NOT NULL,
  `group_id` varchar(64) DEFAULT NULL,
  `user_rank` bigint DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `password` varchar(255) NOT NULL,
  `avatar` varchar(500) DEFAULT NULL COMMENT '用户头像URL',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `status` tinyint(1) DEFAULT NULL COMMENT '账号状态：1启用 0禁用',
  PRIMARY KEY (`id`),
  KEY `idx_user_group_id` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_user_evaluate`
--

DROP TABLE IF EXISTS `xy_user_evaluate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_user_evaluate` (
  `message_id` bigint NOT NULL COMMENT '消息雪花ID，主键',
  `conversation_id` bigint NOT NULL COMMENT '会话雪花ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `feedback` tinyint NOT NULL COMMENT '1-赞,0-踩',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`message_id`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户点赞点踩评价表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_user_friend`
--

DROP TABLE IF EXISTS `xy_user_friend`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_user_friend` (
  `user_id` bigint NOT NULL,
  `friend_id` bigint NOT NULL,
  `create_time` datetime DEFAULT NULL,
  `pair_low_id` bigint GENERATED ALWAYS AS (least(`user_id`,`friend_id`)) STORED,
  `pair_high_id` bigint GENERATED ALWAYS AS (greatest(`user_id`,`friend_id`)) STORED,
  PRIMARY KEY (`user_id`,`friend_id`),
  UNIQUE KEY `uk_user_friend_pair` (`pair_low_id`,`pair_high_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `xy_user_group`
--

DROP TABLE IF EXISTS `xy_user_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `xy_user_group` (
  `group_id` varchar(64) NOT NULL,
  `group_name` varchar(100) NOT NULL,
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-05-28 14:04:16
