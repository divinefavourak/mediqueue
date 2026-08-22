package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.exception.MediQueueException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Hashes and verifies passwords with PBKDF2-HMAC-SHA256 (Project.md section 5,
 * "passwords shall be securely encrypted").
 *
 * <p>Three properties make this real password storage rather than the appearance of it:
 *
 * <ol>
 *   <li><b>It is slow on purpose.</b> Plain SHA-256 is built to be fast, so an attacker
 *       with a stolen table can test billions of guesses a second. PBKDF2 repeats the
 *       hash 120,000 times, turning that into a few thousand -- while a single honest
 *       login still costs well under a tenth of a second.</li>
 *   <li><b>Every user gets a random salt.</b> Without one, identical passwords produce
 *       identical hashes, so cracking a common password cracks it for every account at
 *       once, and precomputed rainbow tables apply directly.</li>
 *   <li><b>Verification is constant time.</b> See {@link #matches}.</li>
 * </ol>
 *
 * <p>Strictly, this is hashing, not encryption: there is deliberately no way back to the
 * original password, not even for us. That is the property a password store wants.
 *
 * <p>Everything used here ships with the JDK -- no BCrypt dependency needed.
 *
 * <p>Spring Boot port: swap for Spring Security's {@code Pbkdf2PasswordEncoder}, which
 * uses this same algorithm, so stored hashes remain valid.
 */
public final class PasswordEncoder {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordEncoder(int iterations) {
        this.iterations = iterations;
    }

    /** A fresh random salt, Base64 encoded for storage in a VARCHAR column. */
    public String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** Derives the stored hash for a password and salt. */
    public String hash(String rawPassword, String salt) {
        char[] characters = rawPassword.toCharArray();
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        PBEKeySpec spec = new PBEKeySpec(characters, saltBytes, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(derived);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new MediQueueException("Password hashing failed: " + e.getMessage(), e);
        } finally {
            // Clear the copy we control. Strings are immutable and cannot be wiped,
            // which is why PBEKeySpec takes a char[] in the first place.
            spec.clearPassword();
            java.util.Arrays.fill(characters, '\0');
        }
    }

    /**
     * Checks a password against a stored hash.
     *
     * <p>Uses {@link MessageDigest#isEqual} rather than {@code String.equals}. A normal
     * comparison returns as soon as two bytes differ, so the time it takes leaks how much
     * of the guess was correct -- enough, over many attempts, to reconstruct the hash one
     * byte at a time. isEqual always inspects every byte.
     */
    public boolean matches(String rawPassword, String salt, String expectedHash) {
        if (rawPassword == null || salt == null || expectedHash == null) {
            return false;
        }
        String actual = hash(rawPassword, salt);
        return MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
