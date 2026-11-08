package com.westpac.assessment.account_management.domain.validator;

import static org.assertj.core.api.Assertions.*;

import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class OffensiveNicknameValidatorTest {
  OffensiveNicknameValidator validator(String list) throws Exception {
    return new OffensiveNicknameValidator(
        new ByteArrayResource(list.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsCaseInsensitiveWholeWordsAndPhrases() throws Exception {
    var policy = validator("# comment\nidiot\noffensive nickname\n");
    for (String nickname : new String[] {"IDIOT savings", "Offensive-Nickname", "ＩＤＩＯＴ savings"})
      assertThatThrownBy(() -> policy.validate(nickname)).isInstanceOf(BusinessException.class);
  }

  @Test
  void acceptsNullSafeNamesAndDoesNotMatchInsideUnrelatedWords() throws Exception {
    var policy = validator("ass\nidiot\n");
    assertThatCode(() -> policy.validate(null)).doesNotThrowAnyException();
    assertThatCode(() -> policy.validate("Class savings")).doesNotThrowAnyException();
    assertThatCode(() -> policy.validate("Holiday savings")).doesNotThrowAnyException();
  }

  @Test
  void rejectsAnEmptyPolicyAtStartup() {
    assertThatThrownBy(() -> validator("# only comments\n"))
        .isInstanceOf(IllegalStateException.class);
  }
}
