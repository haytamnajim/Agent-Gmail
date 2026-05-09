package com.agent.service;

import com.agent.model.AgentStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentService.class);

    private final GmailService gmailService;
    private final OpenAiService openAiService;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean pollingEnabled;
    private final int batchSize;

    public AgentService(
            GmailService gmailService,
            OpenAiService openAiService,
            @Value("${agent.poll.enabled:true}") boolean pollingEnabled,
            @Value("${agent.poll.batch-size:5}") int batchSize,
            @Value("${agent.poll.start-on-boot:false}") boolean startOnBoot
    ) {
        this.gmailService = gmailService;
        this.openAiService = openAiService;
        this.pollingEnabled = pollingEnabled;
        this.batchSize = batchSize;
        this.active.set(startOnBoot);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, AgentStep.info("STATE", active.get() ? "Agent actif." : "Agent arrete."));
        return emitter;
    }

    public Map<String, Object> status() {
        return Map.of(
                "active", active.get(),
                "running", running.get(),
                "pollingEnabled", pollingEnabled,
                "batchSize", batchSize
        );
    }

    public Map<String, Object> start() {
        if (!pollingEnabled) {
            broadcast(AgentStep.error("Le polling automatique est desactive dans la configuration."));
            return status();
        }

        boolean changed = active.compareAndSet(false, true);
        broadcast(AgentStep.success("START", changed ? "Agent automatique demarre." : "Agent automatique deja actif."));
        return status();
    }

    public Map<String, Object> stop() {
        boolean changed = active.compareAndSet(true, false);
        broadcast(AgentStep.info("STOP", changed ? "Agent automatique arrete." : "Agent automatique deja arrete."));
        return status();
    }

    @Scheduled(
            initialDelayString = "${agent.poll.initial-delay-ms:10000}",
            fixedDelayString = "${agent.poll.fixed-delay-ms:60000}"
    )
    public void pollPrimaryInbox() {
        if (!pollingEnabled || !active.get()) {
            return;
        }

        runCycle();
    }

    public void runCycle() {
        if (!active.get()) {
            broadcast(AgentStep.info("IDLE", "Agent arrete. Aucun scan execute."));
            return;
        }

        if (!running.compareAndSet(false, true)) {
            broadcast(AgentStep.info("WAIT", "Un cycle est deja en cours."));
            return;
        }

        try {
            broadcast(AgentStep.info("SCAN", "Scan de la rubrique principale Gmail..."));
            broadcast(AgentStep.info("AUTH", "Si Google demande une autorisation, validez la page OAuth ouverte dans le navigateur."));

            List<GmailService.ReceivedEmail> emails = gmailService.findUnprocessedPrimaryEmails(batchSize);
            if (emails.isEmpty()) {
                broadcast(AgentStep.info("IDLE", "Aucun nouvel email dans la rubrique principale."));
                return;
            }

            for (GmailService.ReceivedEmail email : emails) {
                if (!active.get()) {
                    broadcast(AgentStep.info("STOP", "Arret demande. Le cycle s'interrompt avant le prochain email."));
                    return;
                }
                processEmail(email);
            }

            broadcast(AgentStep.success("DONE", "Cycle termine. L'agent reste en surveillance."));
        } catch (Exception exception) {
            LOGGER.error("Cycle agent Gmail en erreur", exception);
            broadcast(AgentStep.error("Erreur : " + exception.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private void processEmail(GmailService.ReceivedEmail email) throws Exception {
        broadcast(AgentStep.info(
                "MAIL",
                "Email rubrique principale trouve : \"" + email.subject() + "\" de " + email.replyTo()
        ).withEmailContent(email.body()));

        broadcast(AgentStep.info("AI", "Generation de la reponse avec GPT-4o-mini..."));
        String reply = openAiService.generateReply(email);
        broadcast(AgentStep.info("TXT", "Reponse generee.").withGeneratedReply(reply));

        gmailService.sendReply(email, reply);
        gmailService.markAsProcessed(email);
        broadcast(AgentStep.success("SEND", "Reponse envoyee a " + email.replyTo()));
    }

    private void broadcast(AgentStep step) {
        LOGGER.info("{} {}", step.emoji(), step.message());
        for (SseEmitter emitter : emitters) {
            send(emitter, step);
        }
    }

    private void send(SseEmitter emitter, AgentStep step) {
        try {
            emitter.send(SseEmitter.event()
                    .name("step")
                    .data(step));
        } catch (IOException exception) {
            emitters.remove(emitter);
            LOGGER.debug("Connexion SSE fermee pendant l'envoi d'une etape.", exception);
        }
    }
}
