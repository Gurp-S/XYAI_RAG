package com.XYai.myai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.XYai.myai.rag.chat.pojo.ModelCandidateEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ModelCandidateMapper extends BaseMapper<ModelCandidateEntity> {

    /** 查询所有启用的模型 */
    @Select("SELECT * FROM xy_model_candidate WHERE enabled = 1 ORDER BY priority ASC")
    List<ModelCandidateEntity> findAllEnabled();

    /** 查询全部（按优先级升序） */
    @Select("SELECT * FROM xy_model_candidate ORDER BY priority ASC")
    List<ModelCandidateEntity> findAllOrderByPriority();

    /** 根据名称查找 */
    @Select("SELECT * FROM xy_model_candidate WHERE name = #{name}")
    ModelCandidateEntity findByName(String name);

    /**
     * 根据名称删除
     */
    @Delete("DELETE FROM xy_model_candidate WHERE name = #{name}")
    void deleteByName(String name);
}