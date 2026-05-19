package com.XYai.myai.security.model;

import com.XYai.myai.user.pojo.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class SecurityUser implements UserDetails {
    @Getter
    private final User user;
    private final List<? extends GrantedAuthority> authorities;

    public SecurityUser(User user) {
        this.user = user;
        this.authorities = buildAuthorities(user);
    }

    private static List<? extends GrantedAuthority> buildAuthorities(User user) {
        Long rank = user.getUserRank();
        if (rank == null || rank == 2) {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        } else if (rank == 0) {
            return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
        } else if (rank == 1) {
            return List.of(new SimpleGrantedAuthority("ROLE_ORG_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getId().toString();
    }

    public Long getUserId() {
        return user.getId();
    }

    @Override
    public boolean isAccountNonExpired() {
        return UserDetails.super.isAccountNonExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return UserDetails.super.isAccountNonLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return UserDetails.super.isCredentialsNonExpired();
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == null || user.getStatus();
    }
}
