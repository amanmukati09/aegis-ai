package ai.aegis.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP email channel. Active when SMTP_HOST and ALERT_EMAILS are set. Builds its own
 * mail sender from env so Spring's mail autoconfig never fails when unconfigured.
 */
@Component
public class SmtpEmailAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailAlertChannel.class);

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String from;
    private final String[] recipients;

    public SmtpEmailAlertChannel(
            @Value("${SMTP_HOST:}") String host,
            @Value("${SMTP_PORT:587}") int port,
            @Value("${SMTP_USER:}") String user,
            @Value("${SMTP_PASSWORD:}") String password,
            @Value("${SMTP_FROM:alerts@aegis.local}") String from,
            @Value("${ALERT_EMAILS:}") String recipientsCsv) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.from = from;
        this.recipients = recipientsCsv == null || recipientsCsv.isBlank()
                ? new String[0]
                : recipientsCsv.split("\\s*,\\s*");
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean isConfigured() {
        return host != null && !host.isBlank() && recipients.length > 0;
    }

    @Override
    public boolean send(String subject, String body, String severity) {
        if (!isConfigured()) {
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            if (user != null && !user.isBlank()) {
                sender.setUsername(user);
                sender.setPassword(password);
            }
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", String.valueOf(user != null && !user.isBlank()));
            props.put("mail.smtp.starttls.enable", "true");

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(recipients);
            msg.setSubject("[" + severity + "] " + subject);
            msg.setText(body);
            sender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("Email alert failed: {}", e.getMessage());
            return false;
        }
    }
}
