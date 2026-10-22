# Request cookies and standalone response headers

Device IDs accepted during page bootstrap must contain 1–128 ASCII letters,
digits, or `._~-`. This supports the built-in generator, UUIDs, and unpadded
base64url custom generators. Invalid or ambiguous incoming IDs receive a new
ID; invalid custom-generator output fails before state initialization. Deployments
with generators outside this format must migrate them before upgrading; existing
invalid device cookies no longer select their former presentation state. Device
IDs are presentation identifiers, not authentication credentials. Other
device-bound HTTP and WebSocket routes treat malformed IDs as absent.

Cookie helpers retain the UTF-8 form-encoding contract: `+` decodes to space,
percent escapes decode once, and `withCookie` and `Headers.setCookie` encode
names and values symmetrically. Values may contain encoded `;` and `=`; raw `=`
after the first separator is part of the value. Malformed percent escapes make
that cookie unavailable without failing unrelated cookies. Duplicate decoded
names are unavailable, including a valid value paired with a malformed value.
`withCookie` explicitly replaces all matching names and preserves unrelated
pairs. The standalone parser joins repeated Cookie fields before parsing so
ordering does not resolve duplicates. Security adapters retain their stricter
credential validation.

The standalone HTTP response serializer validates all field names as HTTP tokens
and rejects CR, LF, NUL, DEL, and other ASCII controls in field values except
horizontal tab. The same check protects custom status phrases. Validation happens
before any response bytes are emitted, including for application headers that
bypass cookie helpers. Cookie paths reject controls and semicolons. Cookie values
are encoded, so delimiters cannot become raw response headers. Normal redirects
and repeated Set-Cookie fields remain supported.

Regression commands, from the repository's Nix environment:

```sh
nix develop -c sbt 'web/testOnly spoonbill.web.RequestSpec spoonbill.web.HeadersSpec' 'http/testOnly spoonbill.http.protocol.Http11Spec' 'spoonbill/testOnly spoonbill.server.internal.services.DeviceIdSpec' 'standalone/testOnly spoonbill.server.StandaloneHeaderSafetySpec'
```

The standalone fixture uses real loopback TCP responses with both unguarded and
installed browser-authentication configurations. It covers encoded CR/LF canaries,
malformed escapes, duplicate cookies (including separate Cookie fields), oversized
IDs, valid custom IDs, and binding-cookie output. These tests establish the local
standalone boundary; deployed proxies and packaging still need their own checks.
