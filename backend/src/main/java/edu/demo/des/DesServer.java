package edu.demo.des;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executors;

public final class DesServer {
    private static final int PORT = 8080;
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final int MAX_CIPHERTEXT_BYTES = MAX_BYTES + 32;
    private static final byte[] MAGIC = "DESFILE1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path webRoot;

    private DesServer(Path projectRoot) {
        this.webRoot = projectRoot.resolve("web").toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        DesServer app = new DesServer(root);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/encrypt", exchange -> app.handleCrypto(exchange, true));
        server.createContext("/api/decrypt", exchange -> app.handleCrypto(exchange, false));
        server.createContext("/", app::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("DES demo ready at http://localhost:" + PORT);
    }

    private void handleCrypto(HttpExchange exchange, boolean encrypt) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Use POST for this endpoint.");
                return;
            }
            int inputLimit = encrypt ? MAX_BYTES : MAX_CIPHERTEXT_BYTES;
            byte[] input = exchange.getRequestBody().readNBytes(inputLimit + 1);
            if (input.length == 0 || input.length > inputLimit) {
                sendText(exchange, 413, "Choose a non-empty file no larger than 25 MiB.");
                return;
            }
            byte[] keyBytes = parseKey(exchange.getRequestHeaders().getFirst("X-Des-Key"));
            byte[] result = encrypt ? encrypt(input, keyBytes) : decrypt(input, keyBytes);
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

    private static byte[] encrypt(byte[] input, byte[] key) throws Exception {
        byte[] iv = new byte[8];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(input);
        byte[] output = Arrays.copyOf(MAGIC, MAGIC.length + iv.length + encrypted.length);
        System.arraycopy(iv, 0, output, MAGIC.length, iv.length);
        System.arraycopy(encrypted, 0, output, MAGIC.length + iv.length, encrypted.length);
        return output;
    }

    private static byte[] decrypt(byte[] input, byte[] key) throws Exception {
        if (input.length <= MAGIC.length + 8 || !Arrays.equals(MAGIC, Arrays.copyOf(input, MAGIC.length))) {
            throw new IllegalArgumentException("This is not a supported DES document. Select a .des file created by this app.");
        }
        byte[] iv = Arrays.copyOfRange(input, MAGIC.length, MAGIC.length + 8);
        byte[] encrypted = Arrays.copyOfRange(input, MAGIC.length + 8, input.length);
        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "DES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
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
}
