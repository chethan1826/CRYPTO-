package edu.demo.des;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class DesServer {
    private static final int PORT = 8080;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final int MAX_CIPHERTEXT_BYTES = MAX_BYTES + 4096 + 32;
    private static final int MAX_AUTH_BODY_BYTES = 8192;
    private static final int BCRYPT_COST = 12;
    private static final long SESSION_LIFETIME_MS = 60L * 60L * 1000L;
    private static final byte[] LEGACY_MAGIC = "DESFILE1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC = "DESFILE2".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path webRoot;
    private final Path userStore;
    private final Map<String, String> passwordHashes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private DesServer(Path projectRoot) throws IOException {
        this.webRoot = projectRoot.resolve("web").toAbsolutePath().normalize();
        this.userStore = projectRoot.resolve("build/users.properties").toAbsolutePath().normalize();
        loadUsers();
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        DesServer app = new DesServer(root);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/register", app::handleRegister);
        server.createContext("/api/login", app::handleLogin);
        server.createContext("/api/reauth", app::handleReauth);
        server.createContext("/api/logout", app::handleLogout);
        server.createContext("/api/session", app::handleSession);
        server.createContext("/api/encrypt", exchange -> app.handleCrypto(exchange, true));
        server.createContext("/api/decrypt", exchange -> app.handleCrypto(exchange, false));
        server.createContext("/", app::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("DES demo ready at http://localhost:" + PORT);
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST to create an account.");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String email = normalizeEmail(form.get("email"));
            String password = validatePassword(form.get("password"));
            String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST));
            synchronized (passwordHashes) {
                if (passwordHashes.containsKey(email)) {
                    sendText(exchange, 409, "An account with this email already exists. Sign in instead.");
                    return;
                }
                saveUser(email, passwordHash);
                passwordHashes.put(email, passwordHash);
            }
            createSession(exchange, email);
            sendJson(exchange, 201, "{\"authenticated\":true,\"email\":\"" + email + "\"}");
        } catch (IllegalArgumentException ex) {
            sendText(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            sendText(exchange, 500, "Account creation failed. Please try again.");
        } finally {
            exchange.close();
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST to sign in.");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String email = normalizeEmail(form.get("email"));
            String password = form.get("password");
            if (password == null || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                sendText(exchange, 401, "Email or password is incorrect.");
                return;
            }
            String storedHash = passwordHashes.get(email);
            boolean valid = false;
            try {
                valid = storedHash != null && BCrypt.checkpw(password, storedHash);
            } catch (IllegalArgumentException ignored) {
                // A malformed stored hash is treated as an invalid login.
            }
            if (!valid) {
                sendText(exchange, 401, "Email or password is incorrect.");
                return;
            }
            createSession(exchange, email);
            sendJson(exchange, 200, "{\"authenticated\":true,\"email\":\"" + email + "\"}");
        } catch (IllegalArgumentException ex) {
            sendText(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            sendText(exchange, 500, "Sign in failed. Please try again.");
        } finally {
            exchange.close();
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST to sign out.");
                return;
            }
            String token = readSessionToken(exchange);
            if (token != null) sessions.remove(token);
            exchange.getResponseHeaders().add("Set-Cookie", "DES_SESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            sendJson(exchange, 200, "{\"authenticated\":false}");
        } finally {
            exchange.close();
        }
    }

    private void handleReauth(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST to verify your account password.");
                return;
            }
            String email = authenticatedEmail(exchange);
            if (email == null) {
                sendJson(exchange, 401, "{\"error\":\"Sign in again to continue.\"}");
                return;
            }
            Map<String, String> form = readForm(exchange);
            String password = form.get("password");
            if (password == null || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                sendText(exchange, 401, "Account password is incorrect.");
                return;
            }
            String storedHash = passwordHashes.get(email);
            boolean valid = false;
            try {
                valid = storedHash != null && BCrypt.checkpw(password, storedHash);
            } catch (IllegalArgumentException ignored) {
                // Malformed stored hashes fail closed.
            }
            if (!valid) {
                sendText(exchange, 401, "Account password is incorrect.");
                return;
            }
            sendJson(exchange, 200, "{\"verified\":true}");
        } catch (IllegalArgumentException ex) {
            sendText(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            sendText(exchange, 500, "Account verification failed. Please try again.");
        } finally {
            exchange.close();
        }
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use GET to check the session.");
                return;
            }
            String email = authenticatedEmail(exchange);
            if (email == null) sendJson(exchange, 200, "{\"authenticated\":false}");
            else sendJson(exchange, 200, "{\"authenticated\":true,\"email\":\"" + email + "\"}");
        } finally {
            exchange.close();
        }
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Submit the sign-in form using the supported form encoding.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_AUTH_BODY_BYTES + 1);
        if (bytes.length > MAX_AUTH_BODY_BYTES) throw new IllegalArgumentException("The sign-in request is too large.");
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String item : raw.split("&")) {
            if (item.isEmpty()) continue;
            int separator = item.indexOf('=');
            String key = URLDecoder.decode(separator < 0 ? item : item.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(separator < 0 ? "" : item.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("The sign-in form contains duplicate fields.");
        }
        return values;
    }

    private static String normalizeEmail(String value) {
        if (value == null) throw new IllegalArgumentException("Enter your email address.");
        String email = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (email.length() > 254 || !email.matches("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}$")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return email;
    }

    private static String validatePassword(String value) {
        if (value == null || value.length() < 8) throw new IllegalArgumentException("Use a password with at least 8 characters.");
        if (value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Bcrypt supports passwords up to 72 UTF-8 bytes. Choose a shorter password.");
        }
        return value;
    }

    private void createSession(HttpExchange exchange, String email) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        sessions.put(token, new Session(email, System.currentTimeMillis() + SESSION_LIFETIME_MS));
        exchange.getResponseHeaders().add("Set-Cookie", "DES_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private String authenticatedEmail(HttpExchange exchange) {
        String token = readSessionToken(exchange);
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return session.email();
    }

    private static String readSessionToken(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith("DES_SESSION=")) return trimmed.substring("DES_SESSION=".length());
        }
        return null;
    }

    private void loadUsers() throws IOException {
        if (!Files.exists(userStore)) return;
        Properties stored = new Properties();
        try (InputStream input = Files.newInputStream(userStore)) {
            stored.load(input);
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String encodedEmail : stored.stringPropertyNames()) {
            try {
                String email = new String(decoder.decode(encodedEmail), StandardCharsets.UTF_8);
                String hash = stored.getProperty(encodedEmail);
                if (email.matches("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}$") && hash != null && hash.startsWith("$2")) {
                    passwordHashes.put(email, hash);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed entries rather than accepting them as credentials.
            }
        }
    }

    private void saveUser(String email, String passwordHash) throws IOException {
        Properties stored = new Properties();
        for (Map.Entry<String, String> entry : passwordHashes.entrySet()) {
            stored.setProperty(Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)), entry.getValue());
        }
        stored.setProperty(Base64.getUrlEncoder().withoutPadding().encodeToString(email.getBytes(StandardCharsets.UTF_8)), passwordHash);
        Files.createDirectories(userStore.getParent());
        Path temporary = userStore.resolveSibling("users.properties.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            stored.store(output, "DES demo accounts; passwords are stored as BCrypt hashes");
        }
        try {
            Files.move(temporary, userStore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, userStore, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Session {
        private final String email;
        private final long expiresAt;

        private Session(String email, long expiresAt) {
            this.email = email;
            this.expiresAt = expiresAt;
        }

        private String email() { return email; }
        private long expiresAt() { return expiresAt; }
    }

    private void handleCrypto(HttpExchange exchange, boolean encrypt) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST for this endpoint.");
                return;
            }
            if (authenticatedEmail(exchange) == null) {
                sendJson(exchange, 401, "{\"error\":\"Sign in to continue.\"}");
                return;
            }
            int inputLimit = encrypt ? MAX_BYTES : MAX_CIPHERTEXT_BYTES;
            byte[] input = exchange.getRequestBody().readNBytes(inputLimit + 1);
            if (input.length == 0 || input.length > inputLimit) {
                sendText(exchange, 413, "Choose a non-empty file no larger than 25 MiB.");
                return;
            }
            byte[] keyBytes = parseKey(exchange.getRequestHeaders().getFirst("X-Des-Key"));
            byte[] result;
            if (encrypt) {
                String originalName = decodeMetadataHeader(exchange.getRequestHeaders().getFirst("X-Original-Name"), "document");
                String originalType = decodeMetadataHeader(exchange.getRequestHeaders().getFirst("X-Original-Type"), "application/octet-stream");
                result = encrypt(input, keyBytes, originalName, originalType);
            } else {
                DecryptionResult decrypted = decrypt(input, keyBytes);
                result = decrypted.bytes();
                if (decrypted.originalName() != null) {
                    exchange.getResponseHeaders().set("X-Original-Name", encodeMetadataHeader(decrypted.originalName()));
                    exchange.getResponseHeaders().set("X-Original-Type", encodeMetadataHeader(decrypted.originalType()));
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result);
        } catch (IllegalArgumentException ex) {
            sendText(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            sendText(exchange, 422, "Decryption failed. Check the key and file, then try again.");
        } finally {
            exchange.close();
        }
    }

    private static byte[] parseKey(String value) {
        if (value == null || value.length() != 8) {
            throw new IllegalArgumentException("The DES key must be exactly 8 printable ASCII characters.");
        }
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != 8 || !value.equals(new String(bytes, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("The DES key must use printable ASCII characters only.");
        }
        for (byte b : bytes) {
            if (b < 0x20 || b > 0x7e) throw new IllegalArgumentException("The DES key must use printable ASCII characters only.");
        }
        return bytes;
    }

    private static byte[] encrypt(byte[] input, byte[] key, String originalName, String originalType) throws Exception {
        byte[] nameBytes = originalName.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = originalType.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 2048 || typeBytes.length > 512) {
            throw new IllegalArgumentException("The original filename or file type is too long.");
        }
        byte[] plaintext = new byte[4 + nameBytes.length + typeBytes.length + input.length];
        ByteBuffer.wrap(plaintext, 0, 4).putShort((short) nameBytes.length).putShort((short) typeBytes.length);
        int metadataOffset = 4;
        System.arraycopy(nameBytes, 0, plaintext, metadataOffset, nameBytes.length); metadataOffset += nameBytes.length;
        System.arraycopy(typeBytes, 0, plaintext, metadataOffset, typeBytes.length); metadataOffset += typeBytes.length;
        System.arraycopy(input, 0, plaintext, metadataOffset, input.length);
        byte[] iv = new byte[8];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] output = new byte[MAGIC.length + 8 + encrypted.length];
        int offset = 0;
        System.arraycopy(MAGIC, 0, output, offset, MAGIC.length); offset += MAGIC.length;
        System.arraycopy(iv, 0, output, offset, iv.length); offset += iv.length;
        System.arraycopy(encrypted, 0, output, offset, encrypted.length);
        return output;
    }

    private static DecryptionResult decrypt(byte[] input, byte[] key) throws Exception {
        if (input.length <= MAGIC.length + 8 || input.length < MAGIC.length ||
                (!Arrays.equals(MAGIC, Arrays.copyOf(input, MAGIC.length)) && !Arrays.equals(LEGACY_MAGIC, Arrays.copyOf(input, LEGACY_MAGIC.length)))) {
            throw new IllegalArgumentException("This is not a supported DES document. Select a .des file created by this app.");
        }
        boolean hasMetadata = Arrays.equals(MAGIC, Arrays.copyOf(input, MAGIC.length));
        byte[] iv = Arrays.copyOfRange(input, MAGIC.length, MAGIC.length + 8);
        int ciphertextOffset = MAGIC.length + 8;
        byte[] encrypted = Arrays.copyOfRange(input, ciphertextOffset, input.length);
        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "DES"), new IvParameterSpec(iv));
        byte[] plaintext = cipher.doFinal(encrypted);
        if (!hasMetadata) return new DecryptionResult(plaintext, null, null);
        if (plaintext.length < 4) throw new IllegalArgumentException("The encrypted file metadata is invalid.");
        ByteBuffer lengths = ByteBuffer.wrap(plaintext, 0, 4);
        int nameLength = Short.toUnsignedInt(lengths.getShort());
        int typeLength = Short.toUnsignedInt(lengths.getShort());
        int contentOffset = 4 + nameLength + typeLength;
        if (nameLength > 2048 || typeLength > 512 || contentOffset > plaintext.length) {
            throw new IllegalArgumentException("The encrypted file metadata is invalid.");
        }
        String originalName = safeFilename(new String(plaintext, 4, nameLength, StandardCharsets.UTF_8));
        String originalType = safeMediaType(new String(plaintext, 4 + nameLength, typeLength, StandardCharsets.UTF_8));
        return new DecryptionResult(Arrays.copyOfRange(plaintext, contentOffset, plaintext.length), originalName, originalType);
    }

    private static String decodeMetadataHeader(String encoded, String fallback) {
        if (encoded == null || encoded.isEmpty()) return fallback;
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return fallback.equals("document") ? safeFilename(value) : safeMediaType(value);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String encodeMetadataHeader(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeFilename(String value) {
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\u0000]", "_");
        if (name.isEmpty() || name.length() > 512) return "document";
        return name;
    }

    private static String safeMediaType(String value) {
        if (value != null && value.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) return value.toLowerCase(java.util.Locale.ROOT);
        return "application/octet-stream";
    }

    private static final class DecryptionResult {
        private final byte[] bytes;
        private final String originalName;
        private final String originalType;
        private DecryptionResult(byte[] bytes, String originalName, String originalType) {
            this.bytes = bytes; this.originalName = originalName; this.originalType = originalType;
        }
        private byte[] bytes() { return bytes; }
        private String originalName() { return originalName; }
        private String originalType() { return originalType; }
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed.");
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.equals("/")) requestPath = "/index.html";
            Path file = webRoot.resolve(requestPath.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not found.");
                return;
            }
            String type = Files.probeContentType(file);
            exchange.getResponseHeaders().set("Content-Type", type == null ? "application/octet-stream" : type);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            byte[] body = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
