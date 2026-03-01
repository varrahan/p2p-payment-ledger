package com.p2p.payment.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Initializes the Firebase Admin SDK.
 *
 * The service account JSON file is loaded from the classpath.
 * In production (ECS/Kubernetes) this file is injected as a
 * mounted secret — it is NEVER committed to source control.
 *
 * Local dev: place firebase-service-account.json in src/main/resources/
 * and add it to .gitignore immediately.
 *
 * Production: mount via AWS Secrets Manager or Kubernetes secret,
 * or set FIREBASE_CREDENTIALS_PATH to an absolute path.
 */
@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${app.notifications.firebase.credentials-path:firebase-service-account.json}")
    private String credentialsPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            InputStream credentials = new ClassPathResource(credentialsPath).getInputStream();

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentials))
                    .build();

            FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK initialised");
        }

        return FirebaseMessaging.getInstance();
    }
}
