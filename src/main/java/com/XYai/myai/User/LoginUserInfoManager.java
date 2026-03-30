package com.XYai.myai.User;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 */
public class LoginUserInfoManager {
    public static final ThreadLocal<User> USER_INFO_HOLDER = new ThreadLocal<>();

    public static Long getId(){
        return USER_INFO_HOLDER.get().getId();
    }

    public static User get() {
        return USER_INFO_HOLDER.get();
    }

    public static void set(User loginUserInfoContext) {
        USER_INFO_HOLDER.set(loginUserInfoContext);
    }

    public static void remove() {
        USER_INFO_HOLDER.remove();
    }
}
