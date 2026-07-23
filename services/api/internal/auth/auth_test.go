package auth

import (
	"testing"
)

func TestArgon2PasswordHashing(t *testing.T) {
	password := "SecurePassword123!"

	hash, err := HashPassword(password)
	if err != nil {
		t.Fatalf("HashPassword failed: %v", err)
	}

	if len(hash) == 0 {
		t.Fatalf("expected non-empty hash string")
	}

	match, err := VerifyPassword(password, hash)
	if err != nil {
		t.Fatalf("VerifyPassword failed: %v", err)
	}
	if !match {
		t.Errorf("expected password to match hash")
	}

	wrongMatch, _ := VerifyPassword("WrongPassword!", hash)
	if wrongMatch {
		t.Errorf("expected wrong password to fail verification")
	}
}

func TestRecoveryCodeGeneration(t *testing.T) {
	code, verifierHash, err := GenerateRecoveryCode()
	if err != nil {
		t.Fatalf("GenerateRecoveryCode failed: %v", err)
	}

	if len(code) == 0 || len(verifierHash) == 0 {
		t.Fatalf("expected non-empty code and verifier hash")
	}

	if !VerifyRecoveryCode(code, verifierHash) {
		t.Errorf("expected recovery code to verify against hash")
	}

	invalidCode := "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-0000"
	if VerifyRecoveryCode(invalidCode, verifierHash) {
		t.Errorf("expected invalid recovery code to fail verification")
	}
}
