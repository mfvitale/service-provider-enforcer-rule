package io.flomava.logger;

import org.apache.maven.enforcer.rule.api.EnforcerLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class Slf4jEnforcerLoggerAdapter implements EnforcerLogger {
    private final Logger logger;

    public Slf4jEnforcerLoggerAdapter(Class<?> clazz) {
        this.logger = LoggerFactory.getLogger(clazz);
    }

    @Override
    public void warnOrError(CharSequence message) {
        if (logger.isWarnEnabled()) {
            logger.warn(message.toString());
        } else {
            logger.error(message.toString());
        }
    }

    @Override
    public void warnOrError(Supplier<CharSequence> messageSupplier) {
        if (logger.isWarnEnabled()) {
            logger.warn(messageSupplier.get().toString());
        } else {
            logger.error(messageSupplier.get().toString());
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public void debug(CharSequence message) {
        logger.debug(message.toString());
    }

    @Override
    public void debug(Supplier<CharSequence> messageSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(messageSupplier.get().toString());
        }
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public void info(CharSequence message) {
        logger.info(message.toString());
    }

    @Override
    public void info(Supplier<CharSequence> messageSupplier) {
        if (logger.isInfoEnabled()) {
            logger.info(messageSupplier.get().toString());
        }
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    @Override
    public void warn(CharSequence message) {
        logger.warn(message.toString());
    }

    @Override
    public void warn(Supplier<CharSequence> messageSupplier) {
        if (logger.isWarnEnabled()) {
            logger.warn(messageSupplier.get().toString());
        }
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public void error(CharSequence message) {
        logger.error(message.toString());
    }

    @Override
    public void error(Supplier<CharSequence> messageSupplier) {
        if (logger.isErrorEnabled()) {
            logger.error(messageSupplier.get().toString());
        }
    }
}
