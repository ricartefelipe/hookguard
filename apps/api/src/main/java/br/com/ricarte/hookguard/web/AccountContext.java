package br.com.ricarte.hookguard.web;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class AccountContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private AccountContext() {
    }

    public static void set(UUID accountId) {
        CURRENT.set(accountId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static UUID requireAccountId() {
        UUID accountId = CURRENT.get();
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return accountId;
    }
}
