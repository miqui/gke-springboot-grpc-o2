package dev.miqui.messageservice.cache;

import dev.miqui.messageservice.v1.Message;

import java.util.Optional;
import java.util.UUID;

/** Cache-aside store for GetMessage (key: message id). */
public interface MessageCache {

    Optional<Message> get(UUID id);

    void put(Message message);

    void evict(UUID id);

    /** False once the client has given up for good; a restart is the only recovery. */
    boolean connected();
}
