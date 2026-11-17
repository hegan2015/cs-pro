package com.qoder.cs.core.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 会话级 SSE 推送（token/tool/message/done）。POST 同步返回为准，SSE 仅增强体验。 */
@Slf4j
@Component
public class SseHub {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        SseEmitter old = emitters.put(sessionId, emitter);
        if (old != null) {
            old.complete();
        }
        emitter.onCompletion(() -> emitters.remove(sessionId, emitter));
        emitter.onTimeout(() -> emitters.remove(sessionId, emitter));
        emitter.onError(e -> emitters.remove(sessionId, emitter));
        send(sessionId, "hello", Map.of("sessionId", sessionId));
        return emitter;
    }

    public void send(String sessionId, String event, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (Exception e) {
            emitters.remove(sessionId, emitter);
            emitter.complete();
        }
    }
}