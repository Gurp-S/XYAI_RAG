// AclCommandEvent.java
package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class TraceLogEvent {
    private String eventId;
    private String traceId;
    private String nodeId;
    private String nodeName;
    private String eventType;  // start, finish, node, error, warn
    private String message;
    private Long costNanos;
    private Long timestamp;
    private Long userId;
}