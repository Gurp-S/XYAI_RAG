package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Neo4jEvent {
    private String eventId;
    private String commandType;  // INSERT_TRIPLES, DELETE_RELATIONS, TRIGGER_MAINTENANCE
    private Map<String, String> triples;  // 三元组JSON
    private List<String> chunkIds;  // DELETE_RELATIONS 用
}