package com.ciscotier3.webex_mutedvoice_bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class WebexWebhookController {

    @Value("${webex.bot.token}")
    private String botAccessToken;

    @Value("${webex.target.room.id}")
    private String targetGroupSpaceId;

    @Value("${webex.proxy.host:}") // Optional
    private String proxyHost;

    @Value("${webex.proxy.port:0}")
    private int proxyPort;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String botPersonId;

    @PostConstruct
    public void init() {
        try {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10));

            if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
                System.out.println("Proxy configured: " + proxyHost + ":" + proxyPort);
            }

            httpClient = builder.build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://webexapis.com/v1/people/me"))
                    .header("Authorization", "Bearer " + botAccessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonNode = objectMapper.readTree(response.body());
                botPersonId = jsonNode.get("id").asText();
                System.out.println("Bot initialized. Bot ID: " + botPersonId);
            } else {
                System.err.println("Failed to retrieve bot ID. Status: " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Error during bot init: " + e.getMessage());
        }
    }
    
    public List<String> extractPersonIds(List<Map<String, Object>> items) {
        List<String> personIds = new ArrayList<>();
        for (Map<String, Object> member : items) {
            String personId = (String) member.get("personId");
            personIds.add(personId);
        }
        return personIds;
    }
    
    private boolean isUserBelogsToAnnonymusGroup(String roomId, String MessagingPersonId) {
    	
    	String url = "https://webexapis.com/v1/memberships?roomId=" + roomId;
    	try {
    	 HttpRequest msgRequest = HttpRequest.newBuilder()
                 .uri(URI.create(url))
                 .header("Authorization", "Bearer " + botAccessToken)
                 .GET()
                 .build();

         HttpResponse<String> msgResponse = httpClient.send(msgRequest, HttpResponse.BodyHandlers.ofString());
         ObjectMapper mapper = new ObjectMapper();

         // Parse JSON
         Map<String, Object> json = mapper.readValue(msgResponse.body(), new TypeReference<>() {});
         List<Map<String, Object>> items = (List<Map<String, Object>>) json.get("items");

         // Extract personIds
         for (Map<String, Object> item : items) {
             String personId = (String) item.get("personId");
             if(personId.equals(MessagingPersonId) ) {
            	 return true;
             }
         }
    	}
    	catch(Exception ex) {
    		return false;
    	}
    	return false;
    }

    @PostMapping("/webex-webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody JsonNode payload) {
        System.out.println("Received Webhook:\n" + payload.toPrettyString());

        try {
            String resource = payload.at("/resource").asText();
            String event = payload.at("/event").asText();

            if ("messages".equals(resource) && "created".equals(event)) {
                String messageId = payload.at("/data/id").asText();
                String roomId = payload.at("/data/roomId").asText();
                String personId = payload.at("/data/personId").asText();

                if (botPersonId != null && botPersonId.equals(personId)) {
                    System.out.println("Ignoring message sent by self.");
                    return ResponseEntity.ok("Ignored self message.");
                }
                
                System.out.println("botPersonId : "+botPersonId +";personId : "+personId);
               
                if(!isUserBelogsToAnnonymusGroup(targetGroupSpaceId, personId)){
                	System.out.println("Ignoring message sent by outsider.");
                    return ResponseEntity.ok("Ignoring message sent by outsider.");
                }
                
                HttpRequest msgRequest = HttpRequest.newBuilder()
                        .uri(URI.create("https://webexapis.com/v1/messages/" + messageId))
                        .header("Authorization", "Bearer " + botAccessToken)
                        .GET()
                        .build();

                HttpResponse<String> msgResponse = httpClient.send(msgRequest, HttpResponse.BodyHandlers.ofString());

                if (msgResponse.statusCode() == 200) {
                    JsonNode messageJson = objectMapper.readTree(msgResponse.body());
                    String roomType = messageJson.get("roomType").asText();
                    String userMessageText = messageJson.get("text").asText();

                    if ("direct".equals(roomType)) {
                        // Create anonymous message
                        ObjectNode postBody = objectMapper.createObjectNode();
                        postBody.put("roomId", targetGroupSpaceId);
                        postBody.put("text", "👤 Anonymous: " + userMessageText);

                        HttpRequest postRequest = HttpRequest.newBuilder()
                                .uri(URI.create("https://webexapis.com/v1/messages"))
                                .header("Authorization", "Bearer " + botAccessToken)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(postBody.toString()))
                                .build();

                        HttpResponse<String> postResponse = httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString());

                        if (postResponse.statusCode() == 200) {
                            System.out.println("Successfully posted anonymous message.");
                        } else {
                            System.err.println("Failed to post anonymous message. Status: " + postResponse.statusCode());
                        }
                    }
                }
            }

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("Webhook error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
