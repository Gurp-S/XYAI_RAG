package com.XYai.myai.user.userChat.POJO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FileMessage extends ChatMessage {
    Boolean acceptOrReject;
    String collectionName;
    String fileId;
    Integer chunkId;
    Long fromUserId;
    Long toUserId;
    String status;
    Long createdAt;
}
