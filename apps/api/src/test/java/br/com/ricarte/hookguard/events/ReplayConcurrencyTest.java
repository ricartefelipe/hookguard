package br.com.ricarte.hookguard.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.ricarte.hookguard.auth.AuthService;
import br.com.ricarte.hookguard.billing.UsageService;
import br.com.ricarte.hookguard.domain.Account;
import br.com.ricarte.hookguard.domain.DeliveryAttemptRepository;
import br.com.ricarte.hookguard.domain.DeliveryJobRepository;
import br.com.ricarte.hookguard.domain.EventStatus;
import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.domain.WebhookEventRepository;
import br.com.ricarte.hookguard.ingest.IngestService;
import br.com.ricarte.hookguard.project.ProjectService;
import br.com.ricarte.hookguard.support.DatabaseCleaner;
import br.com.ricarte.hookguard.support.PostgresIntegrationTest;
import br.com.ricarte.hookguard.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReplayConcurrencyTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private IngestService ingestService;
    @Autowired private AuthService authService;
    @Autowired private UsageService usageService;
    @Autowired private ReplayService replayService;
    @Autowired private DeliveryJobRepository jobRepository;
    @Autowired private DeliveryAttemptRepository attemptRepository;
    @Autowired private WebhookEventRepository eventRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    private Account owner;
    private Project project;

    @BeforeEach
    void setUp() {
        cleaner.clean();
        owner = projectService.ensureAccount("replay-" + UUID.randomUUID() + "@example.com", "Owner");
        jdbc.update("UPDATE accounts SET plan = 'pro' WHERE id = ?", owner.getId());
        project = projectService.create(owner.getId(), "demo", "https://example.com/hooks", null);
    }

    private UUID closedEvent() {
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{\"n\":1}".getBytes(), "application/json");
        jdbc.update("UPDATE delivery_jobs SET state = 'dead' WHERE event_id = ?", eventId);
        jdbc.update("UPDATE events SET status = 'dead' WHERE id = ?", eventId);
        return eventId;
    }

    private List<Object> race(int callers, Callable<Object> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousReplaysOfOneEventCreateExactlyOneOpenJob() throws Exception {
        UUID eventId = closedEvent();
        long usageBefore = usageService.currentUsage(owner.getId());

        List<Object> results = race(8, () -> replayService.replay(owner.getId(), eventId, "reprocessar"));

        long winners = results.stream().filter(r -> r instanceof UUID).count();
        List<Object> losers = results.stream().filter(r -> !(r instanceof UUID)).toList();
        assertThat(winners).isEqualTo(1);
        assertThat(losers).hasSize(7).allSatisfy(loser -> {
            assertThat(loser).isInstanceOf(ApiException.class);
            assertThat(((ApiException) loser).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(((ApiException) loser).getCode()).isEqualTo("replay_already_queued");
        });
        assertThat(jobRepository.findOpenByEventId(eventId)).hasSize(1);
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).hasSize(2);
        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.RECEIVED);
        assertThat(usageService.currentUsage(owner.getId())).isEqualTo(usageBefore);
    }

    @Test
    void simultaneousHttpReplaysAnswerConflictNeverServerError() throws Exception {
        UUID eventId = closedEvent();
        String token = String.valueOf(authService.createSessionForAccount(owner).get("sessionToken"));

        List<Object> statuses = race(6, () -> mockMvc.perform(post("/v1/events/" + eventId + "/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mesmo evento\"}"))
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> (int) s == 200).count()).isEqualTo(1);
        assertThat(jobRepository.findOpenByEventId(eventId)).hasSize(1);
    }

    @Test
    void differentEventsProgressIndependently() throws Exception {
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(closedEvent());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier barrier = new CyclicBarrier(8);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (UUID eventId : events) {
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            return replayService.replay(owner.getId(), eventId, "lote");
                        } catch (Exception ex) {
                            return ex;
                        }
                    }));
                }
            }
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID eventId : events) {
            assertThat(jobRepository.findOpenByEventId(eventId)).hasSize(1);
            assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).hasSize(2);
        }
    }

    @Test
    void databaseRejectsASecondOpenJobEvenWithoutTheService() {
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{}".getBytes(), "application/json");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO delivery_jobs (id, event_id, project_id, available_at, state, attempt_number, trigger_type) "
                        + "SELECT ?, event_id, project_id, now(), 'pending', 0, 'replay' FROM delivery_jobs WHERE event_id = ?",
                UUID.randomUUID(), eventId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE delivery_jobs SET state = 'in_progress' WHERE event_id = ?", eventId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO delivery_jobs (id, event_id, project_id, available_at, state, attempt_number, trigger_type) "
                        + "SELECT ?, event_id, project_id, now(), 'pending', 0, 'replay' FROM delivery_jobs WHERE event_id = ?",
                UUID.randomUUID(), eventId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void replayIsRefusedWhileTheOriginalJobIsStillInFlightAndKeepsEventStatus() {
        UUID eventId = ingestService.accept(project.getProjectKey(), Map.of(), "{}".getBytes(), "application/json");
        jdbc.update("UPDATE delivery_jobs SET state = 'in_progress' WHERE event_id = ?", eventId);
        jdbc.update("UPDATE events SET status = 'delivering' WHERE id = ?", eventId);

        assertThatThrownBy(() -> replayService.replay(owner.getId(), eventId, "cedo demais"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("replay_already_queued");

        assertThat(eventRepository.findById(eventId).orElseThrow().getStatus()).isEqualTo(EventStatus.DELIVERING);
        assertThat(jobRepository.findByEventIdOrderByAvailableAtAsc(eventId)).hasSize(1);
    }

    @Test
    void attemptBudgetCannotBeBypassedByReplay() {
        UUID eventId = closedEvent();
        for (int i = 1; i <= ReplayService.MAX_ATTEMPTS_PER_EVENT; i++) {
            jdbc.update("INSERT INTO delivery_attempts (id, event_id, attempt_number, started_at, finished_at, http_status) VALUES (?, ?, ?, now(), now(), 500)",
                    UUID.randomUUID(), eventId, i);
        }
        assertThatThrownBy(() -> replayService.replay(owner.getId(), eventId, "mais uma"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(attemptRepository.countByEventId(eventId)).isEqualTo(ReplayService.MAX_ATTEMPTS_PER_EVENT);
        assertThat(jobRepository.findOpenByEventId(eventId)).isEmpty();
    }

    @Test
    void anotherAccountCannotReplayDuringTheRace() throws Exception {
        UUID eventId = closedEvent();
        Account intruder = projectService.ensureAccount("intruder-" + UUID.randomUUID() + "@example.com", "Other");
        List<Object> results = race(4, () -> replayService.replay(intruder.getId(), eventId, "tentativa"));
        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ApiException.class);
            assertThat(((ApiException) result).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        assertThat(jobRepository.findOpenByEventId(eventId)).isEmpty();
    }
}
