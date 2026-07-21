package com.example.sqlgen.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 등록된 DB 연결의 비밀번호를 AES/GCM으로 암호화·복호화한다. IV는 매번 새로
 * 무작위 생성해 암호문 앞에 붙여 저장한다(GCM 표준 관례) - 같은 비밀번호를 여러 번
 * 등록해도 암호문이 매번 달라진다.
 *
 * <p>{@code sqlgen.encryption.secret-key}는 반드시 운영 환경에서 환경변수
 * ({@code SQLGEN_ENCRYPTION_KEY})로 별도 값을 넣어야 한다. 이 키가 바뀌면 이미
 * 저장된 연결들의 비밀번호는 복호화할 수 없게 되므로(재등록 필요), 키를 잃어버리지
 * 않도록 별도 보관이 필요하다.</p>
 */
@Component
public class SqlGenPasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec secretKey;

    public SqlGenPasswordEncryptor(
            @Value("${sqlgen.encryption.secret-key:bd9aNsMfEM9jvt6Qbb9SnmozRSZSpSvMMchdNo2MO7M=}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("비밀번호 암호화 실패: " + e.getMessage(), e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);

            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("비밀번호 복호화 실패: " + e.getMessage(), e);
        }
    }
}
