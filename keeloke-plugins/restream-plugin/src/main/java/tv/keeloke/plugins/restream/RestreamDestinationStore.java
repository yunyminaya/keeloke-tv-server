package tv.keeloke.plugins.restream;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persists restream destinations to a JSON file (conf/keeloke-restream-destinations.json)
 * so they survive a server restart. Deliberately simple - a single JSON file is enough
 * for a self-hosted single-node deployment; a real multi-node cluster would move this
 * to a shared store (see the cluster-registry-plugin for the Redis-backed groundwork).
 */
public class RestreamDestinationStore {

    private final File storeFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RestreamDestination> destinations = new ConcurrentHashMap<>();

    public RestreamDestinationStore(String storeFilePath) {
        this.storeFile = new File(storeFilePath);
        load();
    }

    private synchronized void load() {
        if (!storeFile.exists()) {
            return;
        }
        try {
            List<RestreamDestination> list = mapper.readValue(storeFile,
                    mapper.getTypeFactory().constructCollectionType(List.class, RestreamDestination.class));
            for (RestreamDestination d : list) {
                destinations.put(d.getId(), d);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + storeFile.getAbsolutePath(), e);
        }
    }

    private synchronized void persist() {
        try {
            storeFile.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(storeFile, new ArrayList<>(destinations.values()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + storeFile.getAbsolutePath(), e);
        }
    }

    public RestreamDestination add(String streamId, String name, String rtmpUrl) {
        RestreamDestination d = new RestreamDestination(UUID.randomUUID().toString(), streamId, name, rtmpUrl);
        destinations.put(d.getId(), d);
        persist();
        return d;
    }

    public boolean remove(String id) {
        boolean removed = destinations.remove(id) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    public List<RestreamDestination> forStream(String streamId) {
        return destinations.values().stream()
                .filter(d -> d.isEnabled() && streamId.equals(d.getStreamId()))
                .collect(Collectors.toList());
    }

    public List<RestreamDestination> all() {
        return new ArrayList<>(destinations.values());
    }
}
