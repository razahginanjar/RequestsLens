package agent.collector.logging;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * JUL bridge for live log capture. It observes records that already pass JUL
 * logger levels; it does not change target logging levels.
 */
public final class JulLogHandler extends Handler {

    public static void attachRoot() {
        Logger root = LogManager.getLogManager().getLogger("");
        if (root == null) return;
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof JulLogHandler) return;
        }
        JulLogHandler handler = new JulLogHandler();
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) return;
        LogCaptureSupport.recordJul(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
