package com.gateway.merchant.tooling;

import com.gateway.merchant.domain.ApiKey;
import com.gateway.merchant.domain.Argon2Hasher;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantMode;
import com.gateway.merchant.persistence.ApiKeyRepository;
import com.gateway.merchant.persistence.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("seed")
class LocalDevSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDevSeeder.class);

    private static final String MERCHANT_ID = "mer_local_test";
    private static final String SEED_API_KEY =
            "sk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000";
    private static final String CALLBACK_URL = "http://host.docker.internal:9999";
    private static final String RETURN_URL_PATTERN = "http://host.docker.internal:9999/return";
    private static final String CANCEL_URL_PATTERN = "http://host.docker.internal:9999/cancel";

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final Argon2Hasher argon2Hasher;
    private final ConfigurableApplicationContext applicationContext;
    private final TransactionTemplate transactionTemplate;

    LocalDevSeeder(
            MerchantRepository merchantRepository,
            ApiKeyRepository apiKeyRepository,
            Argon2Hasher argon2Hasher,
            ConfigurableApplicationContext applicationContext,
            PlatformTransactionManager transactionManager) {
        this.merchantRepository = merchantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.argon2Hasher = argon2Hasher;
        this.applicationContext = applicationContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        // The DB work runs in its own transaction that commits before we exit. Exiting
        // (which closes the context and the datasource) must happen *after* the commit —
        // calling SpringApplication.exit inside the transaction closes the connection
        // before commit and fails with "This connection has been closed".
        seed();
        SpringApplication.exit(applicationContext, () -> 0);
    }

    private void seed() {
        transactionTemplate.executeWithoutResult(
                status -> {
                    if (merchantRepository.existsById(MERCHANT_ID)) {
                        log.info("Seed merchant '{}' already present, skipping.", MERCHANT_ID);
                        return;
                    }

                    Merchant merchant =
                            new Merchant(
                                    MERCHANT_ID,
                                    "Local Test Merchant",
                                    CALLBACK_URL,
                                    RETURN_URL_PATTERN,
                                    CANCEL_URL_PATTERN,
                                    null,
                                    MerchantMode.TEST);
                    merchantRepository.save(merchant);

                    String keyHash = argon2Hasher.hash(SEED_API_KEY);
                    ApiKey apiKey =
                            new ApiKey(
                                    MERCHANT_ID,
                                    SEED_API_KEY.substring(0, 16),
                                    keyHash,
                                    MerchantMode.TEST,
                                    "Local dev seed");
                    apiKeyRepository.save(apiKey);

                    log.info(
                            "Seeded merchant '{}' with API key prefix '{}'.",
                            MERCHANT_ID,
                            SEED_API_KEY.substring(0, 16));
                });
    }
}
