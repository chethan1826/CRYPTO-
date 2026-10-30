# DES Secure Document Encryption System

A small university demonstration of encrypting and decrypting documents with the Data Encryption Standard (DES). The browser UI sends file bytes to a local Java service, which encrypts them with Java's JCA/JCE implementation. A local account screen protects the workspace; passwords are hashed on the server with bcrypt.

> **Coursework only:** DES is obsolete. Its 56-bit effective key is vulnerable to brute-force search. Do not use this application or DES to protect real documents. Use an authenticated modern cipher such as AES-GCM for real systems.

## Run

Requires a JDK 11 or newer. From this directory:

```powershell
.\start.ps1
```

Then open <http://localhost:8080>. Stop the server with Ctrl+C.

## Use

1. Create a local account or sign in. Account storage contains email addresses and bcrypt hashes, not plaintext passwords.
2. Choose a document (up to 25 MiB).
3. To generate a key for encryption, re-enter your account password, enter a different encryption password (at least 8 characters), and select **Generate key**. The server verifies the account password; the browser derives an 8-character DES key using both passwords. Select **Copy generated key** and keep/share it safely. During decryption, enter the same two passwords and manually enter the copied 8-character key; the app checks that they match. You can also enter a DES key manually during encryption.
4. Select **Encrypt document**. The downloaded `.des` file contains a format marker, random 8-byte CBC IV, and DES ciphertext. The original filename and MIME type are carried inside the encrypted payload.
5. Select the `.des` file. Enter the same account password and encryption password, then manually enter or paste the copied DES key. The app verifies the password pair and key match before decrypting. The recovered download uses the original filename, extension, and MIME type.

Generated keys use PBKDF2-HMAC-SHA-256 with 310,000 iterations and the normalized signed-in email as the public salt. The derivation combines the account password and a separate encryption password, then maps the output to eight printable ASCII characters. Before deriving, the server re-verifies the account password with bcrypt. The derivation happens in the browser; neither password nor the DES key is saved. The DES key is manually entered in the decryption section. Encryption uses DES in CBC mode with PKCS#5 padding. The IV is randomly generated for each encryption and stored with the ciphertext; it is not secret. The DES key is sent only to the local backend over loopback. Login passwords are hashed with jBCrypt at cost 12; account hashes are stored in the ignored `build/users.properties` file. The HttpOnly, SameSite=Strict session cookie expires after one hour. This local educational design does not authenticate DES ciphertext, so it must not be used for real security.

Bcrypt is a one-way password-hashing function, not encryption. The frontend sends the password to the loopback-only Java service for registration or login; the server never logs it and persists only its bcrypt hash.

## Project structure

```text
Secure-Document-Encryption/
├── README.md
├── project.md
├── start.ps1
├── lib/
│   ├── jbcrypt-0.4.jar
│   └── JBCRYPT-LICENSE.txt
├── backend/src/main/java/edu/demo/des/DesServer.java
└── web/
    ├── index.html
    ├── app.js
    ├── auth.css
    └── styles.css
```

The backend uses JDK APIs plus the pinned jBCrypt 0.4 library (`org.mindrot:jbcrypt`) for bcrypt. The library's license is included in `lib/JBCRYPT-LICENSE.txt`. The frontend is dependency-free HTML, CSS, and JavaScript, served by the Java process from the same origin.
