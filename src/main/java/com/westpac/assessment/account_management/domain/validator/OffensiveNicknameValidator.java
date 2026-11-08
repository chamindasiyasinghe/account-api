package com.westpac.assessment.account_management.domain.validator;

import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Deterministic, case-insensitive whole-word/phrase deny-list policy. */
@Component
public class OffensiveNicknameValidator {
  private final List<String> offensiveNames;

  public OffensiveNicknameValidator(
      @Value("${account.offensive-nicknames-location:classpath:offensive-nicknames.txt}")
          Resource resource)
      throws IOException {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      offensiveNames =
          reader
              .lines()
              .map(String::strip)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .map(OffensiveNicknameValidator::normalize)
              .filter(line -> !line.isBlank())
              .distinct()
              .toList();
    }
    if (offensiveNames.isEmpty())
      throw new IllegalStateException("Offensive nickname deny-list must not be empty");
  }

  public void validate(String nickname) {
    if (nickname == null) return;
    String normalized = " " + normalize(nickname) + " ";
    if (offensiveNames.stream().anyMatch(name -> normalized.contains(" " + name + " "))) {
      throw new BusinessException("Account nickname contains a prohibited word or phrase");
    }
    // TODO: Agree a bank-approved multilingual policy and obfuscation rules before production.
    // This explicit list is not a comprehensive language moderation service.
  }

  private static String normalize(String value) {
    String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder normalized = new StringBuilder();
    for (int codePoint : folded.codePoints().toArray()) {
      if (Character.isLetterOrDigit(codePoint)) {
        normalized.appendCodePoint(codePoint);
      } else if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != ' ') {
        normalized.append(' ');
      }
    }
    return normalized.toString().strip();
  }
}
