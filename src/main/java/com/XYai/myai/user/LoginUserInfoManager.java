package com.XYai.myai.user;

import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.pojo.User;
import com.alibaba.ttl.TransmittableThreadLocal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 当前请求线程的用户信息管理器
 * 与 JWT 配合使用：JWT 负责身份验证，本类负责当前请求的上下文传递
 */
@Slf4j
@Component
public class LoginUserInfoManager {

    private static UserMapper userMapper;

    // 只存储 userId（轻量级，避免重复解析JWT）
    private static final ThreadLocal<Long> USER_ID_HOLDER = new TransmittableThreadLocal<>();

    // 懒加载的用户对象缓存
    private static final ThreadLocal<User> USER_CACHE = new TransmittableThreadLocal<>();

    @Autowired
    public void setUserMapper(UserMapper mapper) {
        LoginUserInfoManager.userMapper = mapper;
    }

    @PostConstruct
    public void init() {
        log.info("LoginUserInfoManager initialized");
    }

    /**
     * 设置当前用户ID（从JWT解析后调用）
     */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
        USER_CACHE.remove(); // 清除旧的缓存
    }

    /**
     * 获取当前用户ID（轻量级）
     */
    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    /**
     * 获取当前用户对象（懒加载，首次调用时从DB查询）
     */
    public static User getUser() {
        Long userId = getUserId();
        if (userId == null) {
            return null;
        }

        User user = USER_CACHE.get();
        if (user == null && userMapper != null) {
            // 懒加载：首次使用时才查询数据库
            user = userMapper.selectById(userId);
            USER_CACHE.set(user);
            if (user != null) {
                log.debug("Loaded user {} from database", userId);
            }
        }
        return user;
    }

    /**
     * 清理当前线程的用户信息（请求结束时调用）
     */
    public static void remove() {
        USER_ID_HOLDER.remove();
        USER_CACHE.remove();
    }

    /**
     * 是否有登录用户
     */
    public static boolean isLoggedIn() {
        return getUserId() != null;
    }

    /**
     * 获取用户ID，如果未登录则抛出异常
     */
    public static Long getUserIdOrThrow() {
        Long userId = getUserId();
        if (userId == null) {
            throw new IllegalStateException("No logged in user found");
        }
        return userId;
    }
}