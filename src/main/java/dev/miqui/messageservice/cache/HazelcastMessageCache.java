package dev.miqui.messageservice.cache;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import dev.miqui.messageservice.v1.Message;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Hazelcast map {@code messages} (key: message id, value: the serialized protobuf Message), on the
 * standalone member in k8s/hazelcast-deployment.yaml, so the cache tier is independent of app pod
 * restarts and scaling.
 *
 * <p>Deliberately no Near Cache: a client-side Near Cache went stale across pods after an evict,
 * because the invalidation broadcast didn't reliably reach every other client. No TTL: entries are
 * evicted after a successful update/delete, and after a stale-version conflict.
 *
 * <p>Hazelcast has no tracing instrumentation, so every call is an Observation: a child span of
 * the RPC (otherwise a cache hit looks like a request with no DB spans and an unexplained gap) and
 * the {@code hazelcast_cache} timer.
 */
public class HazelcastMessageCache implements MessageCache {

    public static final String MAP_NAME = "messages";

    private final HazelcastInstance client;
    private final IMap<String, byte[]> map;
    private final ObservationRegistry observations;

    public HazelcastMessageCache(HazelcastInstance client, ObservationRegistry observations) {
        this.client = client;
        this.map = client.getMap(MAP_NAME);
        this.observations = observations;
    }

    @Override
    public Optional<Message> get(UUID id) {
        return observe("get", obs -> {
            byte[] raw = map.get(id.toString());
            obs.lowCardinalityKeyValue("cache.hit", String.valueOf(raw != null));
            return Optional.ofNullable(raw).map(HazelcastMessageCache::parse);
        });
    }

    @Override
    public void put(Message message) {
        observe("set", obs -> {
            map.set(message.getId(), message.toByteArray());
            return null;
        });
    }

    @Override
    public void evict(UUID id) {
        observe("delete", obs -> {
            map.delete(id.toString());
            return null;
        });
    }

    @Override
    public boolean connected() {
        return client.getLifecycleService().isRunning();
    }

    private <T> T observe(String operation, Function<Observation, T> call) {
        Observation obs = Observation.createNotStarted("hazelcast.cache", observations)
                .contextualName("hazelcast." + operation)
                .lowCardinalityKeyValue("db.system", "hazelcast")
                .lowCardinalityKeyValue("db.operation", operation)
                .lowCardinalityKeyValue("cache.map", MAP_NAME)
                // Same key set on every operation, so the timer's series stay uniform.
                .lowCardinalityKeyValue("cache.hit", "none");
        return obs.observe(() -> call.apply(obs));
    }

    private static Message parse(byte[] raw) {
        try {
            return Message.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unreadable cache entry", e);
        }
    }
}
