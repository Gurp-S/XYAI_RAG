package com.XYai.myai.mapper;

import com.XYai.myai.user.pojo.RefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
    @Select("SELECT id, user_id, token_hash, issued_at, expires_at, revoked FROM xy_refresh_token WHERE token_hash = #{hash} LIMIT 1")
    RefreshToken selectByTokenHash(@Param("hash") String hash);
}
