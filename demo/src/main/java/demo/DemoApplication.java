package demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Minimal Spring Boot app (no database) for verifying the RequestLens agent.
 *
 * Endpoints:
 *   GET /hello  — fast response
 *   GET /slow   — ~150ms response (so the "slowest endpoints" panel has a clear winner)
 */
@SpringBootApplication
@RestController
public class DemoApplication {

    @Value("${server.port:8080}")
    private int serverPort;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/hello")
    public String hello() {
        return "hello";
    }

    @GetMapping("/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(150);
        // Exercise several allocation kinds so the per-object breakdown is visible:
        //  - a primitive array (NEWARRAY)
        //  - a JDK collection (NEW + <init>) filled with app-type objects (NEW + <init>)
        byte[] junk = new byte[256 * 1024];
        junk[0] = 1;
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            items.add(new Item(i, "item-" + i));
        }
        Item[] itemArray = new Item[] {
            new Item(100, "array-100"),
            new Item(101, "array-101")
        };
        Object[] objectArray = new Object[] { "alpha", itemArray };
        int[][] matrix = new int[2][3];
        matrix[1][2] = itemArray.length + objectArray.length;
        return "slow: bytes=" + junk.length
            + " items=" + items.size()
            + " itemArray=" + itemArray.length
            + " objectArray=" + objectArray.length
            + " matrix=" + matrix[1][2];
    }

    @GetMapping("/cpu")
    public String cpu() {
        long deadline = System.nanoTime() + 250_000_000L;
        long sum = 0L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10_000; i++) {
                sum += (i * 31L) ^ (sum >>> 3);
            }
        }
        return "cpu: " + sum;
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable int id) {
        return "item: " + id;
    }

    @GetMapping("/remote")
    public String remote() {
        return "remote";
    }

    @GetMapping("/external")
    public String external() {
        String remote = new RestTemplate().getForObject(
            "http://127.0.0.1:" + serverPort + "/remote", String.class);
        int sqlAnswer = querySqlAnswer();
        return "external: http=" + remote + " sql=" + sqlAnswer;
    }

    private int querySqlAnswer() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("select 42 as answer")) {
                return result.next() ? result.getInt("answer") : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /** A small application type, allocated under /slow to test object capture. */
    public static final class Item {
        final int id;
        final String name;
        Item(int id, String name) { this.id = id; this.name = name; }
    }
}
