package com.example.sqlgen.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SqlGenPasswordEncryptor}가 저장된 DB 연결 비밀번호를 안전하게 암호화·복호화하는지
 * 검증한다. 등록 화면에서 평문 대신 암호화해서 저장하기로 한 결정에 따라 추가됨.
 */
class SqlGenPasswordEncryptorTest {

    // 32바이트(AES-256) 테스트 전용 키. 운영 키와 무관하다.
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final SqlGenPasswordEncryptor encryptor = new SqlGenPasswordEncryptor(TEST_KEY);

    @Test
    @DisplayName("암호화한 값을 복호화하면 원래 비밀번호가 그대로 나온다")
    void encryptThenDecryptRoundTrips() {
        String original = "sqmsap1234!@#";

        String encrypted = encryptor.encrypt(original);
        String decrypted = encryptor.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName("암호화된 값은 평문과 다르다 - DB에 평문이 그대로 남지 않는다")
    void encryptedValueDiffersFromPlainText() {
        String original = "sqmsap1234!@#";
        assertThat(encryptor.encrypt(original)).isNotEqualTo(original);
    }

    @Test
    @DisplayName("같은 비밀번호를 두 번 암호화해도 매번 다른 암호문이 나온다 (IV가 매번 무작위라서)")
    void sameInputProducesDifferentCiphertextEachTime() {
        String original = "sqmsap1234!@#";
        assertThat(encryptor.encrypt(original)).isNotEqualTo(encryptor.encrypt(original));
    }

    @Test
    @DisplayName("다른 키로 암호화한 값은 복호화할 수 없다 - 키가 바뀌면 재등록이 필요한 이유")
    void cannotDecryptWithDifferentKey() {
        String encryptedWithTestKey = encryptor.encrypt("sqmsap1234!@#");
        String otherKey = Base64.getEncoder().encodeToString(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
        SqlGenPasswordEncryptor otherEncryptor = new SqlGenPasswordEncryptor(otherKey);

        assertThatThrownBy(() -> otherEncryptor.decrypt(encryptedWithTestKey))
                .isInstanceOf(IllegalStateException.class);
    }
}
