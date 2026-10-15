package com.etekisi.etekisi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * One-off at startup: encrypts payout bank details that were stored in plain
 * text before field encryption existed. Already-encrypted rows are skipped,
 * so running it on every start is harmless.
 */
@Component
public class LegacyPayoutEncryption implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPayoutEncryption.class);
    private static final String[] COLUMNS = {"account_number", "account_holder", "phone_number"};

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;

    public LegacyPayoutEncryption(JdbcTemplate jdbc, FieldEncryptor encryptor) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int updated = 0;
        for (String col : COLUMNS) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select id, " + col + " as v from driver_schema.payout_requests "
                            + "where " + col + " is not null and " + col + " not like 'enc:v1:%'");
            for (Map<String, Object> row : rows) {
                jdbc.update("update driver_schema.payout_requests set " + col + " = ? where id = ?",
                        encryptor.encrypt((String) row.get("v")), row.get("id"));
                updated++;
            }
        }
        if (updated > 0) log.info("payout.legacy_encryption.done values={}", updated);
    }
}
