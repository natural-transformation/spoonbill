# Local TLS test fixture

`localhost.p12` contains a public, test-only private key and self-signed localhost
certificate. Its password is `spoonbill-test-only`. It is used only by
`HttpClientSpec` and `HttpClientTlsSecuritySpec`; never use this key for an application or deployment. The client
trust context accepts this certificate through a test keystore, without disabling
certificate validation globally or trusting arbitrary certificates.

`wrong-host.p12` is a second public test-only key/certificate for
`wrong-host.invalid`, using the same test password. The security regression
trusts both fixture certificates and verifies that HTTPS and WSS still reject
this certificate for `127.0.0.1`. It then retries the same address with the valid
certificate to check that the failed handshake released the connection slot.
Generate it with the same command below, changing the alias to `wrong-host`,
the subject to `CN=wrong-host.invalid,O=Spoonbill Test Only`, the SAN to
`DNS:wrong-host.invalid`, and the output filename to `wrong-host.p12`.

The certificate covers `localhost` and `127.0.0.1`, with a fixed start date in
2020 and a 100-year validity window so the regression does not depend on renewal.
It was generated with the repository's Nix-managed JDK:

```sh
nix develop --no-write-lock-file --command keytool -genkeypair \
  -alias localhost -keyalg RSA -keysize 2048 -validity 36500 \
  -startdate '2020/01/01 00:00:00' -dname 'CN=localhost,O=Spoonbill Test Only' \
  -ext SAN=DNS:localhost,IP:127.0.0.1 -storetype PKCS12 \
  -keystore modules/http/src/test/resources/tls/localhost.p12 \
  -storepass spoonbill-test-only -keypass spoonbill-test-only -noprompt
```
