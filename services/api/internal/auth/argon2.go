package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"
	"sync"

	"golang.org/x/crypto/argon2"
)

type Argon2Params struct {
	Memory      uint32
	Iterations  uint32
	Parallelism uint8
	SaltLength  uint32
	KeyLength   uint32
}

var DefaultArgon2Params = Argon2Params{
	Memory:      64 * 1024, // 64 MB
	Iterations:  3,
	Parallelism: 2,
	SaltLength:  16,
	KeyLength:   32,
}

var (
	pepperMu  sync.RWMutex
	pepperKey []byte
	dummyHash = mustDummyHash()
)

// SetPepper configures the global server pepper key. If secret is non-empty,
// HMAC-SHA256(secret, password) is computed prior to Argon2id hashing.
func SetPepper(secret string) {
	pepperMu.Lock()
	pepperKey = []byte(secret)
	pepperMu.Unlock()

	// Compute a fresh dummyHash with the new pepper outside of pepperMu to avoid deadlocks.
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	if h, err := HashPassword(base64.RawStdEncoding.EncodeToString(buf)); err == nil {
		pepperMu.Lock()
		dummyHash = h
		pepperMu.Unlock()
	}
}

func preparePassword(password string) []byte {
	pepperMu.RLock()
	key := pepperKey
	pepperMu.RUnlock()

	if len(key) == 0 {
		return []byte(password)
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(password))
	return mac.Sum(nil)
}

// dummyHash is a valid Argon2id encoded hash of a random password, generated
// once at startup. Login handlers run VerifyPassword against it when the
// username does not exist so that the request spends the same CPU time as a
// real verification, closing the user-enumeration timing side channel.
func mustDummyHash() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	h, err := HashPassword(base64.RawStdEncoding.EncodeToString(buf))
	if err != nil {
		// Fall back to a static valid hash; the goal is timing parity, not secrecy.
		return "$argon2id$v=19$m=65536,t=3,p=2$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	}
	return h
}

// DummyVerify performs an Argon2id verification against a throwaway hash to
// equalize response time on the "user not found" path. The result is discarded.
func DummyVerify(password string) {
	pepperMu.RLock()
	dh := dummyHash
	pepperMu.RUnlock()
	_, _ = VerifyPassword(password, dh)
}

func HashPassword(password string) (string, error) {
	params := DefaultArgon2Params
	salt := make([]byte, params.SaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("failed to generate salt: %w", err)
	}

	pwBytes := preparePassword(password)
	hash := argon2.IDKey(pwBytes, salt, params.Iterations, params.Memory, params.Parallelism, params.KeyLength)

	b64Salt := base64.RawStdEncoding.EncodeToString(salt)
	b64Hash := base64.RawStdEncoding.EncodeToString(hash)

	encodedHash := fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, params.Memory, params.Iterations, params.Parallelism, b64Salt, b64Hash)

	return encodedHash, nil
}

func VerifyPassword(password, encodedHash string) (bool, error) {
	parts := strings.Split(encodedHash, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, fmt.Errorf("invalid hash format")
	}

	var version int
	_, err := fmt.Sscanf(parts[2], "v=%d", &version)
	if err != nil || version != argon2.Version {
		return false, fmt.Errorf("incompatible argon2 version")
	}

	var params Argon2Params
	_, err = fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &params.Memory, &params.Iterations, &params.Parallelism)
	if err != nil {
		return false, fmt.Errorf("invalid hash parameters")
	}

	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false, fmt.Errorf("failed to decode salt")
	}

	decodedHash, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false, fmt.Errorf("failed to decode hash")
	}

	params.KeyLength = uint32(len(decodedHash))

	// 1. Primary check: verify using active pepper (or raw if pepper is empty)
	pwBytes := preparePassword(password)
	computedHash := argon2.IDKey(pwBytes, salt, params.Iterations, params.Memory, params.Parallelism, params.KeyLength)

	if subtle.ConstantTimeCompare(decodedHash, computedHash) == 1 {
		return true, nil
	}

	// 2. Fallback check: if a pepper is active, check if the hash matches raw unpeppered password (legacy accounts)
	pepperMu.RLock()
	hasPepper := len(pepperKey) > 0
	pepperMu.RUnlock()

	if hasPepper {
		rawHash := argon2.IDKey([]byte(password), salt, params.Iterations, params.Memory, params.Parallelism, params.KeyLength)
		if subtle.ConstantTimeCompare(decodedHash, rawHash) == 1 {
			return true, nil
		}
	}

	return false, nil
}
