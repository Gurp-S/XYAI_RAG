package com.XYai.myai.user;

import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.pojo.User;
import com.alibaba.ttl.TransmittableThreadLocal;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoginUserInfoManager {

    private static UserMapper userMapper;

    private static final ThreadLocal<Long> USER_ID_HOLDER = new TransmittableThreadLocal<>();

    private static final ThreadLocal<User> USER_HOLDER = new TransmittableThreadLocal<>();

    private static final ThreadLocal<String> JTI_HOLDER = new TransmittableThreadLocal<>();

    private static final ThreadLocal<Long> EXP_HOLDER = new TransmittableThreadLocal<>();

    @Autowired
    public void setUserMapper(UserMapper mapper) {
        LoginUserInfoManager.userMapper = mapper;
    }

    @PostConstruct
    public void init() {
        log.info("LoginUserInfoManager initialized");
    }

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void setUser(User user) {
        USER_HOLDER.set(user);
    }

    public static User getUser() {
        User user = USER_HOLDER.get();
        if (user == null) {
            Long userId = getUserId();
            if (userId != null && userMapper != null) {
                user = userMapper.selectById(userId);
                if (user != null) {
                    USER_HOLDER.set(user);
                    log.debug("Loaded user {} from database", userId);
                }
            }
        }
        return user;
    }

    public static void setJti(String jti) {
        JTI_HOLDER.set(jti);
    }

    public static String getJti() {
        return JTI_HOLDER.get();
    }

    public static void setTokenExp(long expEpochSecond) {
        EXP_HOLDER.set(expEpochSecond);
    }

    public static long getTokenExp() {
        Long exp = EXP_HOLDER.get();
        return exp != null ? exp : 0;
    }

    public static void remove() {
        USER_ID_HOLDER.remove();
        USER_HOLDER.remove();
        JTI_HOLDER.remove();
        EXP_HOLDER.remove();
    }

    public static boolean isLoggedIn() {
        return getUserId() != null;
    }

    public static Long getUserIdOrThrow() {
        Long userId = getUserId();
        if (userId == null) {
            throw new IllegalStateException("No logged in user found");
        }
        return userId;
    }
}
