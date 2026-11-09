package com.example.banking.util;

import com.example.banking.model.Money;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

/** All configuration in one place: secrets from environment variables, the rest from app.properties. */
public final class AppConfig {

    private final Properties props;

    private AppConfig(Properties props) {
        this.props = props;
    }

    public static AppConfig load() {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (in == null) {
                throw new IllegalStateException("app.properties not found in src/main/resources");
            }
            Properties p = new Properties();
            p.load(in);
            return new AppConfig(p);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read app.properties", e);
        }
    }

    // ---------- secrets: environment variables only, never files in Git ----------
    public String dbUrl()      { return requireEnv("DB_URL"); }
    public String dbUsername() { return requireEnv("DB_USERNAME"); }
    public String dbPassword() { return requireEnv("DB_PASSWORD"); }

    // ---------- non-secret settings ----------
    public int sessionIdleTimeoutSeconds() { return getInt("session.idle.timeout.seconds", 300); }
    public int dbQueryTimeoutSeconds()     { return getInt("db.query.timeout.seconds", 10); }
    public int authMaxFailedLogins()       { return getInt("auth.max.failed.logins", 5); }
    public int authLockMinutes()           { return getInt("auth.lock.minutes", 15); }
    public int passwordHashIterations()    { return getInt("password.hash.iterations", 600000); }
    public int historyMaxPageSize()        { return getInt("history.max.page.size", 50); }

    public String passwordHashAlgorithm() {
        return props.getProperty("password.hash.algorithm", "PBKDF2WithHmacSHA256").trim();
    }

    public Money txMaxAmount() {
        return Money.of(props.getProperty("tx.max.amount", "1000000.00"));
    }

    public Money savingsMinimumBalance() {
        return Money.of(props.getProperty("savings.minimum.balance", "0.00"));
    }

    // ---------- AI assistant (local Ollama) ----------
    public boolean assistantEnabled() {
        return Boolean.parseBoolean(props.getProperty("assistant.enabled", "true").trim());
    }

    public String ollamaUrl() {
        return props.getProperty("ollama.url", "http://localhost:11434").trim();
    }

    public String ollamaModel() {
        return props.getProperty("ollama.model", "llama3").trim();
    }

    public Duration ollamaTimeout() {
        return Duration.ofSeconds(getInt("ollama.timeout.seconds", 60));
    }

    // ---------- helpers ----------
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable " + name + " is not set. See README for setup.");
        }
        return value;
    }

    private int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("app.properties: '" + key + "' must be a whole number");
        }
    }
}