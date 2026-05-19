package com.XYai.myai.rag.graph.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.util.Set;

@Node("Entity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityNode {

    @Id
    private String name;               // 主体或客体名称

    private Set<String> chunkIds;      // 关联的 chunkId 列表
}