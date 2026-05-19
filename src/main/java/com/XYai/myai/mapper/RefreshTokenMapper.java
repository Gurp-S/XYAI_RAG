package com.XYai.myai.mapper;

import com.XYai.myai.user.pojo.RefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
    @Select("select * from xy_refresh_token where token_hash = #{hash} limit 1")
    RefreshToken selectByTokenHash(@Param("hash") String hash);
}
