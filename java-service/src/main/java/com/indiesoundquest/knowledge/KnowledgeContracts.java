package com.indiesoundquest.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class KnowledgeContracts {
  private KnowledgeContracts() {}
  public record Entity(@NotBlank @Pattern(regexp="ARTIST|RELEASE_GROUP|RELEASE|WORK|RECORDING") String type,
      @NotBlank @Size(max=100) String id, @Pattern(regexp="SUBJECT|CREATOR|ALBUM_CONTEXT|RELATED_WORK") String role) {}
  public record Source(@NotBlank @Size(max=300) String identifier, @NotBlank @Size(max=250) String title,
      @Size(max=1500) String url, @Size(max=250) String publisher, @Size(max=20) String language,
      @Size(max=100) String publishedAt, @NotBlank @Size(max=500) String rightsNote,
      @Size(max=100) String originGroup, @NotBlank @Size(max=100) String materialType) {}
  public record Support(@NotNull @Valid Source source, @NotBlank @Size(max=2000) String excerpt,
      @NotBlank @Size(max=500) String locator,
      @NotBlank @Pattern(regexp="SUPPORTS|CONTRADICTS|CONTEXT_ONLY") String relation) {}
  public record Theme(@NotBlank @Size(max=180) String title, @NotBlank @Size(max=180) String artist,
      @Size(max=12) List<@Size(max=60) String> themes, @Size(max=12) List<@Size(max=60) String> moods,
      @Size(max=12) List<@Size(max=60) String> scenes) {}
  public record Submission(@NotBlank @Pattern(regexp="FACT|ATTRIBUTED_STATEMENT|EDITORIAL_CONTEXT|THEME_CARD") String knowledgeType,
      @NotBlank @Size(max=3000) String summary, @Size(max=500) String boundary,
      @Size(max=300) String attribution, @Size(max=300) String applicability,
      @Size(max=12) List<@Size(max=150) String> aliases,
      @NotEmpty @Size(max=20) List<@Valid Entity> entities,
      @NotEmpty @Size(max=12) List<@Valid Support> support, @Valid Theme theme) {}
  public record ImportRequest(@NotBlank @Pattern(regexp="MANUAL_FILE|MANUAL_URL|MANUAL_EDIT") String origin,
      @NotEmpty @Size(max=100) List<@Valid Submission> candidateItems) {}
  public record Decision(@Min(1) int revision, @NotBlank @Pattern(regexp="APPROVED|REJECTED") String status,
      @NotBlank @Size(max=1000) String note) {}
  public record Publish(@Min(1) int revision) {}
  public record Reason(@NotBlank @Size(max=500) String reason) {}
  public record Search(@NotBlank @Size(max=1000) String query, @Size(max=20) List<@Valid Entity> entities,
      @Pattern(regexp="STRICT|RELATED_CONTEXT") String entityScope,
      @Size(max=4) List<@Pattern(regexp="FACT|ATTRIBUTED_STATEMENT|EDITORIAL_CONTEXT|THEME_CARD") String> knowledgeTypes,
      @Min(1) @Max(20) int limit) {}
  public record Hit(@NotBlank String entryId, @Min(1) int revision) {}
  public record Verify(@NotNull @Size(max=100) List<@Valid Hit> hits, @Valid Search search) {}
  public record Claim(@NotBlank @Size(max=100) String workerId) {}
  public record Finish(boolean success, @Size(max=100) String errorCode) {}
  public record Generation(@NotBlank String model, @Min(1) int dimension, @NotBlank @Pattern(regexp="[a-f0-9]{40}") String modelRevision,
      @NotBlank String templateVersion) {}
}
