package com.XYai.myai.rag.chat.pojo;

import lombok.Builder;
import reactor.core.publisher.Flux;


@Builder
public record StreamResult(Flux<String> content, String modelName) {
}