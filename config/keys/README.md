# JWT RSA Keys (local / secret mount)

Private keys must **never** be committed to git.

## Generate a key pair

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private_pkcs8.pem
openssl rsa -pubout -in private_pkcs8.pem -out public_x509.pem
```

Place the files in this directory (`config/keys/`) for local development, or inject PEM content via environment variables:

| Variable | Purpose |
|----------|---------|
| `JWT_RSA_PRIVATE_KEY` | Full PKCS#8 PEM content |
| `JWT_RSA_PUBLIC_KEY` | Full X.509 PEM content |
| `JWT_RSA_PRIVATE_KEY_PATH` | Override file path (default `file:./config/keys/private_pkcs8.pem`) |
| `JWT_RSA_PUBLIC_KEY_PATH` | Override file path (default `file:./config/keys/public_x509.pem`) |

Production should use a secret store (K8s Secret / Vault) and set the PEM env vars — do not bake keys into the JAR.
