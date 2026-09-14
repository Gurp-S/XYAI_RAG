package com.XYai.myai.mapper;

import com.XYai.myai.rag.evaluate.pojo.GoldenCasePOJO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GoldenCaseMapper extends BaseMapper<GoldenCasePOJO> {
}
