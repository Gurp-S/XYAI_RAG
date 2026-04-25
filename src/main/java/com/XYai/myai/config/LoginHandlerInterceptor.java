package com.XYai.myai.config;

import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录处理器拦截器。
 * 负责从请求头中提取用户 ID 内容，并将其存入当前线程的用户信息上下文中。
 */
@Slf4j
public class LoginHandlerInterceptor implements HandlerInterceptor {
    /**
     * 登录拦截器：从请求头提取 userId 并加载用户信息到线程上下文（LoginUserInfoManager）。
     * <p>
     * 注：当前实现假定在网关层已经验证 token 并将 userId 放入请求头中。
     */
    private static final String USER_ID = "userId";

    @Resource
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object obj) {

        /*
         * 在请求处理前尝试从 header 中读取 userId 并将对应的 User 加入线程上下文。
         * 若 header 中不包含 userId 则允许通过（不强制认证），若解析失败则抛出 SecurityException。
         */

        // 先不校验权限数据 如果不是映射到方法直接通过
        if (!(obj instanceof HandlerMethod)) {
            return true;
        }

        String userId = request.getHeader(USER_ID);
        // userId校验
        if (StringUtils.isEmpty(userId)) {
            return true;
        }
        try {
            // 从 http 请求头中取出
            // userId,因为系统在网关层就验证过token，并解析token获取userId并放入请求头里，所以这里直接从请求头中获取。如果不是这样的逻辑则建议换成一个根据toeken获取用户信息的方法
            User userInfo = userMapper.selectById(Long.valueOf(userId));
            if (userInfo == null) {
                throw new NullPointerException();
            }
            LoginUserInfoManager.set(userInfo);
        } catch (NullPointerException e) {
            LoginUserInfoManager.remove();
            throw new SecurityException("解析用户信息失败");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object obj, Exception e) {
        // 移除线程变量，防止内存泄漏或线程复用时信息残留
        LoginUserInfoManager.remove();
    }

}
