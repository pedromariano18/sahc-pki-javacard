# Group 5 Project — JavaCard Applet for PKI

Diogo Valverde (up202509119), Pedro Mariano (up202509341), Pedro Teixeira (up202309307)

---

## What is included

```
sahc-pki-javacard/
├── README.md
├── report.pdf
├── certs/
│   └── rootCA.crt
├── configs/
│   ├── nginx-sahc-tls
│   └── pam_pkcs11.conf
└── src/
    ├── SmartcardSigner.java
    ├── pom.xml
    ├── pkcs11.cfg
    └── pkcs11-cc-sign.cfg
```

- `certs/rootCA.crt` — the SAHC Root CA certificate, used both to complete the PDF
  signing chain (Step 1) and to verify the signature/TLS client certs afterwards.
- `configs/nginx-sahc-tls` — the nginx server block used for the mutual-TLS
  authentication scenario (client certs are checked against `rootCA.crt`). Update the
  `ssl_certificate*` and `ssl_client_certificate` paths before using it on another machine.
- `configs/pam_pkcs11.conf` — the `pam_pkcs11` configuration used for the smartcard-based
  PAM login scenario.

The SSH and S/MIME scenarios were performed using standard unmodified applications and
are fully documented in the report; they require no custom code or config beyond what
those applications already provide. The TLS and PAM scenarios use the config files
above (adapted from their distribution defaults) — also documented in detail in the report.

---

## Requirements

Tested on Ubuntu 24 with:

- Java 17
- Maven
- OpenSC 0.25.0
- pcsc-lite / pcscd
- Apache PDFBox 2.0.30 (fetched automatically by Maven)
- BouncyCastle 1.77 (fetched automatically by Maven)
- A JavaCard with IsoApplet installed and provisioned

Install the required packages:

```bash
sudo apt update
sudo apt install openjdk-17-jdk maven opensc pcscd pcsc-tools poppler-utils
```

---

## Step 1 — Configure paths in SmartcardSigner.java

The following paths at the top of `main()` must be updated before running:

```java
String pasta  = System.getProperty("user.home") + "/Uni/Mestrado/SAHC/work/pdf-signer/";
String inPdf  = pasta + "teste_limpo.pdf";
String outPdf = pasta + "teste_limpo_assinado.pdf";
```

And the root CA certificate path:

```java
new FileInputStream(System.getProperty("user.home") + "/Uni/Mestrado/SAHC/work/rootCA.crt")
```

Change these to the local paths of your input PDF and output PDF. For the root CA, you
can point this at the `certs/rootCA.crt` file included in this repo, or your own.

The PKCS#11 config path can be overridden at runtime without editing the source:

```bash
mvn exec:java -Dexec.mainClass="pt.up.fc.sahc.SmartcardSigner" -Dpkcs11.cfg=/path/to/pkcs11.cfg
```

---

## Step 2 — Start the smartcard service and verify the card is detected

```bash
sudo systemctl start pcscd
opensc-tool -l
pkcs15-tool --dump
pkcs11-tool --module /usr/lib/x86_64-linux-gnu/opensc-pkcs11.so -O
```

---

## Step 3 — Build

`pom.xml` lives in `src/` but expects sources at the standard Maven layout
(`src/main/java/...`), while `SmartcardSigner.java` ships flat next to it. Move it into
place once, then build:

```bash
cd src
mkdir -p src/main/java/pt/up/fc/sahc
mv SmartcardSigner.java src/main/java/pt/up/fc/sahc/
mvn clean compile
```

---

## Step 4 — Run (IsoApplet token)

```bash
mvn exec:java -Dexec.mainClass="pt.up.fc.sahc.SmartcardSigner"
```

The application prompts for the smartcard PIN and writes the signed PDF to the
configured output path.

---

## Variant — Run with the Portuguese Citizen Card

The Citizen Card uses a separate Sign PIN slot. Use the included config file:

```bash
mvn exec:java -Dexec.mainClass="pt.up.fc.sahc.SmartcardSigner" \
    -Dpkcs11.cfg=pkcs11-cc-sign.cfg
```

---

## Step 5 — Verify the signed PDF

```bash
pdfsig teste_limpo_assinado.pdf
```

Expected output:

```
Signature Validation: Signature is Valid
Certificate Validation: Certificate is Trusted
```

The certificate will only appear as trusted if the SAHC Root CA (or the Citizen Card
state CA) is trusted locally. For the IsoApplet token, import `certs/rootCA.crt` into
the system trust store before verifying.
