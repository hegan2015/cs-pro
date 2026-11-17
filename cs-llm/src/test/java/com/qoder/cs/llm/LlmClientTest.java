package com.qoder.cs.llm;

import com.qoder.cs.llm.LlmModel.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {

    @Test
    void unconfiguredClientIsUnavailable() {
        LlmProperties props = new LlmProperties();
        LlmClient client = new LlmClient(props);
        assertFalse(client.available());
        assertThrows(LlmException.class,
                () -> client.chat(List.of(ChatMessage.user("hi")), null, null));
    }

    @Test
    void configuredWhenApiKeyPresent() {
        LlmProperties props = new LlmProperties();
        props.setApiKey("sk-test");
        assertTrue(props.configured());
        assertTrue(new LlmClient(props).available());
    }
}