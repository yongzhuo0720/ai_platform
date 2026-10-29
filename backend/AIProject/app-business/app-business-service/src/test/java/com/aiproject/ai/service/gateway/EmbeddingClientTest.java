package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingClientTest {
    private AiUpstreamStub upstream;
    private EmbeddingClient client;
    private EmbeddingClient.ResolvedEmbedding config;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new AiUpstreamStub();
        client = new EmbeddingClient();
        config = new EmbeddingClient.ResolvedEmbedding(upstream.baseUrl(), "test-key", "embed", "test");
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    @Test
    void vectorsMatchInputsWhenUpstreamResultsAreReordered() throws Exception {
        upstream.responseBody = "{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}";

        List<double[]> vectors = client.embed(config, Arrays.asList("first", "second"));

        assertArrayEquals(new double[]{1, 0}, vectors.get(0));
        assertArrayEquals(new double[]{0, 1}, vectors.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":2,\"embedding\":[2]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[]},{\"index\":1,\"embedding\":[]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[1,2]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[\"bad\"]},{\"index\":1,\"embedding\":[1]}]}",
            "{\"data\":[{\"index\":0,\"embedding\":[1]}]}",
            "{\"data\":[{\"embedding\":[1]},{\"embedding\":[2]}]}"
    })
    void rejectsResponsesThatCannotBeMatchedToInputs(String response) {
        upstream.responseBody = response;

        assertThrows(IllegalStateException.class, () -> client.embed(config, Arrays.asList("first", "second")));
    }
}
