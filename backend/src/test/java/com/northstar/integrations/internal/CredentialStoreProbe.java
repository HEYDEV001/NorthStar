package com.northstar.integrations.internal;

/** Test-only: the credential store's type, which is package-private, for tests outside this package. */
public final class CredentialStoreProbe {

    private CredentialStoreProbe() {}

    public static Class<?> storeType() {
        return CredentialStore.class;
    }
}
