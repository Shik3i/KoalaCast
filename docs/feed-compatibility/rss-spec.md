# RSS Feed Compatibility & Feed Aliasing Specification

KoalaCast parses standard RSS 2.0, Atom feeds, and Podcasting 2.0 extension tags.

## RSS Feed Processing Principles

### 1. Robust Feed Parsing
- Supports standard `<rss>`, `<feed>`, `<item>`, `<entry>` structures.
- Parses podcast artwork, explicit flags, duration, episode numbers, seasons, and HTML descriptions.
- Preserves Podcasting 2.0 tags (`<podcast:chapters>`, `<podcast:transcript>`, `<podcast:person>`, `<podcast:funding>`, `<podcast:location>`, `<podcast:value>`).

### 2. SSRF Protection Filters
User-submitted feed URLs undergo strict validation before fetching:
- Scheme must be `http` or `https`.
- Pre-resolves domain IP and blocks:
  - Loopback (`127.0.0.0/8`, `::1`)
  - Private ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
  - Link-local (`169.254.0.0/16`, `fe80::/10`)
  - Carrier-grade NAT (`100.64.0.0/10`)
  - Unspecified, multicast, reserved and documentation ranges
  - IPv6 unique-local (`fc00::/7`) and multicast (`ff00::/8`)
  - Cloud metadata endpoints (`169.254.169.254`)
- Validates redirects across every hop against the same IP filter rules.

### 3. Feed Aliasing (Handling Feed URL Changes)
When a podcast publisher moves their feed URL (e.g. HTTP 301/308 redirects or `<itunes:new-feed-url>` tags):
- The server records the original URL in the `podcast_aliases` table pointing to the canonical `podcasts.id`.
- Prevents duplicate podcasts from being created when users add old/new URLs.

### 4. Episode Identity Rules
- Episodes use `<guid>` (RSS) or `<id>` (Atom) where present.
- Without one, a non-empty enclosure URL becomes the stable key after
  lower-casing. Only an episode without both an ID and enclosure URL uses a
  deterministic `SHA256(title|enclosure_url|publication_unix_time)` key.
- Duplicate keys inside one feed are retained with deterministic `#dupN`
  suffixes instead of silently dropping an episode.
