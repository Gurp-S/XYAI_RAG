package com.XYai.myai.User;

// import com.alibaba.ttl.TransmittableThreadLocal; // not used currently

import com.XYai.myai.User.POJO.User;

/**
 * 管理当前线程（或可传递线程）中的登录用户信息的工具类。
 * 该类使用 ThreadLocal 存储当前请求的 User 对象，提供便捷的 get/set/remove 操作。
 * 注意：使用后需在请求结束时调用 {@link #remove()} 以避免内存泄漏或线程复用时数据污染。
 */
public class LoginUserInfoManager {
    /**
     * 持有当前线程的用户信息的 ThreadLocal。使用 TransmittableThreadLocal 的版本在需要跨线程池传递时可替换。
     */
    public static final ThreadLocal<User> USER_INFO_HOLDER = new InheritableThreadLocal<>();

    /**
     * 获取当前线程持有的用户 ID。
     *
     * @return 当前登录用户的 ID，若未设置可能抛出 NullPointerException（调用前应保证已 set）
     */
    public static Long getId(){
        return USER_INFO_HOLDER.get().getId();
    }

    /**
     * 获取当前线程持有的完整 User 对象。
     *
     * @return 当前线程的 User，若未设置则返回 null
     */
    public static User get() {
        return USER_INFO_HOLDER.get();
    }

    /**
     * 在当前线程中设置 User 对象。
     *
     * @param loginUserInfoContext 要设置的 User 对象
     */
    public static void set(User loginUserInfoContext) {
        USER_INFO_HOLDER.set(loginUserInfoContext);
    }

    /**
     * 清除当前线程中持有的 User 信息，避免线程复用导致信息泄漏。
     */
    public static void remove() {
        USER_INFO_HOLDER.remove();
    }
}
