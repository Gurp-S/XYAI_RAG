package com.XYai.myai.rag.milvus.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilvusCollections {

    //存在判断
    public static final String NO_EXISTS = "NO_EXISTS";
    public static final String EXISTS_NO_ACL = "EXISTS_NO_ACL";
    public static final String EXISTS_HAVE_ACL = "EXISTS_HAVE_ACL";
    public static final String UNKNOWN_EXISTS = "UNKNOWN_EXISTS";
    // 集合名称
    private String collectionName;
}