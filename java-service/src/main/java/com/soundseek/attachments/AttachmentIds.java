package com.soundseek.attachments;

import java.util.*;

public final class AttachmentIds {
  private AttachmentIds() {}

  public static List<UUID> decode(String value) {
    return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(UUID::fromString).toList();
  }

  public static String encode(List<UUID> ids) {
    return String.join(",", ids.stream().map(UUID::toString).toList());
  }

  public static List<UUID> validate(String content, List<UUID> ids) {
    var value = ids == null ? List.<UUID>of() : ids;
    if (value.size() > 10 || value.stream().anyMatch(Objects::isNull)
        || new HashSet<>(value).size() != value.size() || content == null
        || content.length() > 2000 || (content.isBlank() && value.isEmpty())) {
      throw new IllegalArgumentException("请填写消息或添加图片，每次最多10张");
    }
    return List.copyOf(value);
  }
}
