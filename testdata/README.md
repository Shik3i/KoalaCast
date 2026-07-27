# Test Data (`testdata`)

Fixture feeds used by the Go test suite. Go automatically ignores any directory
named `testdata` when building, so nothing here ships in binaries.

```text
testdata/
└── feeds/
    ├── sample_podcast.xml         Standard RSS 2.0 podcast feed
    └── podcasting20_sample.xml    Feed exercising Podcasting 2.0 tags
```

## Usage

These fixtures back the
[`services/api/internal/rss/parser_test.go`](../services/api/internal/rss/parser_test.go)
parser tests and the feed-handling
handler/integration tests. They let the parser be tested deterministically
without any network access.

## Adding a Fixture

- Keep feeds **small** and focused on the specific case under test
  (a new namespace, a malformed field, an encoding quirk, etc.).
- Prefer trimming a real feed down to the minimum that reproduces the case.
- Reference the new file from a test and describe what it covers in a comment.
- Do not include private or copyrighted full-length content — a couple of
  representative items is enough.
