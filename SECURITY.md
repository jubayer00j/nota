# Security Policy

## Local-First Security Model

Nota is a local-first notes application for Android. All notes, attachments, and metadata are stored locally on the device using Room. Nota does not transmit user notes to external servers by default and enforces HTTPS-only network traffic (`cleartextTrafficPermitted="false"`).

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.35.x  | :white_check_mark: |
| < 0.35  | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability in Nota, please report it privately using **GitHub Private Vulnerability Reporting** on the `jubayer00j/nota` repository:

1. Navigate to the **Security** tab of the `jubayer00j/nota` repository on GitHub.
2. Click **Report a vulnerability**.
3. Provide a clear description of the issue, steps to reproduce, and any potential impact.

Please do not open public GitHub issues for undisclosed security vulnerabilities.
