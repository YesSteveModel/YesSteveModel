package com.elfmcys.ysm.testutil;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LogCapture extends AbstractAppender implements AutoCloseable {
    private final org.apache.logging.log4j.core.Logger logger;
    private final Level previousLevel;
    private final CopyOnWriteArrayList<LogEvent> events = new CopyOnWriteArrayList<>();

    public LogCapture(Logger logger) {
        super("test-log-capture-" + System.nanoTime(), null, null, false);
        this.logger = (org.apache.logging.log4j.core.Logger)logger;
        previousLevel = this.logger.getLevel();
        start();
        this.logger.addAppender(this);
        this.logger.setLevel(Level.DEBUG);
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.removeAppender(this);
        logger.setLevel(previousLevel);
        stop();
    }
}
