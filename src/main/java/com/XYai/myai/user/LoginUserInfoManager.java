package com.XYai.myai.user;

// import com.alibaba.ttl.TransmittableThreadLocal; // not used currently

import com.XYai.myai.user.POJO.User;
import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 管理当前线程（或可传递线程）中的登录用户信息的工具类。
 * 该类使用 ThreadLocal 存储当前请求的 User 对象，提供便捷的 get/set/remove 操作。
 * 注意：使用后需在请求结束时调用 {@link #remove()} 以避免内存泄漏或线程复用时数据污染。
 */
public class LoginUserInfoManager {
    /**
     * 持有当前线程的用户信息的 ThreadLocal。使用 TransmittableThreadLocal 的版本在需要跨线程池传递时可替换。
     */
    private static final ThreadLocal<User> USER_INFO_HOLDER = new TransmittableThreadLocal<>();

    public static User get() {
        return USER_INFO_HOLDER.get();
    }

    public static void set(User user) {
        if (user == null) {
            remove();
        } else {
            USER_INFO_HOLDER.set(user);
        }
    }

    public static void remove() {
        USER_INFO_HOLDER.remove();
    }
}
