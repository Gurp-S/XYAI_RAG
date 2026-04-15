package com.XYai.myai.user.service;

import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.POJO.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    @Resource
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = null;
        try {
            Long id = Long.parseLong(username);
            user = userMapper.selectById(id);
        } catch (Exception ignore) {
        }

        if (user == null) {
            LambdaQueryWrapper<User> q = new LambdaQueryWrapper<>();
            q.eq(User::getName, username).or().eq(User::getPhone, username);
            user = userMapper.selectOne(q);
        }

        if (user == null)
            throw new UsernameNotFoundException("User not found: " + username);

        boolean enabled = user.getStatus() == null || user.getStatus();
        List<SimpleGrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_USER"));

        return org.springframework.security.core.userdetails.User
                .withUsername(String.valueOf(user.getId()))
                .password(user.getPassword())
                .authorities(auths)
                .disabled(!enabled)
                .build();
    }
}
