package com.XYai.myai.Config;

import com.XYai.myai.User.LoginUserInfoManager;
import com.XYai.myai.User.User;
import com.XYai.myai.mapper.UserMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;


@Slf4j
public class LoginHandlerInterceptor implements HandlerInterceptor {
    private static final String USER_ID = "userId";

    @Resource
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object obj) {

        // 先不校验权限数据 如果不是映射到方法直接通过
        if (!(obj instanceof HandlerMethod)) {
            return true;
        }

        String userId = request.getHeader(USER_ID);
        // userId校验
        log.info(userId);
        if (StringUtils.isEmpty(userId)) {
            return true;
        }
        try {
            // 从 http 请求头中取出 userId,因为系统在网关层就验证过token，并解析token获取userId并放入请求头里，所以这里直接从请求头中获取。如果不是这样的逻辑则建议换成一个根据toeken获取用户信息的方法
            User userInfo = userMapper.selectById(Long.valueOf(userId));
            if(userInfo == null){
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
        //移除线程变量, 防止内存泄漏
        LoginUserInfoManager.remove();
    }

}
