import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Mini "Permit Lookup" app -- mirrors the layers in AMANDA:
 *
 *   Browser (HTML/JS/CSS)
 *        |  GET /api/permits?street=...   <-- REST-style, JSON, stateless (Module 7)
 *        v
 *   PermitApiHandler (Java)               <-- stands in for a Servlet (Module 6)
 *        |  JDBC
 *        v
 *   H2 database (SQL)                     <-- stands in for Oracle/SQL Server (Module 8)
 *
 * H2 is a real, standard SQL relational database written in pure Java -- it's not Oracle,
 * so it won't use PL/SQL syntax, but the JDBC calls, the SQL, and the join/where logic
 * are the same concepts used in Oracle for a production app. Near the bottom of this
 * file there's an H2 "ALIAS" example, which is H2's version of a stored procedure --
 * conceptually the same idea as the PL/SQL procedure, just Java-based
 * instead of PL/SQL-based.
 *
 * No Tomcat, no Maven, no internet needed once you have the JDK + the h2 jar.
 */
public class PermitApp {

    // Kept open for the life of the app since this is an in-memory DB --
    // closing the last connection would wipe the data.
    static Connection conn;

    public static void main(String[] args) throws Exception {
        setupDatabase();
        startServer();
    }

    // ---------------------------------------------------------------
    // Module 8: Data layer -- schema, sample data, and a "stored procedure"
    // ---------------------------------------------------------------
    static void setupDatabase() throws Exception {
        // In-memory DB named "permitdb"; DB_CLOSE_DELAY=-1 keeps it alive as long as
        // at least one connection reference is held (ours, in the static field above).
        conn = DriverManager.getConnection("jdbc:h2:mem:permitdb;DB_CLOSE_DELAY=-1");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE addresses (
                    address_id  INT PRIMARY KEY,
                    street      VARCHAR(100),
                    city        VARCHAR(50)
                )
            """);

            stmt.execute("""
                CREATE TABLE permits (
                    permit_id    INT PRIMARY KEY,
                    address_id   INT REFERENCES addresses(address_id),
                    permit_type  VARCHAR(50),
                    status       VARCHAR(20),
                    created_date DATE DEFAULT CURRENT_DATE
                )
            """);

            stmt.execute("INSERT INTO addresses VALUES (1, '1221 SW 4th Ave', 'Portland')");
            stmt.execute("INSERT INTO addresses VALUES (2, '350 NE Sandy Blvd', 'Portland')");
            
            stmt.execute("INSERT INTO permits VALUES (101, 1, 'Electrical', 'OPEN', CURRENT_DATE)");
            stmt.execute("INSERT INTO permits VALUES (102, 1, 'Plumbing', 'APPROVED', CURRENT_DATE - 30)");
            stmt.execute("INSERT INTO permits VALUES (103, 2, 'Roofing', 'OPEN', CURRENT_DATE - 2)");
            stmt.execute("INSERT INTO permits VALUES (104, 1, 'Mechanical', 'CLOSED', CURRENT_DATE)");
            stmt.execute("INSERT INTO permits VALUES (105, 2, 'Mechanical', 'APPROVED', CURRENT_DATE)");

            // --- H2's version of a stored procedure: an ALIAS pointing at a Java method.
            // Oracle would use PL/SQL for this; H2 delegates to a Java static method instead.
            // Same underlying idea: encapsulate a query behind a named, callable unit
            // that lives with the database rather than being duplicated in every app.
            stmt.execute("""
                CREATE ALIAS IF NOT EXISTS COUNT_OPEN_PERMITS FOR "PermitApp.countOpenPermits"
            """);
        }

        System.out.println("Database ready: 2 addresses, 3 permits loaded.");
    }

    // Called from SQL via the ALIAS above: SELECT COUNT_OPEN_PERMITS();
    public static int countOpenPermits(Connection c) throws Exception {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM permits WHERE status = 'OPEN'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // Core query both the JSON API and a plain console test could reuse.
    // This is an example of a JOIN + WHERE.
    static String fetchOpenPermitsAsJson(String street, String status) throws Exception {
        String sql = """
            SELECT p.permit_id, p.permit_type, p.status
            FROM permits p
            JOIN addresses a ON p.address_id = a.address_id
            WHERE a.street = ?
              AND p.status = ?
        """;

        StringBuilder json = new StringBuilder("[");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, street);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;
                    json.append(String.format(
                        "{\"permitId\":%d,\"type\":\"%s\",\"status\":\"%s\"}",
                        rs.getInt("permit_id"),
                        rs.getString("permit_type"),
                        rs.getString("status")));
                }
            }
        }
        json.append("]");
        return json.toString();
    }

    // ---------------------------------------------------------------
    // Module 6/7: The "servlet" layer, built on the JDK's built-in HTTP server
    // so there's no Tomcat/Jakarta EE download required. Conceptually the same
    // job: receive an HTTP request, run business logic, write a response.
    // ---------------------------------------------------------------
    static void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/permits", new PermitApiHandler());
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(null); // default single-threaded executor -- fine for local testing
        server.start();

        System.out.println("Server running: http://localhost:8080");
        System.out.println("Try: http://localhost:8080/api/permits?street=1221 SW 4th Ave");
    }

    /** REST-style JSON endpoint */
    static class PermitApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only handle GET; anything else gets a 405
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String street = params.get("street");
            String status = params.get("status");

            if (street == null || street.isBlank()) {
                sendResponse(exchange, 400, "{\"error\":\"Missing required parameter: street\"}");
                return;
            }

            if (status == null || status.isBlank()) {
                status = "OPEN";
            }

            try {
                String json = fetchOpenPermitsAsJson(street, status);
                sendResponse(exchange, 200, json); // empty [] is still 200 -- resource exists, just no matches
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
                e.printStackTrace();
            }
        }
    }

    /** Serves index.html / style.css / script.js from the web/ folder. */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            System.out.println("PermitApiHandler");

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path file = Path.of("web" + path);
            if (!Files.exists(file)) {
                sendResponse(exchange, 404, "Not found");
                return;
            }

            String contentType = path.endsWith(".css") ? "text/css"
                    : path.endsWith(".js") ? "application/javascript"
                    : "text/html";

            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // --- lightweight helpers ---

    static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }
}
