package br.com.ricarte.hookguard.domain;

public enum EventStatus {
    RECEIVED,
    DELIVERING,
    DELIVERED,
    DEAD;

    public static EventStatus fromStorage(String value) {
        return EventStatus.valueOf(value.toUpperCase());
    }

    public String toStorage() {
        return name().toLowerCase();
    }
}
