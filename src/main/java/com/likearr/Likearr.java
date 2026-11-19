package com.likearr;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class Likearr {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path MUSIC = Path.of(env("LIKEARR_MUSIC_ROOT", "/music")).toAbsolutePath().normalize();
    static final Path EXPLO = Path.of(env("LIKEARR_EXPLO_ROOT", MUSIC.resolve("Explo").toString())).toAbsolutePath().normalize();
    static final Path STATE = Path.of("/state/promote-favorites-pending.json");
    static final Path JELLYFIN_MUSIC = Path.of(env("LIKEARR_JELLYFIN_MUSIC_ROOT", "/media/Music")).normalize();
    static final String URL = System.getenv("LIKEARR_JELLYFIN_URL");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    final String token, user;
    final ArrayNode pending;

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    Likearr() throws Exception {
        if (URL == null || URL.isBlank()) throw new IllegalStateException("Missing LIKEARR_JELLYFIN_URL");
        token = Objects.requireNonNull(System.getenv("LIKEARR_JELLYFIN_API_KEY"), "Missing LIKEARR_JELLYFIN_API_KEY");
        String username = Objects.requireNonNull(System.getenv("LIKEARR_JELLYFIN_USERNAME"), "Missing LIKEARR_JELLYFIN_USERNAME");
        if (token.isBlank() || username.isBlank()) throw new IllegalStateException("Jellyfin API key or user is empty");

        List<String> ids = new ArrayList<>();
        for (JsonNode u : request("GET", "/Users", Map.of()))
            if (u.path("Name").asText().equalsIgnoreCase(username)) ids.add(u.path("Id").asText());

        if (ids.size() != 1) throw new IllegalStateException("Jellyfin user not found or ambiguous");

        user = ids.getFirst();
        JsonNode state = Files.exists(STATE) ? JSON.readTree(STATE.toFile()) : JSON.createArrayNode();
        if (!(state instanceof ArrayNode)) throw new IllegalStateException("Invalid pending state");

        pending = (ArrayNode) state;
    }

    JsonNode request(String method, String path, Map<String, String> params) throws Exception {
        StringBuilder url = new StringBuilder(URL.replaceAll("/$", "")).append(path);
        boolean first = true;
        for (var entry : params.entrySet()) {
            url.append(first ? '?' : '&');
            first = false;
            url.append(URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(30)).header("Authorization", "MediaBrowser Token=\"" + token + "\"").header("Accept", "application/json").method(method, HttpRequest.BodyPublishers.noBody()).build();
        var response = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Jellyfin " + method + " " + path + " returned HTTP " + response.statusCode());
        return response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static boolean safeComponent(String s) {
        return s != null && !s.isBlank() && !s.equals(".") && !s.equals("..") && !s.contains("/") && !s.contains("\\") && s.codePoints().noneMatch(c -> c < 32);
    }

    static Path hostPath(String raw) {
        Path p = Path.of(raw).normalize();
        return p.startsWith(JELLYFIN_MUSIC) ? MUSIC.resolve(JELLYFIN_MUSIC.relativize(p)).normalize() : p;
    }

    static String sha(Path p) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[1024 * 1024];
            int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        return HexFormat.of().formatHex(d.digest());
    }

    void save() throws Exception {
        Files.createDirectories(STATE.getParent());
        Path tmp = Files.createTempFile(STATE.getParent(), ".pending-", ".json");

        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            JSON.writeValue(tmp.toFile(), pending);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, STATE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    List<JsonNode> search(String name) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        int start = 0;
        while (true) {
            JsonNode r = request(
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

            JsonNode items = r.path("Items");
            if (!items.isArray()) throw new IOException("Jellyfin response missing Items");

            items.forEach(all::add);
            start += items.size();

            if (items.isEmpty() || start >= r.path("TotalRecordCount").asInt(start)) return all;
        }
    }

    boolean duplicate(JsonNode item) throws Exception {
        String title = norm(item.path("Name").asText());
        Set<String> artists = new HashSet<>();
        item.path("Artists").forEach(a -> artists.add(norm(a.asText())));
        artists.remove("");
        if (title.isEmpty() || artists.isEmpty())
            throw new IOException("Missing title or track artist; cannot check duplicates");
        for (JsonNode x : search(item.path("Name").asText())) {
            if (!norm(x.path("Name").asText()).equals(title)) continue;
            boolean artist = false;
            for (JsonNode a : x.path("Artists")) if (artists.contains(norm(a.asText()))) artist = true;
            if (!artist) continue;
            if (x.path("Path").asText().isBlank()) throw new IOException("Matching Jellyfin track has no path");
            Path p = hostPath(x.path("Path").asText());
            if (p.startsWith(MUSIC) && !p.startsWith(EXPLO)) {
                System.out.println("SKIP existing library track: " + p);
                return true;
            }
        }
        return false;
    }

    Path directory(Path parent, String component) throws IOException {
        if (!safeComponent(component)) throw new IOException("Unsafe or missing artist/album name");
        List<Path> matches;
        if (Files.isDirectory(parent)) try (Stream<Path> s = Files.list(parent)) {
            matches = s.filter(Files::isDirectory).filter(p -> norm(p.getFileName().toString()).equals(norm(component))).toList();
        }
        else matches = List.of();
        if (matches.size() > 1) throw new IOException("Ambiguous folder: " + component);
        Path result = matches.isEmpty() ? parent.resolve(component) : matches.getFirst();
        if (Files.isSymbolicLink(result)) throw new IOException("Symbolic link folder: " + result);
        return result;
    }

    void reconcile() throws Exception {
        for (JsonNode e : new ArrayList<>(toList(pending))) {
            if (!user.equals(e.path("user").asText())) continue;
            if (!e.hasNonNull("source_id")) {
                System.err.println("Legacy pending entry requires manual review: " + e.path("target").asText());
                continue;
            }

            Path source = Path.of(e.path("source").asText()).toAbsolutePath().normalize(), target = Path.of(e.path("target").asText()).toAbsolutePath().normalize();
            String hash = e.path("sha256").asText(), oldId = e.path("source_id").asText();

            if (!source.startsWith(EXPLO) || !target.startsWith(MUSIC) || target.startsWith(EXPLO) || oldId.isBlank() || hash.isBlank()) {
                System.err.println("Unsafe pending record; skipped");
                continue;
            }

            if (Files.exists(source) && (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !sha(source).equals(hash))) {
                System.err.println("Original changed: " + source);
                continue;
            }

            if (Files.exists(target) && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !sha(target).equals(hash))) {
                System.err.println("Destination differs: " + target);
                continue;
            }

            if (!Files.exists(target)) {
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("Both original and destination missing: " + target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                boolean created = false;
                try {
                    try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        created = true;
                        in.transferTo(out);
                    }
                    if (!sha(source).equals(hash) || !sha(target).equals(hash))
                        throw new IOException("Copy verification failed");
                } catch (Exception ex) {
                    if (created) Files.deleteIfExists(target);
                    throw ex;
                }

                System.out.println("Verified copy; original remains liked: " + target);
                request("POST", "/Library/Refresh", Map.of());
            }

            List<JsonNode> matching = search(e.path("name").asText()).stream().filter(x -> !x.path("Path").asText().isBlank() && hostPath(x.path("Path").asText()).equals(target)).toList();

            if (matching.size() != 1) {
                System.out.println("PENDING Jellyfin indexing: " + target);
                continue;
            }


            String id = matching.getFirst().path("Id").asText();

            request("POST", "/Users/" + user + "/FavoriteItems/" + id, Map.of());

            if (!request("GET", "/Users/" + user + "/Items/" + id, Map.of()).path("UserData").path("IsFavorite").asBoolean()) {
                System.err.println("New favorite not verified: " + target);
                continue;
            }

            if (Files.exists(source)) {
                if (!sha(source).equals(hash) || !sha(target).equals(hash)) {
                    System.err.println("Files changed, original retained: " + source);
                    continue;
                }
                if (!oldId.equals(id)) {
                    JsonNode old = request("GET", "/Users/" + user + "/Items/" + oldId, Map.of());
                    if (old.path("UserData").path("IsFavorite").asBoolean())
                        request("DELETE", "/Users/" + user + "/FavoriteItems/" + oldId, Map.of());
                    if (request("GET", "/Users/" + user + "/Items/" + oldId, Map.of()).path("UserData").path("IsFavorite").asBoolean()) {
                        System.err.println("Old favorite remains: " + source);
                        continue;
                    }
                }
                Files.delete(source);
            }

            pending.remove(indexOf(pending, e));
            save();

            System.out.println("MOVED and favorite verified: " + target);
        }
    }

    static List<JsonNode> toList(ArrayNode a) {
        List<JsonNode> list = new ArrayList<>();
        a.forEach(list::add);
        return list;
    }

    static int indexOf(ArrayNode a, JsonNode n) {
        for (int i = 0; i < a.size(); i++) if (a.get(i) == n) return i;
        throw new IllegalStateException();
    }

    int run(boolean apply) throws Exception {
        if (apply) {
            double cutoff = Instant.now().getEpochSecond() - 30.0 * 86400;
            for (JsonNode e : new ArrayList<>(toList(pending)))
                if (e.path("created_at").isNumber() && e.path("created_at").asDouble() > 0 && e.path("created_at").asDouble() <= cutoff) {
                    System.out.println("Expired pending record; music untouched: " + e.path("target").asText());
                    pending.remove(indexOf(pending, e));
                }
            if (Files.exists(STATE)) save();
            reconcile();
        }
        int start = 0, found = 0, errors = 0, added = 0;
        while (true) {
            JsonNode result = request("GET", "/Users/" + user + "/Items", Map.of("Recursive", "true", "IncludeItemTypes", "Audio", "Filters", "IsFavorite", "Fields", "Path,AlbumArtist,Album", "StartIndex", Integer.toString(start), "Limit", "200"));
            JsonNode items = result.path("Items");
            if (!items.isArray()) throw new IOException("Jellyfin response missing Items");
            for (JsonNode item : items) {
                String raw = item.path("Path").asText();
                if (raw.isBlank()) continue;
                Path source = hostPath(raw).toAbsolutePath().normalize();
                if (!source.startsWith(EXPLO) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Path real = source.toRealPath();
                if (!real.startsWith(EXPLO.toRealPath())) continue;
                source = real;
                found++;
                Path actualSource = source;
                if (toList(pending).stream().anyMatch(x -> actualSource.toString().equals(x.path("source").asText())))
                    continue;
                try {
                    String artist = item.path("AlbumArtist").asText();
                    if (artist.isBlank() && item.path("Artists").isArray() && !item.path("Artists").isEmpty())
                        artist = item.path("Artists").get(0).asText();
                    String album = item.path("Album").asText();
                    if (!safeComponent(artist) || !safeComponent(album) || !safeComponent(source.getFileName().toString()))
                        throw new IOException("Missing or unsafe artist/album");
                    if (duplicate(item)) continue;
                    Path dir = directory(directory(MUSIC, artist), album);
                    Path target = dir.resolve(source.getFileName()).normalize();
                    if (Files.exists(target)) {
                        System.out.println("EXISTS: " + target);
                        continue;
                    }
                    System.out.println((apply ? "PLAN " : "WOULD MOVE ") + source + " -> " + target);
                    if (apply) {
                        ObjectNode e = pending.addObject();
                        e.put("source", source.toString());
                        e.put("target", target.toString());
                        e.put("name", item.path("Name").asText());
                        e.put("sha256", sha(source));
                        e.put("user", user);
                        e.put("source_id", item.path("Id").asText());
                        e.put("created_at", Instant.now().getEpochSecond());
                        save();
                        added++;
                    }
                } catch (Exception ex) {
                    errors++;
                    System.err.println("ERROR " + source + ": " + ex.getMessage());
                }
            }
            start += items.size();
            if (items.isEmpty() || start >= result.path("TotalRecordCount").asInt(start)) break;
        }
        if (apply && added > 0) reconcile();
        System.out.println("Favorites in Explo: " + found + "; new pending: " + added + "; errors: " + errors);
        return errors == 0 ? 0 : 1;
    }

    static void scheduled() throws Exception {
        LocalDate last = null;
        while (!Thread.currentThread().isInterrupted()) {
            ZonedDateTime now = ZonedDateTime.now();
            if (now.getHour() == 1 && now.getMinute() == 0 && !now.toLocalDate().equals(last)) {
                last = now.toLocalDate();
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(45);
                do {
                    try {
                        Likearr app = new Likearr();
                        if (app.run(true) != 0 || app.pending.isEmpty()) break;
                    } catch (Exception e) {
                        System.err.println("Favorites check failed: " + e.getMessage());
                        break;
                    }
                    Thread.sleep(60_000);
                } while (System.nanoTime() < deadline);
            }
            Thread.sleep(5000);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Waiting for 01:00 local time.");
            scheduled();
        } else if (args.length == 1 && (args[0].equals("--dry-run") || args[0].equals("--apply")))
            System.exit(new Likearr().run(args[0].equals("--apply")));
        else {
            System.err.println("Usage: java -jar likearr.jar [--dry-run|--apply]");
            System.exit(2);
        }
    }
}
