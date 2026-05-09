package com.agent.service;

import com.agent.config.GmailConfig;
import com.google.api.client.util.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Service
public class GmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GmailService.class);
    private static final String USER_ID = "me";
    private static final String PROCESSED_LABEL_NAME = "AgentGmailTraite";
    private static final DateTimeFormatter GMAIL_QUERY_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final GmailConfig gmailConfig;
    private final Instant applicationStartedAt;
    private final String primaryUnprocessedQuery;

    public GmailService(GmailConfig gmailConfig) {
        this.gmailConfig = gmailConfig;
        this.applicationStartedAt = Instant.now();
        this.primaryUnprocessedQuery = buildPrimaryUnprocessedQuery(applicationStartedAt);
        LOGGER.info("Gmail cutoff actif: seuls les messages recus depuis {} seront traites.", applicationStartedAt);
        LOGGER.info("Requete Gmail active: {}", primaryUnprocessedQuery);
    }

    public ReceivedEmail findLatestCustomerEmail() throws Exception {
        List<ReceivedEmail> emails = findUnprocessedPrimaryEmails(1);
        if (emails.isEmpty()) {
            throw new IllegalStateException("Aucun email client trouve dans la rubrique principale.");
        }
        return emails.get(0);
    }

    public List<ReceivedEmail> findUnprocessedPrimaryEmails(int maxResults) throws Exception {
        Gmail gmail = gmailConfig.createGmailClient();
        ensureProcessedLabel(gmail);
        int fetchLimit = Math.max(maxResults, 25);

        ListMessagesResponse response = gmail.users()
                .messages()
                .list(USER_ID)
                .setQ(primaryUnprocessedQuery)
                .setMaxResults((long) fetchLimit)
                .execute();

        List<Message> messages = response.getMessages();
        if (messages == null || messages.isEmpty()) {
            LOGGER.info("Gmail n'a retourne aucun message pour la requete: {}", primaryUnprocessedQuery);
            return List.of();
        }

        LOGGER.info("Gmail a retourne {} message(s) candidat(s) avant filtre d'heure de lancement.", messages.size());

        List<ReceivedEmail> receivedEmails = messages.stream()
                .map(message -> fetchReceivedEmail(gmail, message.getId()))
                .flatMap(Optional::stream)
                .filter(this::wasReceivedAfterApplicationStart)
                .limit(maxResults)
                .toList();

        LOGGER.info("{} message(s) conserve(s) apres filtre d'heure de lancement.", receivedEmails.size());
        return receivedEmails;
    }

    public void sendReply(ReceivedEmail receivedEmail, String reply) throws Exception {
        Gmail gmail = gmailConfig.createGmailClient();

        MimeMessage mimeMessage = createEmail(
                receivedEmail.replyTo(),
                "me",
                "Re: " + receivedEmail.subject(),
                reply,
                receivedEmail.messageId(),
                receivedEmail.references()
        );

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mimeMessage.writeTo(buffer);

        Message message = new Message();
        message.setRaw(Base64.encodeBase64URLSafeString(buffer.toByteArray()));
        message.setThreadId(receivedEmail.threadId());
        gmail.users().messages().send(USER_ID, message).execute();
    }

    public void markAsProcessed(ReceivedEmail receivedEmail) throws Exception {
        Gmail gmail = gmailConfig.createGmailClient();
        String labelId = ensureProcessedLabel(gmail);
        ModifyMessageRequest request = new ModifyMessageRequest()
                .setAddLabelIds(List.of(labelId));

        gmail.users().messages().modify(USER_ID, receivedEmail.id(), request).execute();
    }

    private Optional<ReceivedEmail> fetchReceivedEmail(Gmail gmail, String messageId) {
        try {
            Message message = gmail.users()
                    .messages()
                    .get(USER_ID, messageId)
                    .setFormat("full")
                    .execute();

            return Optional.of(toReceivedEmail(message));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String buildPrimaryUnprocessedQuery(Instant startedAt) {
        String startDate = GMAIL_QUERY_DATE_FORMATTER.format(startedAt.atZone(ZoneId.systemDefault()));
        return "in:inbox -from:me -label:" + PROCESSED_LABEL_NAME + " after:" + startDate;
    }

    private boolean wasReceivedAfterApplicationStart(ReceivedEmail email) {
        boolean accepted = email.internalDateMillis() >= applicationStartedAt.toEpochMilli();
        if (!accepted) {
            LOGGER.info(
                    "Message ignore car anterieur au lancement: id={}, subject=\"{}\", internalDate={}",
                    email.id(),
                    email.subject(),
                    Instant.ofEpochMilli(email.internalDateMillis())
            );
        }
        return accepted;
    }

    private ReceivedEmail toReceivedEmail(Message message) {
        String from = header(message, "From").orElse("expediteur-inconnu@example.com");
        String subject = header(message, "Subject").orElse("(sans objet)");
        String messageId = header(message, "Message-ID").orElse(null);
        String references = header(message, "References").orElse(messageId);
        String body = extractBody(message.getPayload()).orElse(message.getSnippet());

        return new ReceivedEmail(
                message.getId(),
                message.getThreadId(),
                message.getInternalDate(),
                messageId,
                references,
                from,
                extractEmailAddress(from),
                subject,
                body
        );
    }

    private String ensureProcessedLabel(Gmail gmail) throws IOException {
        List<Label> labels = gmail.users().labels().list(USER_ID).execute().getLabels();
        if (labels != null) {
            Optional<Label> existingLabel = labels.stream()
                    .filter(label -> PROCESSED_LABEL_NAME.equals(label.getName()))
                    .findFirst();
            if (existingLabel.isPresent()) {
                return existingLabel.get().getId();
            }
        }

        Label label = new Label()
                .setName(PROCESSED_LABEL_NAME)
                .setLabelListVisibility("labelShow")
                .setMessageListVisibility("show");

        return gmail.users().labels().create(USER_ID, label).execute().getId();
    }

    private MimeMessage createEmail(
            String to,
            String from,
            String subject,
            String bodyText,
            String inReplyTo,
            String references
    ) throws MessagingException {
        MimeMessage email = new MimeMessage(Session.getDefaultInstance(new Properties()));
        email.setFrom(new InternetAddress(from));
        email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        email.setSubject(subject, StandardCharsets.UTF_8.name());
        email.setText(bodyText, StandardCharsets.UTF_8.name());
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            email.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            email.setHeader("References", references);
        }
        return email;
    }

    private Optional<String> header(Message message, String name) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return Optional.empty();
        }

        return message.getPayload()
                .getHeaders()
                .stream()
                .filter(header -> name.equalsIgnoreCase(header.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst();
    }

    private Optional<String> extractBody(MessagePart part) {
        if (part == null) {
            return Optional.empty();
        }

        if (part.getBody() != null && part.getBody().getData() != null) {
            String mimeType = part.getMimeType();
            if (mimeType == null || mimeType.startsWith("text/plain")) {
                return Optional.of(decode(part.getBody().getData()));
            }
        }

        if (part.getParts() == null) {
            return Optional.empty();
        }

        return part.getParts()
                .stream()
                .map(this::extractBody)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private String decode(String encoded) {
        return new String(Base64.decodeBase64(encoded), StandardCharsets.UTF_8);
    }

    private String extractEmailAddress(String from) {
        try {
            InternetAddress address = new InternetAddress(from);
            return address.getAddress();
        } catch (MessagingException exception) {
            return from;
        }
    }

    public record ReceivedEmail(
            String id,
            String threadId,
            long internalDateMillis,
            String messageId,
            String references,
            String from,
            String replyTo,
            String subject,
            String body
    ) {
    }
}
