# Test TLS fixture

`test-server.p12` is a throw-away self-signed certificate and key used only by the tests in
`DeliveryClientProtocolTest` to run a local TLS destination and to check SNI, hostname
verification and certificate rejection. It never leaves the test classpath (`src/test/resources`),
is not packaged in the application jar and protects nothing: the password (`changeit`) and the
private key are public by design. Do not reuse it anywhere else.

- Subject: `CN=hooks.test.example`
- SAN: `hooks.test.example`, `localhost`, `127.0.0.1`, `::1`
- Key: RSA 2048, validity 100 years

Recreate it with:

```bash
keytool -genkeypair -alias hooks -keyalg RSA -keysize 2048 -validity 36500 \
  -storetype PKCS12 -keystore test-server.p12 -storepass changeit \
  -dname "CN=hooks.test.example" \
  -ext "SAN=dns:hooks.test.example,dns:localhost,ip:127.0.0.1,ip:::1"
```
