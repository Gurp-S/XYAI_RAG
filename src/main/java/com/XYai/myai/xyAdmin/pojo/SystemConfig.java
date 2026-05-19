package com.XYai.myai.xyAdmin.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统配置持久化实体
 * 存储所有运行时配置：功能-模型分配、管道节点配置等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_system_config")
public class SystemConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 配置分组：feature_model / pipeline_node / system */
    @TableField("config_group")
    private String configGroup;

    /** 配置键 */
    @TableField("config_key")
    private String configKey;

    /** 配置值（JSON格式） */
    @TableField("config_value")
    private String configValue;

    /** 中文描述 */
    @TableField("description")
    private String description;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
