package com.soundseek.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.soundseek.redis.ProviderCacheService;
import com.soundseek.tournament.domain.Artist;
import com.soundseek.tournament.domain.Recording;
import com.soundseek.tournament.repository.ArtistRepository;
import com.soundseek.tournament.repository.RecordingRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MusicBrainzCatalogServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void recordingResearchUsesExplicitArtistAndTitleFields() {
    assertThat(MusicBrainzCatalogService.researchLuceneQuery(
        "recording", "张悬 宝贝", "张悬", "宝贝"))
        .isEqualTo("recording:\"宝贝\" AND artist:\"张悬\"");
  }

  @Test
  void exactResearchContractRejectsSameTitleFromWrongArtist() throws Exception {
    var wrongArtist = objectMapper.readTree("""
        {"id":"09a7d13b-f9a5-4b95-b42c-f416aaa73b34","title":"宝贝",
         "artist-credit":[{"name":"布仁巴雅尔","artist":{"name":"布仁巴雅尔"}}]}
        """);
    var correctArtist = objectMapper.readTree("""
        {"id":"541523a2-3277-4bdb-9d8e-2ed06ea6115e","title":"寶貝",
         "artist-credit":[{"name":"張懸","artist":{"name":"張懸"}}]}
        """);

    assertThat(MusicBrainzCatalogService.matchesResearchContract(
        "recording", wrongArtist, "张悬", "宝贝")).isFalse();
    assertThat(MusicBrainzCatalogService.matchesResearchContract(
        "recording", correctArtist, "张悬", "宝贝")).isTrue();
  }
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeCatalogReadsLaterPagesBeforePublishingCandidates(boolean failSecondPage) throws Exception {
    var artistMbid = UUID.randomUUID().toString();
    var guestArtistMbid = UUID.randomUUID().toString();
    var firstPage = objectMapper.createObjectNode().put("recording-count", 103).put("recording-offset", 0);
    var firstTracks = firstPage.putArray("recordings");
    var repeatedMbid = UUID.randomUUID().toString();
    for (int index = 0; index < 100; index++) {
      addRecording(firstTracks, index == 0 ? repeatedMbid : UUID.randomUUID().toString(), "Blue", artistMbid, null);
    }
    var secondPage = objectMapper.createObjectNode().put("recording-count", 103).put("recording-offset", 100);
    var secondTracks = secondPage.putArray("recordings");
    addRecording(secondTracks, repeatedMbid, "Blue", artistMbid, null);
    addRecording(secondTracks, UUID.randomUUID().toString(), "Collaboration", guestArtistMbid, artistMbid);
    addRecording(secondTracks, UUID.randomUUID().toString(), "Other artist only", guestArtistMbid, null);

    var queries = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/recording", exchange -> {
      var query = exchange.getRequestURI().getQuery();
      queries.add(query);
      boolean laterPage = query.contains("offset=100");
      byte[] body = objectMapper.writeValueAsBytes(laterPage ? secondPage : firstPage);
      exchange.sendResponseHeaders(laterPage && failSecondPage ? 502 : 200, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      var artists = mock(ArtistRepository.class);
      var recordings = mock(RecordingRepository.class);
      var cache = mock(ProviderCacheService.class);
      when(cache.get(anyString(), anyString())).thenReturn(Optional.empty());
      when(artists.save(any(Artist.class))).thenAnswer(invocation -> invocation.getArgument(0));
      when(recordings.save(any(Recording.class))).thenAnswer(invocation -> invocation.getArgument(0));
      var service = new MusicBrainzCatalogService(artists, recordings, objectMapper, cache,
          "http://127.0.0.1:" + server.getAddress().getPort(), "SoundSeek/Test");
      var seeds = List.of(new MusicBrainzCatalogService.ArtistSeed(artistMbid, "Named Artist", null));

      if (failSecondPage) {
        assertThatThrownBy(() -> service.discoverCompleteArtistCatalog(seeds))
            .hasMessage("MUSICBRAINZ_CATALOG_UNAVAILABLE");
        verifyNoInteractions(recordings);
      } else {
        assertThat(service.discoverCompleteArtistCatalog(seeds))
            .extracting(MusicBrainzCatalogService.Resolution::title)
            .containsExactly("Blue", "Collaboration");
        verify(recordings, times(2)).save(any(Recording.class));
      }
      assertThat(queries).hasSize(2);
      assertThat(queries.getFirst()).contains("offset=0", "artist=" + artistMbid);
      assertThat(queries.getLast()).contains("offset=100", "artist=" + artistMbid);
    } finally {
      server.stop(0);
    }
  }

  private void addRecording(ArrayNode results, String id, String title, String firstArtist, String secondArtist) {
    var credits = results.addObject().put("id", id).put("title", title).putArray("artist-credit");
    credits.addObject().putObject("artist").put("id", firstArtist).put("name", "Named Artist");
    if (secondArtist != null) {
      credits.addObject().putObject("artist").put("id", secondArtist).put("name", "Collaborating Artist");
    }
  }

}
