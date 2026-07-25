package br.com.ricarte.hookguard.domain;

public enum JobState {
    PENDING,
    IN_PROGRESS,
    DONE,
    DEAD;

    public static JobState fromStorage(String value) {
        return JobState.valueOf(value.toUpperCase());
    }

    public String toStorage() {
        return name().toLowerCase();
    }
}
