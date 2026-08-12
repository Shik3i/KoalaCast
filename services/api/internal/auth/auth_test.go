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

func TestArgon2RejectsUnsafeEncodedParameters(t *testing.T) {
	encoded := "$argon2id$v=19$m=4294967295,t=3,p=2$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	match, err := VerifyPassword("password", encoded)
	if err == nil || match {
		t.Fatalf("expected unsafe parameters to be rejected, got match=%v err=%v", match, err)
	}
}

func TestPepperPasswordHashing(t *testing.T) {
	// 1. Create a legacy unpeppered hash
	SetPepper("")
	legacyPassword := "LegacySecret123!"
	legacyHash, err := HashPassword(legacyPassword)
	if err != nil {
		t.Fatalf("failed to create legacy hash: %v", err)
	}

	// 2. Enable pepper key
	pepperSecret := "SuperSecretPepperKey999!"
	SetPepper(pepperSecret)
	defer SetPepper("") // cleanup

	// Verify new peppered hash creation & verification
	pepperedPassword := "PepperedSecret456!"
	pepperedHash, err := HashPassword(pepperedPassword)
	if err != nil {
		t.Fatalf("failed to create peppered hash: %v", err)
	}

	match, err := VerifyPassword(pepperedPassword, pepperedHash)
	if err != nil || !match {
		t.Errorf("expected peppered password to verify against peppered hash")
	}

	// Verify legacy unpeppered hash still succeeds via fallback
	legacyMatch, err := VerifyPassword(legacyPassword, legacyHash)
	if err != nil || !legacyMatch {
		t.Errorf("expected legacy unpeppered password to verify successfully via fallback")
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
