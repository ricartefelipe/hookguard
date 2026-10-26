package br.com.ricarte.hookguard.delivery;

/** The destination host did not resolve: a transient condition, unlike a blocked address. */
public class HostUnresolvedException extends IllegalArgumentException {

    public HostUnresolvedException() {
        super("destinationUrl host cannot be resolved");
    }
}
