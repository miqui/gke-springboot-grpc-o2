package dev.miqui.messageservice;

import dev.miqui.messageservice.cache.MessageCache;
import dev.miqui.messageservice.v1.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link MessageCache} that records every call, standing in for Hazelcast. */
public class RecordingMessageCache implements MessageCache {

    public record Call(String op, UUID id) {
    }

    public final Map<UUID, Message> data = new ConcurrentHashMap<>();
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public volatile boolean connected = true;

    @Override
    public Optional<Message> get(UUID id) {
        calls.add(new Call("get", id));
        return Optional.ofNullable(data.get(id));
    }

    @Override
    public void put(Message message) {
        UUID id = UUID.fromString(message.getId());
        calls.add(new Call("set", id));
        data.put(id, message);
    }

    @Override
    public void evict(UUID id) {
        calls.add(new Call("evict", id));
        data.remove(id);
    }

    @Override
    public boolean connected() {
        return connected;
    }

    public void reset() {
        data.clear();
        calls.clear();
        connected = true;
    }
}
