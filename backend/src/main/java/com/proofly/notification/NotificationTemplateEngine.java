package com.proofly.notification;

import com.proofly.common.AppProperties;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class NotificationTemplateEngine {

    public record RenderedMessage(String subject, String htmlBody, String textBody) {}

    private final AppProperties appProperties;

    public NotificationTemplateEngine(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public RenderedMessage render(String templateCode, Map<String, Object> vars) {
        return switch (templateCode) {
            case "WELCOME_VERIFY_EMAIL" -> {
                String name = str(vars, "fullName", "there");
                String orgName = str(vars, "orgName", "your workspace");
                String verifyUrl = str(vars, "actionUrl", appProperties.frontendBaseUrl() + "/verify-email");
                String subject = "Verify your Proofly email for " + orgName;
                String text = "Hi " + name + ",\n\nConfirm your email address for " + orgName
                        + " by opening:\n" + verifyUrl + "\n\nSupport: " + appProperties.supportEmail();
                String html = wrapHtml(subject,
                        "<p>Hi " + esc(name) + ",</p>"
                                + "<p>Your workspace <strong>" + esc(orgName) + "</strong> is ready. Please verify your email address to secure account recovery.</p>"
                                + ctaButton(verifyUrl, "Verify Email Address"));
                yield new RenderedMessage(subject, html, text);
            }
            case "PASSWORD_RESET" -> {
                String name = str(vars, "fullName", "there");
                String resetUrl = str(vars, "actionUrl", appProperties.frontendBaseUrl() + "/reset-password");
                String ttl = str(vars, "ttlMinutes", "15");
                String subject = "Reset your Proofly password";
                String text = "Hi " + name + ",\n\nUse this single-use link within " + ttl + " minutes to reset your password:\n"
                        + resetUrl + "\n\nIf you did not request this, you can safely ignore this message.";
                String html = wrapHtml(subject,
                        "<p>Hi " + esc(name) + ",</p>"
                                + "<p>Use the single-use button below within " + esc(ttl) + " minutes to choose a new Proofly password.</p>"
                                + ctaButton(resetUrl, "Reset Password")
                                + "<p style=\"color:#526277;font-size:13px;\">If you did not request a reset, no action is needed.</p>");
                yield new RenderedMessage(subject, html, text);
            }
            case "SUBSCRIPTION_PAYMENT_SUCCEEDED" -> {
                String orgName = str(vars, "orgName", "your organization");
                String planName = str(vars, "planName", "Paid Plan");
                String gateway = str(vars, "gateway", "Payment Gateway");
                String amountFormatted = str(vars, "amountDisplay", "");
                String reference = str(vars, "reference", "");
                String subject = "Proofly payment receipt — " + planName;
                String text = "Payment confirmed for " + orgName + " (" + planName + "): " + amountFormatted
                        + "\nGateway: " + gateway + "\nReference: " + reference;
                String html = wrapHtml(subject,
                        "<p>Payment confirmed for <strong>" + esc(orgName) + "</strong>.</p>"
                                + "<p>Plan: <strong>" + esc(planName) + "</strong><br/>Gateway: <strong>" + esc(gateway)
                                + "</strong><br/>Amount: <strong>" + esc(amountFormatted)
                                + "</strong><br/>Reference: <code>" + esc(reference) + "</code></p>"
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=billing", "View Billing"));
                yield new RenderedMessage(subject, html, text);
            }
            case "SUBSCRIPTION_PAYMENT_FAILED" -> {
                String orgName = str(vars, "orgName", "your organization");
                String graceUntil = str(vars, "graceUntil", "soon");
                String subject = "Action needed: Proofly subscription payment failed";
                String text = "Your subscription renewal for " + orgName + " failed. Grace period ends " + graceUntil
                        + ". Update billing at " + appProperties.frontendBaseUrl() + "/workspace?tab=billing";
                String html = wrapHtml(subject,
                        "<p>Your subscription payment for <strong>" + esc(orgName) + "</strong> did not settle.</p>"
                                + "<p>Your workspace remains active during the grace period until <strong>" + esc(graceUntil) + "</strong>.</p>"
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=billing", "Complete Payment"));
                yield new RenderedMessage(subject, html, text);
            }
            case "SUBSCRIPTION_SUSPENDED" -> {
                String orgName = str(vars, "orgName", "your organization");
                String subject = "Proofly workspace suspended — billing grace period ended";
                String text = "Your Proofly subscription grace period for " + orgName
                        + " has expired and the workspace is now suspended. Complete payment at "
                        + appProperties.frontendBaseUrl() + "/workspace?tab=billing to restore access.";
                String html = wrapHtml(subject,
                        "<p>The billing grace period for <strong>" + esc(orgName) + "</strong> has ended and the workspace is now suspended.</p>"
                                + "<p>Complete a subscription checkout to restore clock-ins and admin access immediately.</p>"
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=billing", "Restore Subscription"));
                yield new RenderedMessage(subject, html, text);
            }
            case "STAFF_INVITE_EMAIL" -> {
                String orgName = str(vars, "orgName", "Proofly");
                String inviteUrl = str(vars, "actionUrl", appProperties.frontendBaseUrl());
                String subject = "You are invited to join " + orgName + " on Proofly";
                String text = "Join " + orgName + " on Proofly to clock in at assigned sites:\n" + inviteUrl;
                String html = wrapHtml(subject,
                        "<p>You have been invited to join <strong>" + esc(orgName) + "</strong> on Proofly.</p>"
                                + ctaButton(inviteUrl, "Accept Invitation"));
                yield new RenderedMessage(subject, html, text);
            }
            case "STAFF_INVITE_PHONE_PIN" -> {
                String orgName = str(vars, "orgName", "Proofly");
                String subject = "Proofly PIN Invite — " + orgName;
                String text = "You were added to " + orgName + " on Proofly. Open "
                        + appProperties.frontendBaseUrl() + "/login and sign in with Phone & PIN.";
                yield new RenderedMessage(subject, wrapHtml(subject, "<p>" + esc(text) + "</p>"
                        + ctaButton(appProperties.frontendBaseUrl() + "/login", "Open Proofly PWA")), text);
            }
            case "ATTENDANCE_OUT_OF_FENCE_FLAGGED" -> {
                String staffName = str(vars, "staffName", "Staff member");
                String siteName = str(vars, "siteName", "Site");
                String distanceMeters = str(vars, "distanceMeters", "0");
                String radiusMeters = str(vars, "radiusMeters", "0");
                String subject = "Out-of-fence clock attempt flagged — " + staffName;
                String text = staffName + " clocked in " + distanceMeters + "m from " + siteName
                        + " (site radius: " + radiusMeters + "m). Review in Proofly.";
                String html = wrapHtml(subject,
                        "<p><strong>" + esc(staffName) + "</strong> submitted a clock-in outside the perimeter of <strong>"
                                + esc(siteName) + "</strong>.</p>"
                                + "<p>Computed distance: <strong>" + esc(distanceMeters) + "m</strong> (limit " + esc(radiusMeters) + "m).</p>"
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=reviews", "Review Attempt"));
                yield new RenderedMessage(subject, html, text);
            }
            case "LEAVE_REQUEST_SUBMITTED" -> {
                String staffName = str(vars, "staffName", "Team member");
                String leaveType = str(vars, "leaveType", "Leave");
                String dateRange = str(vars, "dateRange", "");
                String subject = "Leave request pending approval — " + staffName;
                String text = staffName + " requested " + leaveType + " leave (" + dateRange + ").";
                String html = wrapHtml(subject,
                        "<p><strong>" + esc(staffName) + "</strong> requested <strong>" + esc(leaveType)
                                + "</strong> leave for <strong>" + esc(dateRange) + "</strong>.</p>"
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=reviews", "Review Leave Request"));
                yield new RenderedMessage(subject, html, text);
            }
            case "LEAVE_REQUEST_DECIDED" -> {
                String leaveType = str(vars, "leaveType", "Leave");
                String decision = str(vars, "decision", "UPDATED");
                String dateRange = str(vars, "dateRange", "");
                String note = str(vars, "note", "");
                String subject = "Leave request " + decision.toLowerCase() + " — " + dateRange;
                String text = "Your " + leaveType + " leave (" + dateRange + ") was " + decision + ". " + note;
                String html = wrapHtml(subject,
                        "<p>Your <strong>" + esc(leaveType) + "</strong> leave request for <strong>" + esc(dateRange)
                                + "</strong> was marked <strong>" + esc(decision) + "</strong>.</p>"
                                + (note.isBlank() ? "" : "<p>Manager note: " + esc(note) + "</p>")
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=shift", "View My Shift"));
                yield new RenderedMessage(subject, html, text);
            }
            case "CORRECTION_DECIDED" -> {
                String decision = str(vars, "decision", "UPDATED");
                String note = str(vars, "note", "");
                String subject = "Attendance correction " + decision.toLowerCase();
                String text = "Your attendance correction was " + decision + ". " + note;
                String html = wrapHtml(subject,
                        "<p>Your requested attendance correction has been <strong>" + esc(decision) + "</strong>.</p>"
                                + (note.isBlank() ? "" : "<p>Note: " + esc(note) + "</p>")
                                + ctaButton(appProperties.frontendBaseUrl() + "/workspace?tab=shift", "View Attendance Log"));
                yield new RenderedMessage(subject, html, text);
            }
            default -> {
                String subject = str(vars, "subject", "Proofly Notification");
                String body = str(vars, "body", "You have a new update in Proofly.");
                yield new RenderedMessage(subject, wrapHtml(subject, "<p>" + esc(body) + "</p>"), body);
            }
        };
    }

    private String wrapHtml(String title, String content) {
        return """
                <!doctype html>
                <html>
                <body style="margin:0;padding:24px;background:#F7F9FC;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#0B1F3A;">
                  <div style="max-width:560px;margin:0 auto;background:#FFFFFF;border:1px solid #E2E8F0;border-radius:12px;padding:28px;">
                    <div style="font-size:18px;font-weight:700;color:#0B1F3A;margin-bottom:16px;">Proofly</div>
                    <h2 style="font-size:18px;margin:0 0 14px 0;">%s</h2>
                    <div style="font-size:14px;line-height:1.6;color:#334155;">%s</div>
                    <hr style="border:none;border-top:1px solid #E2E8F0;margin:24px 0 14px 0;" />
                    <p style="font-size:12px;color:#64748B;margin:0;">Questions? Contact %s</p>
                  </div>
                </body>
                </html>
                """.formatted(esc(title), content, esc(appProperties.supportEmail()));
    }

    private static String ctaButton(String url, String label) {
        return "<p style=\"margin:20px 0;\"><a href=\"" + esc(url)
                + "\" style=\"display:inline-block;background:#09A55A;color:#FFFFFF;text-decoration:none;font-weight:600;padding:12px 20px;border-radius:8px;\">"
                + esc(label) + "</a></p>";
    }

    private static String str(Map<String, Object> vars, String key, String fallback) {
        Object val = vars.get(key);
        return val != null ? String.valueOf(val) : fallback;
    }

    private static String esc(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
