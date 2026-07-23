package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/hex"
	"fmt"
	"strings"
)

var customBase32Encoding = base32.NewEncoding("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567").WithPadding(base32.NoPadding)

// GenerateRecoveryCode creates 32 cryptographically secure random bytes and returns:
// 1. Human-readable grouped Base32 string (e.g., AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH)
// 2. SHA-256 verifier hash for server-side storage
func GenerateRecoveryCode() (string, string, error) {
	randomBytes := make([]byte, 32)
	if _, err := rand.Read(randomBytes); err != nil {
		return "", "", fmt.Errorf("failed to generate random recovery bytes: %w", err)
	}

	rawBase32 := customBase32Encoding.EncodeToString(randomBytes)
	if len(rawBase32) > 32 {
		rawBase32 = rawBase32[:32]
	}

	// Format into 8 groups of 4 characters: AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH
	var groups []string
	for i := 0; i < len(rawBase32); i += 4 {
		end := i + 4
		if end > len(rawBase32) {
			end = len(rawBase32)
		}
		groups = append(groups, rawBase32[i:end])
	}

	formattedCode := strings.Join(groups, "-")
	verifierHash := HashRecoveryCode(formattedCode)

	return formattedCode, verifierHash, nil
}

// HashRecoveryCode computes SHA-256 hash of normalized recovery code.
func HashRecoveryCode(rawCode string) string {
	normalized := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(rawCode), "-", ""))
	hash := sha256.Sum256([]byte(normalized))
	return hex.EncodeToString(hash[:])
}

// VerifyRecoveryCode checks if a user-supplied recovery code matches stored verifier hash.
func VerifyRecoveryCode(inputCode, storedVerifierHash string) bool {
	computed := HashRecoveryCode(inputCode)
	return computed == storedVerifierHash
}
