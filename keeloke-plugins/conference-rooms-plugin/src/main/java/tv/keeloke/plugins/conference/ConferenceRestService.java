package tv.keeloke.plugins.conference;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collection;

/**
 *   POST   /keeloke/v1/conference/rooms?roomId=&maxPublishers=&maxParticipants=  -> create
 *   GET    /keeloke/v1/conference/rooms                                           -> list
 *   GET    /keeloke/v1/conference/rooms/{roomId}                                  -> get
 *   DELETE /keeloke/v1/conference/rooms/{roomId}                                  -> delete
 *   POST   /keeloke/v1/conference/rooms/{roomId}/join?participantId=&role=        -> join (PUBLISHER/SUBSCRIBER)
 *   POST   /keeloke/v1/conference/rooms/{roomId}/leave?participantId=             -> leave
 */
@Path("/keeloke/v1/conference")
public class ConferenceRestService {

    @Context
    private ServletContext servletContext;

    private RoomManager rooms() {
        ApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        return ctx.getBean(RoomManager.class);
    }

    @POST
    @Path("/rooms")
    @Produces(MediaType.APPLICATION_JSON)
    public ConferenceRoom create(@QueryParam("roomId") String roomId,
                                  @QueryParam("maxPublishers") Integer maxPublishers,
                                  @QueryParam("maxParticipants") Integer maxParticipants) {
        return rooms().create(roomId, maxPublishers, maxParticipants);
    }

    @GET
    @Path("/rooms")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<ConferenceRoom> list() {
        return rooms().list();
    }

    @GET
    @Path("/rooms/{roomId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("roomId") String roomId) {
        ConferenceRoom room = rooms().get(roomId);
        return room != null ? Response.ok(room).build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    @DELETE
    @Path("/rooms/{roomId}")
    public void delete(@PathParam("roomId") String roomId) {
        rooms().delete(roomId);
    }

    @POST
    @Path("/rooms/{roomId}/join")
    @Produces(MediaType.APPLICATION_JSON)
    public Response join(@PathParam("roomId") String roomId,
                          @QueryParam("participantId") String participantId,
                          @QueryParam("role") @DefaultValue("SUBSCRIBER") String role) {
        ConferenceRoom.Role r;
        try {
            r = ConferenceRoom.Role.valueOf(role.trim().toUpperCase());
        } catch (Exception e) {
            r = ConferenceRoom.Role.SUBSCRIBER;
        }
        ConferenceRoom.JoinOutcome outcome = rooms().join(roomId, participantId, r);
        int status = outcome == ConferenceRoom.JoinOutcome.ROOM_FULL ? 409 : 200;
        return Response.status(status).entity("{\"outcome\":\"" + outcome.name() + "\"}").build();
    }

    @POST
    @Path("/rooms/{roomId}/leave")
    public Response leave(@PathParam("roomId") String roomId, @QueryParam("participantId") String participantId) {
        boolean removed = rooms().leave(roomId, participantId);
        return Response.ok("{\"removed\":" + removed + "}").build();
    }
}
