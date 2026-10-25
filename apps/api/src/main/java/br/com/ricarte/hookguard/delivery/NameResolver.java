package br.com.ricarte.hookguard.delivery;

import java.net.InetAddress;
import java.net.UnknownHostException;

public interface NameResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
