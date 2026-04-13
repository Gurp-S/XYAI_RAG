package com.XYai.myai.RAG.Milvus.POJO;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
@Builder
public class UserMilvus {

    public String userId;

    public Map<String,String> collectionName;


}
