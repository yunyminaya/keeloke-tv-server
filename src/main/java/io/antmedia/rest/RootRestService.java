package io.antmedia.rest;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.antmedia.rest.model.Version;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.servers.Server;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;



@OpenAPIDefinition(
	    info = @Info(
	        description = "Keeloke TV Server REST API Reference",
	        version = "V2.0",
	        title = "Keeloke TV Server REST API Reference",
	        contact = @Contact(name = "Keeloke TV Info", email = "info@keeloke.com", url = "https://keeloke.com"),
	        license = @License(name = "Apache 2.0", url = "http://www.apache.org")),
	    servers = {@Server(	description = "test server",
				url = "https://test.keeloke.com:5443/Sandbox/rest/")},
	    externalDocs = @ExternalDocumentation(url = "https://keeloke.com")
	)

@Component
@Path("/v2")
public class RootRestService extends RestServiceBase {
	
	
	protected static Logger logger = LoggerFactory.getLogger(RootRestService.class);
	
	@Operation(summary = "Returns the Keeloke TV Server Version",
		    description = "Retrieves the version information of the Keeloke TV Server.",
		    responses = {
		        @ApiResponse(responseCode = "200", description = "Keeloke TV Server Version",
		                     content = @Content(
		                         mediaType = "application/json",
		                         schema = @Schema(implementation = Version.class)
		                     ))
		    }
		)
	@GET
	@Path("/version")
	@Produces(MediaType.APPLICATION_JSON)
	public Version getVersion() {
		return getSoftwareVersion();
	}

	public static class RoomInfo{
		private String roomId;
		private Map<String,String> streamDetailsMap;
		private long endDate = 0;
		private long startDate = 0;

		public RoomInfo(String roomId, Map<String, String> streamDetailsMap) {
			this.roomId = roomId;
			this.streamDetailsMap = streamDetailsMap;
		}

		public String getRoomId() {

			return roomId;
		}

		public void setEndDate(long endDate) { this.endDate = endDate; }
		
		public void setStartDate(long startDate) { this.startDate = startDate; }
		
		public long getEndDate() { return endDate; }

		public long getStartDate() { return startDate;}

		public void setRoomId(String roomId) {
			this.roomId = roomId;
		}

		public Map<String,String> getStreamDetailsMap() {
			return streamDetailsMap;
		}

		public void setStreamDetailsMap(Map<String,String> streamDetailsMap) {
			this.streamDetailsMap = streamDetailsMap;
		}
	}
}