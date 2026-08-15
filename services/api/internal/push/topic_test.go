package push

import (
	"strings"
	"testing"
)

// RFC 8030 §5.4 caps the Topic header at 32 characters from the URL-safe base64
// alphabet. Push services reject the entire request when it is longer, so a
// topic built by concatenating a UUID silently disabled every new-episode
// notification.
func TestPushTopicFitsRFC8030(t *testing.T) {
	const urlSafeBase64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

	ids := []string{
		"dbb4aca4-77d5-4ddf-9e4a-ddde8a68ae29",
		"",
		strings.Repeat("x", 512),
	}
	for _, id := range ids {
		topic := pushTopic(id)
		if len(topic) != 32 {
			t.Fatalf("topic for %q is %d characters, want 32: %q", id, len(topic), topic)
		}
		if strings.ContainsFunc(topic, func(r rune) bool {
			return !strings.ContainsRune(urlSafeBase64, r)
		}) {
			t.Fatalf("topic for %q leaves the URL-safe base64 alphabet: %q", id, topic)
		}
	}
}

// The topic exists so a queued notification for a show is replaced by a newer
// one instead of stacking. That only works if it is stable per podcast and
// distinct between podcasts.
func TestPushTopicIsStableAndDistinct(t *testing.T) {
	first := pushTopic("pod-1")
	if first != pushTopic("pod-1") {
		t.Fatal("topic is not stable for the same podcast")
	}
	if first == pushTopic("pod-2") {
		t.Fatal("two podcasts collapsed onto the same topic")
	}
}
