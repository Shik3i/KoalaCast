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
  - Cloud metadata endpoints (`169.254.169.254`)
- Validates redirects across every hop against the same IP filter rules.

### 3. Feed Aliasing (Handling Feed URL Changes)
When a podcast publisher moves their feed URL (e.g. HTTP 301/308 redirects or `<itunes:new-feed-url>` tags):
- The server records the original URL in the `podcast_aliases` table pointing to the canonical `podcasts.id`.
- Prevents duplicate podcasts from being created when users add old/new URLs.

### 4. Episode Identity Rules
- Episodes use standard `<guid>` elements where present.
- If GUID is missing or duplicated, a deterministic fallback hash is derived from `SHA256(enclosure_url + title + pub_date)`.
- Prevents duplicate episodes during feed updates or minor title edits.
