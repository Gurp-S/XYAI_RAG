package com.XYai.myai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.XYai.myai.rag.evaluate.pojo.UserEvaluatePOJO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserEvaluateMapper extends BaseMapper<UserEvaluatePOJO> {
}