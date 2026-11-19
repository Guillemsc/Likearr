package com.likearr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

/** Dashboard never returns credentials or file paths. */
final class Dashboard {
    private static final Path HISTORY = Path.of("/state/likearr-activity.jsonl");
    private static final int MAX_EVENTS = 200;
    private Dashboard() {}

    static synchronized void record(String type, String title, String detail) {
        try {
            Files.createDirectories(HISTORY.getParent());

            ObjectNode event = Likearr.JSON.createObjectNode();
            event.put("at", Instant.now().toString());
            event.put("type", type);
            event.put("title", title);
            event.put("detail", detail);

            Files.writeString(
                    HISTORY,
                    Likearr.JSON.writeValueAsString(event) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            List<String> lines = Files.readAllLines(HISTORY, StandardCharsets.UTF_8);

            if (lines.size() > MAX_EVENTS * 2) {
                Files.write(HISTORY, lines.subList(lines.size() - MAX_EVENTS, lines.size()), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            System.err.println("Could not record activity: " + ex.getMessage());
        }
    }

    static void start() throws IOException {
        int port;

        try {
            port = Integer.parseInt(Likearr.env("LIKEARR_WEB_PORT", "7676"));
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("LIKEARR_WEB_PORT must be between 1 and 65535", ex);
        }

        String bindAddress = Likearr.env("LIKEARR_WEB_BIND", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try {
                if ("/api/run".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                    String token = System.getenv("LIKEARR_WEB_TOKEN");
                    String provided = exchange.getRequestHeaders().getFirst("X-Likearr-Token");
                    if (token == null || token.isBlank()) {
                        respond(exchange, 503, "text/plain", "Set LIKEARR_WEB_TOKEN to enable Run now".getBytes(StandardCharsets.UTF_8));
                    } else if (provided == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                        respond(exchange, 401, "text/plain", "Invalid dashboard token".getBytes(StandardCharsets.UTF_8));
                    } else if (!RunCoordinator.start("Manual check")) {
                        respond(exchange, 409, "text/plain", "A run is already in progress".getBytes(StandardCharsets.UTF_8));
                    } else respond(exchange, 202, "text/plain", "Run started".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("/api/status".equals(path)) respond(exchange, 200, "application/json; charset=utf-8", status());
                else if ("/".equals(path)) {
                    try (var in = Dashboard.class.getResourceAsStream("/dashboard.html")) {
                        if (in == null) throw new IOException("Dashboard resource missing");
                        respond(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
                    }
                } else respond(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                System.err.println("Dashboard error: " + ex.getClass().getSimpleName());
                respond(exchange, 500, "text/plain", "Unable to load status".getBytes(StandardCharsets.UTF_8));
            }
        });

        server.start();
        System.out.println("Dashboard listening on " + bindAddress + ":" + port);
    }

    private static synchronized byte[] status() throws IOException {
        ObjectNode response = Likearr.JSON.createObjectNode();
        response.put("runTime", Likearr.env("LIKEARR_RUN_TIME", "01:00"));
        response.put("timezone", ZonedDateTime.now().getZone().getId());
        response.put("running", RunCoordinator.running());

        ArrayNode waiting = response.putArray("pending");

        if (Files.exists(Likearr.STATE)) {
            JsonNode entries = Likearr.JSON.readTree(Likearr.STATE.toFile());
            if (!entries.isArray()) throw new IOException("Invalid pending state");
            for (JsonNode entry : entries) {
                ObjectNode item = waiting.addObject();
                item.put("title", entry.path("name").asText("Unknown track"));
                if (entry.path("created_at").isNumber()) item.put("createdAt", entry.path("created_at").asLong());
            }
        }

        ArrayNode events = response.putArray("activity");

        if (Files.exists(HISTORY)) {
            List<String> lines = Files.readAllLines(HISTORY, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= Math.max(0, lines.size() - MAX_EVENTS); i--) {
                try {
                    events.add(Likearr.JSON.readTree(lines.get(i)));
                } catch (IOException ignored) {
                }
            }
        }

        return Likearr.JSON.writeValueAsBytes(response);
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);

        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
