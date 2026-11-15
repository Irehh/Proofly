package com.proofly.notification;

import io.github.resilience4j.retry.annotation.Retry;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class EmailSender {

    private final JavaMailSender mailSender;
    private final NotificationProperties props;

    public EmailSender(JavaMailSender mailSender, NotificationProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Retry(name = "notification")
    public void send(String toEmail, NotificationTemplateEngine.RenderedMessage rendered) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(props.fromEmail(), props.fromName()));
            helper.setTo(toEmail);
            helper.setSubject(rendered.subject());
            helper.setText(rendered.textBody(), rendered.htmlBody());
            mailSender.send(msg);
        } catch (Exception ex) {
            throw new IllegalStateException("SMTP delivery failed: " + ex.getMessage(), ex);
        }
    }
}
