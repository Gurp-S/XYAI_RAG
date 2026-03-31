package com.XYai.myai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot 应用启动入口。
 */
@SpringBootApplication
public class MyAiApplication {

    /**
     * 应用主入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MyAiApplication.class, args);
    }

    private static final Logger log = LoggerFactory.getLogger(MyAiApplication.class);

//    /**
//   在应用启动时初始化 Redis 中的意图树（仅在未初始化时写入，具有幂等性）。
//   使用 StringRedisTemplate 操作 Redis。若 Redis 不可用会记录错误并继续启动。
//    @Bean
//    public ApplicationRunner redisIntentTreeInitializer(StringRedisTemplate stringRedisTemplate) {
//        return args -> {
//            final String hashKey = "intent:tree:";
//            try {
//                Long size = stringRedisTemplate.opsForHash().size(hashKey);
//                if (size > 0) {
//                    log.info("Redis intent tree already initialized ({} entries) - skipping initialization.", size);
//                    return;
//                }
//
//                log.info("Initializing Redis intent tree at key '{}'", hashKey);
//
//                Map<String, String> map = new HashMap<>();
//                // 保留并规范化原有示例及扩展节点（field -> nodeId）
//                map.put("根节点", "root");
//                map.put("提问", "root-query");
//                map.put("公司", "root-query-company");
//                map.put("人事", "root-query-company-personnel");
//                map.put("请假", "root-query-company-personnel-leave");
//                map.put("闲聊", "root-chat");
//                map.put("用户", "root-chat-user");
//
//                // 公司信息
//                map.put("公司简介", "root-query-company-intro");
//                map.put("公司地址", "root-query-company-location");
//                map.put("公司电话", "root-query-company-contact");
//                map.put("营业时间", "root-query-company-hours");
//
//                // 人事/考勤/薪酬/报销
//                map.put("考勤", "root-query-company-personnel-attendance");
//                map.put("打卡", "root-query-company-personnel-attendance-punch");
//                map.put("迟到早退", "root-query-company-personnel-attendance-exceptions");
//                map.put("薪酬", "root-query-company-payroll");
//                map.put("工资", "root-query-company-payroll");
//                map.put("报销", "root-query-company-reimbursement");
//                map.put("报销流程", "root-query-company-reimbursement-process");
//
//                // 请假细分
//                map.put("请假流程", "root-query-company-personnel-leave-process");
//                map.put("请假审批", "root-query-company-personnel-leave-approval");
//                map.put("病假", "root-query-company-personnel-leave-sick");
//                map.put("事假", "root-query-company-personnel-leave-personal");
//                map.put("年假", "root-query-company-personnel-leave-annual");
//                map.put("婚假", "root-query-company-personnel-leave-marriage");
//                map.put("产假", "root-query-company-personnel-leave-maternity");
//                map.put("陪产假", "root-query-company-personnel-leave-paternity");
//                map.put("调休", "root-query-company-personnel-leave-compensatory");
//
//                // 招聘/入职
//                map.put("招聘", "root-query-company-recruit");
//                map.put("入职", "root-query-company-onboarding");
//                map.put("入职流程", "root-query-company-onboarding-process");
//                map.put("离职流程", "root-query-company-offboarding");
//
//                // IT 支持
//                map.put("IT 支持", "root-query-company-it");
//                map.put("电脑故障", "root-query-company-it-hardware");
//                map.put("网络问题", "root-query-company-it-network");
//                map.put("无法上网", "root-query-company-it-network");
//                map.put("VPN", "root-query-company-it-vpn");
//                map.put("邮箱问题", "root-query-company-it-email");
//                map.put("重置密码", "root-query-company-it-password-reset");
//
//                // 文档检索
//                map.put("员工手册", "root-query-company-doc-handbook");
//                map.put("政策文件", "root-query-company-doc-policy");
//                map.put("财务报表", "root-query-company-doc-finance");
//                map.put("合同模板", "root-query-company-doc-contracts");
//
//                // 审批
//                map.put("审批流程", "root-query-company-approval");
//                map.put("请假审批流程", "root-query-company-approval-leave");
//                map.put("报销审批流程", "root-query-company-approval-reimbursement");
//
//                // 产品/售后
//                map.put("产品介绍", "root-query-company-product");
//                map.put("产品价格", "root-query-company-product-price");
//                map.put("售后服务", "root-query-company-support");
//
//                // 闲聊子节点
//                map.put("问候", "root-chat-greeting");
//                map.put("你好", "root-chat-greeting");
//                map.put("早上好", "root-chat-greeting");
//                map.put("再见", "root-chat-farewell");
//                map.put("谢谢", "root-chat-acknowledge");
//                map.put("笑话", "root-chat-joke");
//                map.put("天气", "root-chat-weather");
//
//                // 同义词示例
//                map.put("请假怎么填", "root-query-company-personnel-leave-process");
//                map.put("如何请假", "root-query-company-personnel-leave-process");
//                map.put("怎么报销", "root-query-company-reimbursement-process");
//
//                // 写入 hash
//                stringRedisTemplate.opsForHash().putAll(hashKey, map);
//
//                // 写入 parent->children 关系（使用 set，便于查找）
//                // root 的一层
//                stringRedisTemplate.opsForSet().add("intent:children:root", "root-query", "root-chat");
//
//                // root-query 的子类
//                stringRedisTemplate.opsForSet().add("intent:children:root-query", "root-query-company");
//
//                // 公司下的人事/文档/产品等
//                stringRedisTemplate.opsForSet().add("intent:children:root-query-company", "root-query-company-personnel", "root-query-company-doc-handbook", "root-query-company-product");
//
//                // 人事下的请假/考勤/薪酬
//                stringRedisTemplate.opsForSet().add("intent:children:root-query-company-personnel", "root-query-company-personnel-leave", "root-query-company-personnel-attendance", "root-query-company-payroll");
//
//                // 请假下的各类请假
//                stringRedisTemplate.opsForSet().add("intent:children:root-query-company-personnel-leave",
//                        "root-query-company-personnel-leave-process",
//                        "root-query-company-personnel-leave-approval",
//                        "root-query-company-personnel-leave-sick",
//                        "root-query-company-personnel-leave-personal",
//                        "root-query-company-personnel-leave-annual",
//                        "root-query-company-personnel-leave-marriage",
//                        "root-query-company-personnel-leave-maternity",
//                        "root-query-company-personnel-leave-paternity",
//                        "root-query-company-personnel-leave-compensatory");
//
//                // 闲聊的子节点
//                stringRedisTemplate.opsForSet().add("intent:children:root-chat", "root-chat-greeting", "root-chat-farewell", "root-chat-joke", "root-chat-weather", "root-chat-acknowledge");
//
//                log.info("Redis intent tree initialized successfully ({} entries).", stringRedisTemplate.opsForHash().size(hashKey));
//            } catch (Exception ex) {
//                log.error("Failed to initialize Redis intent tree: {}", ex.getMessage(), ex);
//            }
//        };
//    }

}
