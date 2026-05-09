package com.agent.config;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GmailConfig {

    private static final String APPLICATION_NAME = "Agent Gmail Spring Boot";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final Path TOKENS_DIRECTORY = Path.of("tokens");
    private static final List<String> SCOPES = List.of(
            // GMAIL_MODIFY permet de lire, composer/envoyer et modifier les messages sans suppression definitive.
            // GMAIL_LABELS est utile si l'agent doit creer, renommer ou supprimer des labels Gmail.
            GmailScopes.GMAIL_MODIFY,
            GmailScopes.GMAIL_SEND,
            GmailScopes.GMAIL_LABELS
    );

    private final String credentialsPath;
    private final int oauthCallbackPort;

    public GmailConfig(
            @Value("${gmail.credentials.path}") String credentialsPath,
            @Value("${gmail.oauth.callback.port:8888}") int oauthCallbackPort
    ) {
        this.credentialsPath = credentialsPath;
        this.oauthCallbackPort = oauthCallbackPort;
    }

    public Gmail createGmailClient() throws GeneralSecurityException, IOException {
        NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = authorize(httpTransport);

        return new Gmail.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private Credential authorize(NetHttpTransport httpTransport) throws IOException {
        Path path = Path.of(credentialsPath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Fichier credentials.json introuvable : " + path.toAbsolutePath());
        }

        try (InputStream inputStream = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(inputStream)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    httpTransport,
                    JSON_FACTORY,
                    clientSecrets,
                    SCOPES
            )
                    // Tokens OAuth locaux. Si les scopes changent, supprimez tokens/ pour forcer
                    // Google a afficher un nouveau consentement avec les permissions mises a jour.
                    .setDataStoreFactory(new FileDataStoreFactory(TOKENS_DIRECTORY.toFile()))
                    .setAccessType("offline")
                    .build();

            LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                    // Avec un client OAuth de type "web", cette URI doit etre declaree dans Google Cloud :
                    // http://localhost:8888/Callback
                    .setPort(oauthCallbackPort)
                    .build();

            return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        }
    }
}
