# Local TLS test fixture

`localhost.p12` contains a public, test-only private key and self-signed localhost
certificate. Its password is `spoonbill-test-only`. It is used only by
`HttpClientSpec`; never use this key for an application or deployment. The client
trust context accepts this certificate through a test keystore, without disabling
certificate validation globally or trusting arbitrary certificates.

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
