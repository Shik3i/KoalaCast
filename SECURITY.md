# Security Policy

## Supported Versions

KoalaCast is under active development. Security fixes are applied to the `main`
branch and the latest published Docker images.

| Version | Supported |
| :--- | :--- |
| `main` / latest release | ✅ |
| Older tagged releases | ❌ |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Instead, use one of the following private channels:

1. **GitHub Security Advisories** (preferred) — open a private report via the
   repository's **Security → Report a vulnerability** tab.
2. **Email** — contact the maintainer at the address listed on the GitHub profile
   of [@Shik3i](https://github.com/Shik3i).

Please include, where possible:

- A description of the vulnerability and its impact.
- Steps to reproduce (proof-of-concept, affected endpoint, or payload).
- Affected version / commit and your environment.
- Any suggested remediation.

## What to Expect

- **Acknowledgement** within 72 hours.
- An assessment and, if confirmed, a remediation plan with a target timeline.
- Credit in the release notes once a fix ships, unless you prefer to remain anonymous.

## Scope & Hardening Notes

KoalaCast already ships several defensive measures relevant to reports:

- **SSRF protection** on all outbound feed fetches (blocks loopback, private,
  link-local, CGNAT, and cloud-metadata addresses; validates on redirects).
- **Response-size limits** on RSS bodies to guard against decompression/DoS abuse.
- **Argon2id** password hashing and HttpOnly, same-site session cookies.
- **Rate limiting** on authentication endpoints.

Reports that strengthen or find gaps in these areas are especially valued.

Thank you for helping keep KoalaCast and its users safe.
