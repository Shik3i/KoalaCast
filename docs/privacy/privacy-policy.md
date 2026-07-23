# Privacy Policy & Principles

KoalaCast is built from the ground up around strict user privacy principles.

## Core Privacy Rules

1. **Zero Behavioral Tracking**: No analytics scripts, no telemetry, no tracking pixels, no event logging for marketing or user profiling.
2. **No Data Selling or Monetization**: User data is never sold, shared, or processed for advertising.
3. **No Social Login / Email Requirements**: Accounts require only a username and password. Email addresses are never collected.
4. **Local Browser Mode**: The web application is 100% functional without an account. Subscriptions, playback positions, history, queue, and settings remain stored in the browser's IndexedDB.
5. **Direct Publisher Audio**: Audio files are streamed directly from podcast publishers to client browsers. KoalaCast servers never proxy or log audio requests.
6. **Session IP Anonymization**: Session records store only anonymized/truncated IP subnets (e.g. `192.168.1.0` or `2001:db8::`) to assist users in identifying active devices without maintaining full geographic or network tracking.
7. **User Session Customization**: Users may assign friendly custom names to their active devices and sessions.

## Local Mode vs. Synced Mode

| Feature | Local Browser Mode | Synced Account Mode |
| :--- | :--- | :--- |
| **Account Required** | No | Yes (Username + Password) |
| **Data Storage Location** | Local IndexedDB | Server SQLite Database |
| **Cross-Device Sync** | None | Automatic |
| **Server Metadata Use** | Stateless RSS metadata & search proxying | User account subscriptions & sync state |
| **Audio Playback** | Direct publisher CDN | Direct publisher CDN |
