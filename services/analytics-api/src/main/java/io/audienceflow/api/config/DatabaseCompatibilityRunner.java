package io.audienceflow.api.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCompatibilityRunner implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseCompatibilityRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("ALTER TABLE cameras DROP CONSTRAINT IF EXISTS cameras_stream_type_check");
        jdbcTemplate.execute("""
                ALTER TABLE cameras
                ADD CONSTRAINT cameras_stream_type_check
                CHECK (stream_type IN ('rtsp', 'http', 'mjpeg', 'device', 'file', 'sample', 'simulation'))
                """);
    }
}
