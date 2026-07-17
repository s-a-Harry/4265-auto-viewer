import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal static file server for the viewer. No Node/Python needed.
 *
 *   java Serve.java [port] [dir]
 */
public class Serve {
  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8265;
    Path root = Paths.get(args.length > 1 ? args[1] : "viewer").toAbsolutePath().normalize();

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", ex -> {
      String p = ex.getRequestURI().getPath();
      if (p.equals("/")) p = "/index.html";
      Path f = root.resolve(p.substring(1)).normalize();
      if (!f.startsWith(root) || !Files.exists(f) || Files.isDirectory(f)) {
        ex.sendResponseHeaders(404, -1);
        ex.close();
        return;
      }
      byte[] body = Files.readAllBytes(f);
      String ct = p.endsWith(".html") ? "text/html; charset=utf-8"
          : p.endsWith(".json") ? "application/json"
          : p.endsWith(".js") ? "text/javascript"
          : "application/octet-stream";
      ex.getResponseHeaders().add("Content-Type", ct);
      ex.getResponseHeaders().add("Cache-Control", "no-store");
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    });
    server.setExecutor(null);
    server.start();
    System.out.println("Serving " + root + "  ->  http://localhost:" + port);
  }
}
