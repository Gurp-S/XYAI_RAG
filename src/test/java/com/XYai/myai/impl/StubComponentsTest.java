package com.XYai.myai.impl;

import com.XYai.myai.core.dto.QueryRequest;
// ...existing code...
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

public class StubComponentsTest {

    @Test
    public void testStubRetrieverCoordinatorAndOrchestrator() throws ExecutionException, InterruptedException {
        StubRetriever retriever = new StubRetriever();
        StubRetrieverCoordinator coordinator = new StubRetrieverCoordinator();
        StubQueryRewriter rewriter = new StubQueryRewriter();
        StubIntentRecognitionService intentService = new StubIntentRecognitionService();

        // coordinator uses retrievers provided at call time
        com.XYai.myai.core.rag.RAGOrchestrator orch = new StubRagOrchestrator(intentService, rewriter, coordinator);

        // because coordinator.retrieveMulti expects a list of retrievers, we will call retrieveMulti directly with our retriever
        var rr = coordinator.retrieveMulti("hello", 5, java.util.List.of(retriever)).get();
        Assertions.assertFalse(rr.hits().isEmpty());

        // orchestrator as implemented returns stub answer but expects coordinator to internally know retrievers; here we just ensure it runs without throwing
        var resp = orch.answer(new QueryRequest("u1", "hello", "c1", java.util.Map.of())).get();
        Assertions.assertNotNull(resp);
        Assertions.assertTrue(resp.answer().contains("[stub answer]"));
    }
}


