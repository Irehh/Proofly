package com.proofly.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationDispatcherJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherJob.class);

    public record OutboxRow(
            UUID id,
            String channel,
            String recipient,
            String templateCode,
            String payloadJson,
            int attemptCount,
            int maxAttempts
    ) {}

    private final JdbcClient controlJdbc;
    private final TransactionTemplate controlTx;
    private final NotificationProperties props;
    private final NotificationTemplateEngine templates;
    private final NotificationOutboxService outboxService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;

    public NotificationDispatcherJob(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                     @Qualifier("controlPlaneTransactionTemplate") TransactionTemplate controlTx,
                                     NotificationProperties props,
                                     NotificationTemplateEngine templates,
                                     NotificationOutboxService outboxService,
                                     EmailSender emailSender,
                                     SmsSender smsSender) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.controlTx = controlTx;
        this.props = props;
        this.templates = templates;
        this.outboxService = outboxService;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    @Scheduled(fixedDelayString = "${proofly.notification.poll-interval:PT5S}")
    @SchedulerLock(name = "dispatchNotificationOutbox", lockAtLeastFor = "PT2S", lockAtMostFor = "PT2M")
    public void dispatchDueNotifications() {
        recoverStuckProcessing();
        List<OutboxRow> batch = claimBatch();
        for (OutboxRow item : batch) {
            processItem(item);
        }
    }

    /**
     * Recovers outbox rows left in 'PROCESSING' after an unexpected worker crash past the lock timeout.
     */
    public int recoverStuckProcessing() {
        long timeoutSec = Math.max(30L, props.processingTimeoutSeconds());
        Integer recovered = controlTx.execute(status -> {
            List<OutboxRow> stuck = controlJdbc.sql("""
                    SELECT id, channel, recipient, template_code, payload_jsonb::text AS payload_json,
                           attempt_count, max_attempts
                    FROM notification_outbox
                    WHERE status = 'PROCESSING'
                      AND COALESCE(processing_started_at, updated_at, created_at) < NOW() - (:timeoutSec * INTERVAL '1 second')
                    ORDER BY created_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """)
                    .param("timeoutSec", timeoutSec)
                    .param("batchSize", Math.max(1, props.batchSize()))
                    .query((rs, rowNum) -> new OutboxRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("channel"),
                            rs.getString("recipient"),
                            rs.getString("template_code"),
                            rs.getString("payload_json"),
                            rs.getInt("attempt_count"),
                            rs.getInt("max_attempts")
                    ))
                    .list();

            for (OutboxRow row : stuck) {
                int nextAttempt = row.attemptCount() + 1;
                boolean exhausted = nextAttempt >= row.maxAttempts();
                String nextStatus = exhausted ? "DEAD_LETTER" : "FAILED";
                Instant nextAt = Instant.now().plusSeconds(computeBackoffSeconds(row.attemptCount()));
                controlJdbc.sql("""
                        UPDATE notification_outbox
                        SET status = :status,
                            attempt_count = :attempt,
                            next_attempt_at = :nextAt,
                            processing_started_at = NULL,
                            updated_at = NOW(),
                            last_error = :err
                        WHERE id = :id
                        """)
                        .param("status", nextStatus)
                        .param("attempt", nextAttempt)
                        .param("nextAt", Timestamp.from(nextAt))
                        .param("err", "Recovered from stale PROCESSING state after worker timeout")
                        .param("id", row.id())
                        .update();
                log.warn("Recovered stale PROCESSING notification id={} -> {} (attempt {}/{})",
                        row.id(), nextStatus, nextAttempt, row.maxAttempts());
            }
            return stuck.size();
        });
        return recovered != null ? recovered : 0;
    }

    /**
     * Atomically locks and claims due notifications inside an explicit control-plane transaction.
     */
    public List<OutboxRow> claimBatch() {
        List<OutboxRow> claimed = controlTx.execute(status -> {
            List<OutboxRow> rows = controlJdbc.sql("""
                    SELECT id, channel, recipient, template_code, payload_jsonb::text AS payload_json,
                           attempt_count, max_attempts
                    FROM notification_outbox
                    WHERE status IN ('PENDING', 'FAILED')
                      AND next_attempt_at <= NOW()
                    ORDER BY next_attempt_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """)
                    .param("batchSize", Math.max(1, props.batchSize()))
                    .query((rs, rowNum) -> new OutboxRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("channel"),
                            rs.getString("recipient"),
                            rs.getString("template_code"),
                            rs.getString("payload_json"),
                            rs.getInt("attempt_count"),
                            rs.getInt("max_attempts")
                    ))
                    .list();

            for (OutboxRow row : rows) {
                controlJdbc.sql("""
                        UPDATE notification_outbox
                        SET status = 'PROCESSING',
                            processing_started_at = NOW(),
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                        .param("id", row.id())
                        .update();
            }
            return rows;
        });
        return claimed != null ? claimed : List.of();
    }

    public void processItem(OutboxRow item) {
        try {
            Map<String, Object> vars = outboxService.parsePayload(item.payloadJson());
            NotificationTemplateEngine.RenderedMessage rendered = templates.render(item.templateCode(), vars);

            switch (item.channel()) {
                case "EMAIL" -> emailSender.send(item.recipient(), rendered);
                case "SMS" -> smsSender.send(item.recipient(), rendered.textBody(), item.templateCode());
                default -> throw new IllegalArgumentException("Unsupported notification channel: " + item.channel());
            }

            controlTx.executeWithoutResult(status -> controlJdbc.sql("""
                    UPDATE notification_outbox
                    SET status = 'SENT',
                        sent_at = NOW(),
                        processing_started_at = NULL,
                        updated_at = NOW(),
                        last_error = NULL
                    WHERE id = :id
                    """)
                    .param("id", item.id())
                    .update());
        } catch (Exception ex) {
            int nextAttempt = item.attemptCount() + 1;
            boolean exhausted = nextAttempt >= item.maxAttempts();
            String nextStatus = exhausted ? "DEAD_LETTER" : "FAILED";
            Instant nextAt = Instant.now().plusSeconds(computeBackoffSeconds(item.attemptCount()));
            String err = ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(500, ex.getMessage().length()))
                    : "Delivery error";

            log.warn("Notification {} failed (attempt {}/{} -> {}): {}",
                    item.id(), nextAttempt, item.maxAttempts(), nextStatus, err);

            controlTx.executeWithoutResult(status -> controlJdbc.sql("""
                    UPDATE notification_outbox
                    SET status = :status,
                        attempt_count = :attempt,
                        next_attempt_at = :nextAt,
                        processing_started_at = NULL,
                        updated_at = NOW(),
                        last_error = :err
                    WHERE id = :id
                    """)
                    .param("status", nextStatus)
                    .param("attempt", nextAttempt)
                    .param("nextAt", Timestamp.from(nextAt))
                    .param("err", err)
                    .param("id", item.id())
                    .update());
        }
    }

    private long computeBackoffSeconds(int currentAttemptCount) {
        long base = Math.max(5L, props.baseRetrySeconds());
        return (long) (base * Math.pow(2, Math.min(6, Math.max(0, currentAttemptCount))));
    }
}
