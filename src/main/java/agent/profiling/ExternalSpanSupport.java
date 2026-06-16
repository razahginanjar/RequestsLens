package agent.profiling;

import java.net.URI;
import java.util.Locale;

/**
 * Helper called from bytecode advice for external client spans.
 *
 * <p>Advice code runs inside target classes, so it should stay tiny and only
 * pass primitive/string/object values into this agent-owned helper.
 */
public final class ExternalSpanSupport {

    private static final int MAX_RESOURCE_LENGTH = 180;

    private ExternalSpanSupport() {}

    public static int enterSql(String sql) {
        String resource = sanitizeSql(sql);
        String operation = sqlOperation(resource);
        return RequestProfilingContext.externalEnter(
            "sql", "SQL", operation, operation, resource);
    }

    public static int enterPreparedSql(Object statement) {
        return enterSql(statement == null ? "" : statement.toString());
    }

    public static int enterHttp(Object method, Object uri) {
        String operation = httpMethod(method);
        String resource = sanitizeUri(uri);
        return RequestProfilingContext.externalEnter(
            "http", "HTTP", operation + " " + resource, operation, resource);
    }

    public static void exit(int enterState) {
        if (enterState != RequestProfilingContext.ENTER_NONE) {
            RequestProfilingContext.methodExit(enterState);
        }
    }

    static String sanitizeSql(String sql) {
        if (sql == null || sql.isBlank()) return "(unknown SQL)";
        String normalized = sql
            .replaceAll("'([^']|'')*'", "?")
            .replaceAll("\"([^\"]|\"\")*\"", "?")
            .replaceAll("\\b\\d+(\\.\\d+)?\\b", "?")
            .replaceAll("\\s+", " ")
            .trim();
        return limit(normalized.isBlank() ? "(unknown SQL)" : normalized);
    }

    static String sqlOperation(String sql) {
        if (sql == null || sql.isBlank() || sql.startsWith("(")) return "SQL";
        String first = sql.trim().split("\\s+", 2)[0]
            .replaceAll("[^A-Za-z]", "");
        return first.isBlank() ? "SQL" : first.toUpperCase(Locale.ROOT);
    }

    static String sanitizeUri(Object uri) {
        if (uri == null) return "(unknown URL)";
        try {
            URI parsed = uri instanceof URI u ? u : URI.create(uri.toString());
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme();
            String host = parsed.getHost();
            if (host == null || host.isBlank()) {
                return limit(parsed.toString().split("\\?", 2)[0]);
            }
            StringBuilder out = new StringBuilder();
            out.append(scheme).append("://").append(host);
            if (parsed.getPort() >= 0) out.append(':').append(parsed.getPort());
            String path = parsed.getRawPath();
            out.append(path == null || path.isBlank() ? "/" : path);
            return limit(out.toString());
        } catch (IllegalArgumentException e) {
            return limit(uri.toString().split("\\?", 2)[0]);
        }
    }

    private static String httpMethod(Object method) {
        String value = method == null ? "HTTP" : method.toString().trim();
        return value.isBlank() ? "HTTP" : value.toUpperCase(Locale.ROOT);
    }

    private static String limit(String value) {
        if (value.length() <= MAX_RESOURCE_LENGTH) return value;
        return value.substring(0, MAX_RESOURCE_LENGTH - 3) + "...";
    }
}
