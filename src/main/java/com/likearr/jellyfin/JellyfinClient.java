package com.likearr.jellyfin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JellyfinClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String url, token, user;

    public JellyfinClient(String url, String token, String username) throws Exception {
        if (url == null || url.isBlank()) throw new IllegalStateException("Missing LIKEARR_JELLYFIN_URL");

        this.url = url;
        this.token = Objects.requireNonNull(token, "Missing LIKEARR_JELLYFIN_API_KEY");

        Objects.requireNonNull(username, "Missing LIKEARR_JELLYFIN_USERNAME");
        if (token.isBlank() || username.isBlank()) throw new IllegalStateException("Jellyfin API key or user is empty");

        List<String> ids = new ArrayList<>();
        for (JsonNode u : request("GET", "/Users", Map.of()))
            if (u.path("Name").asText().equalsIgnoreCase(username)) ids.add(u.path("Id").asText());
        if (ids.size() != 1) throw new IllegalStateException("Jellyfin user not found or ambiguous");

        user = ids.getFirst();
    }

    public String userId() { return user; }

    public List<JsonNode> search(String name) throws Exception {
        List<JsonNode> all = new ArrayList<>();

        int start = 0;
        while (true) {
            JsonNode result = request(
                    "GET",
                    "/Users/" + user + "/Items",
                    Map.of(
                            "Recursive",
                            "true",
                            "IncludeItemTypes",
                            "Audio",
                            "SearchTerm",
                            name,
                            "Fields",
                            "Path",
                            "Limit",
                            "200",
                            "StartIndex",
                            Integer.toString(start)));

            JsonNode items = items(result);
            items.forEach(all::add);
            start += items.size();

            if (items.isEmpty() || start >= result.path("TotalRecordCount").asInt(start)) return all;
        }
    }

    public JsonNode favorites(int start) throws Exception {
        return request(
                "GET",
                "/Users/" + user + "/Items",
                Map.of(
                        "Recursive",
                        "true",
                        "IncludeItemTypes",
                        "Audio",
                        "Filters",
                        "IsFavorite",
                        "Fields",
                        "Path,AlbumArtist,Album",
                        "StartIndex",
                        Integer.toString(start),
                        "Limit",
                        "200"));
    }

    public static JsonNode items(JsonNode result) throws IOException {
        JsonNode items = result.path("Items");
        if (!items.isArray()) throw new IOException("Jellyfin response missing Items");
        return items;
    }

    public void refreshLibrary() throws Exception {
        request("POST", "/Library/Refresh", Map.of());
    }

    public void setFavorite(String id, boolean favorite) throws Exception {
        request(favorite ? "POST" : "DELETE", "/Users/" + user + "/FavoriteItems/" + id, Map.of());
    }
    public boolean isFavorite(String id) throws Exception {
        return request("GET", "/Users/" + user + "/Items/" + id, Map.of()).path("UserData").path("IsFavorite").asBoolean();
    }

    private JsonNode request(String method, String path, Map<String, String> params) throws Exception {
        StringBuilder target = new StringBuilder(url.replaceAll("/$", "")).append(path);

        boolean first = true;
        for (var entry : params.entrySet()) {
            target.append(first ? '?' : '&');
            first = false;
            target.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        var req = HttpRequest.newBuilder(URI.create(target.toString())).timeout(Duration.ofSeconds(30))
                .header("Authorization", "MediaBrowser Token=\"" + token + "\"").header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();

        var response = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Jellyfin " + method + " " + path + " returned HTTP " + response.statusCode());

        return response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
    }
}
