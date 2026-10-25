package br.com.ricarte.hookguard.domain;

public enum ProjectStatus {
    ACTIVE,
    PAUSED,
    SUSPENDED;

    public static ProjectStatus fromStorage(String value) {
        return ProjectStatus.valueOf(value.toUpperCase());
    }

    public String toStorage() {
        return name().toLowerCase();
    }
}
