# Project Design: DES Secure Document Encryption System

## Goal

Demonstrate the classic DES block cipher by encrypting a selected document, saving the ciphertext as a downloadable file, and recovering the original document with the same key.

## Architecture

```text
Browser (file selection and downloads)
              │ HTTP on localhost
              ▼
Java JDK HTTP server ── JCA/JCE DES/CBC/PKCS5Padding
              │
              └── transient in-memory bytes only
```

The server serves the UI and exposes encrypt/decrypt endpoints on one local origin. Upload bytes are held in memory for the request and are not written to disk or logged. Payloads are limited to 25 MiB. DES uses a fixed 8-byte key input (56 effective key bits); the backend rejects non-ASCII and non-eight-byte keys. Encryption creates a fresh random 8-byte CBC IV. The `.des` file stores a version marker and IV before the ciphertext so decryption is self-contained.

## Cryptographic flow

```text
Document ── DES-CBC + PKCS#5 padding ──► marker || random IV || ciphertext
Ciphertext file ── same key + stored IV ──► original document bytes
```

Java's standard cryptographic provider implements DES; no cipher internals are reimplemented. CBC requires an unpredictable IV, so a new IV is generated for every encryption. The format marker supports input validation. The UI retains the source filename only in browser memory for the current page session.

## Security and limitations

DES is historically important and useful for a syllabus demonstration, but its 56-bit effective key is too short for modern protection. CBC encryption alone also does not authenticate data: tampering may go undetected or surface only as a padding error. The service binds to loopback and is intended for local coursework demonstrations. Do not expose it to a network or use it for confidential documents. A real application should use authenticated encryption such as AES-GCM and modern key management.

## Technologies

- Java 11+ JDK standard library for the local HTTP server and JCA/JCE cryptography.
- Plain HTML/CSS/JavaScript frontend, served from the Java process.
- No database, cloud storage, external dependencies, persistent file storage, or account system.

## Demonstration checklist

- Encrypt a small text or PDF file with an eight-character ASCII key.
- Confirm the output has a `.des` extension and differs from the input.
- Decrypt that output using the same key and compare the recovered file.
- Try a wrong key or damaged ciphertext to observe decryption failure/limitations.
- Explain the 64-bit block size, 56-bit effective key, CBC IV, padding, and why DES is obsolete.
