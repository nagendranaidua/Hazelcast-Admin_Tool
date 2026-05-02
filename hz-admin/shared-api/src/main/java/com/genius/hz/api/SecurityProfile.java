package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Per-cluster connection security. Sensitive material is encrypted-at-rest
 * with the master KEK (AES-GCM) before being persisted to Postgres.
 */
@Value
@Builder
@Jacksonized
public class SecurityProfile {
    Mode mode;                    // PLAIN / TLS / MTLS
    String username;              // optional cluster credential
    String passwordCipher;        // AES-GCM cipher (base64) — never plain
    String truststorePath;        // server CA bundle
    String truststorePasswordCipher;
    String keystorePath;          // client keystore for mTLS
    String keystorePasswordCipher;
    String tlsProtocol;           // e.g. TLSv1.3

    public enum Mode { PLAIN, TLS, MTLS }
}
