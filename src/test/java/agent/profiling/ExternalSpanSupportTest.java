package agent.profiling;

import agent.model.MethodSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSpanSupportTest {

    @AfterEach
    void cleanUp() {
        RequestProfilingContext.end();
    }

    @Test
    void sanitizesSqlShape() {
        String sql = ExternalSpanSupport.sanitizeSql(
            "select * from users where id = 42 and email = 'a@example.test'");

        assertEquals("select * from users where id = ? and email = ?", sql);
        assertEquals("SELECT", ExternalSpanSupport.sqlOperation(sql));
    }

    @Test
    void sanitizesHttpUriWithoutQueryString() {
        String uri = ExternalSpanSupport.sanitizeUri(
            "https://api.example.test:8443/users?id=42&token=secret");

        assertEquals("https://api.example.test:8443/users", uri);
    }

    @Test
    void recordsExternalSpanUnderActiveMethod() {
        MethodSpan root = new MethodSpan();
        root.className = "HTTP";
        root.methodName = "GET /x";
        RequestProfilingContext.begin("trace-external", root, 40, 5000);
        assertEquals(RequestProfilingContext.ENTER_SPAN,
            RequestProfilingContext.methodEnterState("com.example.App", "handler"));

        int state = ExternalSpanSupport.enterSql("select 42 as answer");
        assertEquals(RequestProfilingContext.ENTER_SPAN, state);
        ExternalSpanSupport.exit(state);
        RequestProfilingContext.methodExit();

        RequestProfilingContext.finish();
        MethodSpan handler = root.children.get(0);
        assertEquals(1, handler.children.size());
        MethodSpan sql = handler.children.get(0);
        assertEquals("sql", sql.spanKind);
        assertEquals("SQL", sql.className);
        assertEquals("SELECT", sql.methodName);
        assertEquals("SELECT", sql.externalOperation);
        assertEquals("select ? as answer", sql.externalResource);
        assertTrue(sql.wallNs >= 0L);
    }
}
