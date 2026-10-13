# DES Secure Document Encryption System

A small university demonstration of encrypting and decrypting documents with the Data Encryption Standard (DES). The browser UI sends file bytes to a local Java service, which encrypts them with Java's JCA/JCE implementation. It does not persist uploaded files.

> **Coursework only:** DES is obsolete. Its 56-bit effective key is vulnerable to brute-force search. Do not use this application or DES to protect real documents. Use an authenticated modern cipher such as AES-GCM for real systems.

## Run

Requires a JDK 11 or newer. From this directory:

```powershell
.\start.ps1
```

Then open <http://localhost:8080>. Stop the server with Ctrl+C.

## Use

1. Choose a document (up to 25 MiB).
2. Enter exactly eight printable ASCII characters as the DES key and keep it for decryption.
3. Select **Encrypt document**. The downloaded `.des` file contains a format marker, random 8-byte CBC IV, and DES ciphertext.
4. Select the `.des` file, enter the same key, and choose **Decrypt document**.

Encryption uses DES in CBC mode with PKCS#5 padding. The IV is randomly generated for each encryption and stored with the ciphertext; it is not secret. The key is sent only to the local backend over loopback and is never saved. This educational design does not authenticate ciphertext, so it must not be used for real security.

## Project structure

```text
Secure-Document-Encryption/
├── README.md
├── project.md
├── start.ps1
├── backend/src/main/java/edu/demo/des/DesServer.java
└── web/
    ├── index.html
    ├── app.js
    └── styles.css
```

The backend uses only JDK APIs (`HttpServer`, `Cipher`, `SecretKeyFactory`/`SecretKeySpec`, and `SecureRandom`). The frontend is dependency-free HTML, CSS, and JavaScript, served by the Java process from the same origin.
