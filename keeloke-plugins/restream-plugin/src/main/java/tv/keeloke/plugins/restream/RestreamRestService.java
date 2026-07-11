package tv.keeloke.plugins.restream;

import io.antmedia.rest.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API to manage restream destinations, e.g.:
 *
 *   POST /keeloke/v1/restream/destinations
 *   { "streamId": "stream1", "name": "YouTube", "rtmpUrl": "rtmp://a.rtmp.youtube.com/live2/xxxx-xxxx-xxxx-xxxx" }
 *
 *   GET  /keeloke/v1/restream/destinations?streamId=stream1
 *   DELETE /keeloke/v1/restream/destinations/{id}
 *
 * A destination added while the stream is already live is started immediately;
 * one added before the stream starts is picked up automatically on streamStarted().
 */
@RestController
@RequestMapping("/keeloke/v1/restream")
public class RestreamRestService {

    @Autowired
    private RestreamPlugin restreamPlugin;

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @GetMapping("/destinations")
    public List<RestreamDestination> list(@RequestParam(required = false) String streamId) {
        if (streamId != null) {
            return restreamPlugin.getStore().forStream(streamId);
        }
        return restreamPlugin.getStore().all();
    }

    @PostMapping("/destinations")
    public RestreamDestination add(@RequestParam String streamId,
                                    @RequestParam String name,
                                    @RequestParam String rtmpUrl,
                                    @RequestParam(required = false, defaultValue = "false") boolean startNow,
                                    @RequestParam(required = false, defaultValue = "live") String appName) {
        RestreamDestination destination = restreamPlugin.getStore().add(streamId, name, rtmpUrl);
        if (startNow) {
            String sourceUrl = "rtmp://127.0.0.1:" + rtmpPort + "/" + appName + "/" + streamId;
            restreamPlugin.getProcessManager().start(sourceUrl, streamId, destination);
        }
        return destination;
    }

    @DeleteMapping("/destinations/{id}")
    public Result remove(@PathVariable String id, @RequestParam String streamId) {
        restreamPlugin.getProcessManager().stop(streamId, id);
        boolean removed = restreamPlugin.getStore().remove(id);
        Result result = new Result(removed);
        result.setMessage(removed ? "Destination removed" : "Destination not found");
        return result;
    }
}
