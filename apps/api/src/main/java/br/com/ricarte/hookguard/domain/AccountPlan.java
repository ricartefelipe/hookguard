package br.com.ricarte.hookguard.domain;

public enum AccountPlan {
    FREE,
    PRO,
    BUSINESS;

    public static AccountPlan fromStorage(String value) {
        return AccountPlan.valueOf(value.toUpperCase());
    }

    public String toStorage() {
        return name().toLowerCase();
    }
}
