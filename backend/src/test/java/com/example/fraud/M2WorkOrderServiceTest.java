package com.example.fraud;

import com.example.fraud.api.ApiException;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.service.AssigneeDirectory;
import com.example.fraud.service.AuditService;
import com.example.fraud.service.WorkOrderConfigService;
import com.example.fraud.service.WorkOrderCreateWriter;
import com.example.fraud.service.WorkOrderMatcher;
import com.example.fraud.service.WorkOrderService;
import com.example.fraud.store.DuplicateWorkOrderException;
import com.example.fraud.store.FraudStore;
import com.example.fraud.store.WorkOrderConfigStore;
import com.example.fraud.store.WorkOrderStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class M2WorkOrderServiceTest {
    private static final String DIFFERENT_CONFIG = "WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG";

    @Test
    void duplicateFallbackDoesNotExposeInvisibleExistingWorkOrder() {
        Fixture fixture = new Fixture();
        WorkOrderRecord existing = assignedTo("reviewer_a");
        when(fixture.workOrderStore.findByAlertId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(fixture.workOrderStore.findById(10L)).thenReturn(Optional.of(existing));
        when(fixture.createWriter.create(1L, 1L, "reviewer_b", "REVIEWER", "req-1"))
                .thenThrow(new DuplicateWorkOrderException());

        ApiException exception = assertThrows(ApiException.class,
                () -> fixture.service.create(1L, 1L, fixture.reviewerB, fixture.request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("WORK_ORDER_NOT_FOUND", exception.getCode());
        verify(fixture.createWriter).create(1L, 1L, "reviewer_b", "REVIEWER", "req-1");
    }

    @Test
    void visibleDifferentConfigWriterExceptionRemainsConflict() {
        Fixture fixture = new Fixture();
        WorkOrderRecord existing = assignedTo("reviewer_b");
        when(fixture.workOrderStore.findByAlertId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(fixture.workOrderStore.findById(10L)).thenReturn(Optional.of(existing));
        when(fixture.createWriter.create(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, DIFFERENT_CONFIG,
                        "该告警已使用其他配置创建工单"));

        ApiException exception = assertThrows(ApiException.class,
                () -> fixture.service.create(1L, 2L, fixture.reviewerB, fixture.request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(DIFFERENT_CONFIG, exception.getCode());
    }

    @Test
    void invisibleDifferentConfigWriterExceptionBecomesNotFound() {
        Fixture fixture = new Fixture();
        WorkOrderRecord existing = assignedTo("reviewer_a");
        when(fixture.workOrderStore.findByAlertId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(fixture.workOrderStore.findById(10L)).thenReturn(Optional.of(existing));
        when(fixture.createWriter.create(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, DIFFERENT_CONFIG,
                        "该告警已使用其他配置创建工单"));

        ApiException exception = assertThrows(ApiException.class,
                () -> fixture.service.create(1L, 2L, fixture.reviewerB, fixture.request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("WORK_ORDER_NOT_FOUND", exception.getCode());
    }

    @Test
    void duplicateFallbackForVisibleOwnerReturnsIdempotentResult() {
        Fixture fixture = new Fixture();
        WorkOrderRecord existing = assignedTo("reviewer_b");
        when(fixture.workOrderStore.findByAlertId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(fixture.workOrderStore.findById(10L)).thenReturn(Optional.of(existing));
        when(fixture.createWriter.create(1L, 1L, "reviewer_b", "REVIEWER", "req-1"))
                .thenThrow(new DuplicateWorkOrderException());

        WorkOrderService.CreateResult result = fixture.service.create(
                1L, 1L, fixture.reviewerB, fixture.request);

        assertTrue(result.isIdempotent());
        assertEquals(existing.getId(), result.getRecord().getId());
    }

    @Test
    void writerStaleUnassignedSnapshotIsRereadAndRejectedAfterReassignment() {
        Fixture fixture = new Fixture();
        WorkOrderRecord stale = unassigned();
        when(fixture.workOrderStore.findByAlertId(1L)).thenReturn(Optional.empty());
        when(fixture.createWriter.create(1L, 1L, "reviewer_b", "REVIEWER", "req-1"))
                .thenReturn(new WorkOrderCreateWriter.Result(stale, false));
        when(fixture.workOrderStore.findById(10L)).thenReturn(Optional.of(assignedTo("reviewer_a")));

        ApiException exception = assertThrows(ApiException.class,
                () -> fixture.service.create(1L, 1L, fixture.reviewerB, fixture.request));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("WORK_ORDER_NOT_FOUND", exception.getCode());
    }

    private static WorkOrderRecord unassigned() {
        Instant now = Instant.parse("2026-01-03T08:00:00Z");
        return new WorkOrderRecord(10L, 1L, 1L, 1L, 1, "{}", "REVIEWER", null,
                WorkOrderStatus.OPEN, false, 120, now.plusSeconds(7200), null,
                null, null, 1, "reviewer_b", "reviewer_b", now, now);
    }

    private static WorkOrderRecord assignedTo(String assignee) {
        Instant now = Instant.parse("2026-01-03T08:00:00Z");
        return new WorkOrderRecord(10L, 1L, 1L, 1L, 1, "{}", "REVIEWER", assignee,
                WorkOrderStatus.ASSIGNED, false, 120, now.plusSeconds(7200), null,
                null, null, 2, "reviewer_a", "reviewer_a", now, now);
    }

    private static class Fixture {
        private final FraudStore fraudStore = mock(FraudStore.class);
        private final WorkOrderConfigStore configStore = mock(WorkOrderConfigStore.class);
        private final WorkOrderStore workOrderStore = mock(WorkOrderStore.class);
        private final WorkOrderConfigService configService = mock(WorkOrderConfigService.class);
        private final WorkOrderMatcher matcher = mock(WorkOrderMatcher.class);
        private final WorkOrderCreateWriter createWriter = mock(WorkOrderCreateWriter.class);
        private final AssigneeDirectory assigneeDirectory = mock(AssigneeDirectory.class);
        private final AuditService auditService = mock(AuditService.class);
        private final HttpServletRequest request = mock(HttpServletRequest.class);
        private final Authentication reviewerB = new UsernamePasswordAuthenticationToken(
                "reviewer_b", "n/a", Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_REVIEWER")));
        private final WorkOrderService service;

        private Fixture() {
            when(fraudStore.findAlert(1L)).thenReturn(Optional.of(mock(AlertRecord.class)));
            when(auditService.roleName(reviewerB)).thenReturn("REVIEWER");
            when(auditService.requestId(request)).thenReturn("req-1");
            service = new WorkOrderService(fraudStore, configStore, workOrderStore, configService,
                    matcher, createWriter, assigneeDirectory, auditService, new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-01-03T08:00:00Z"), ZoneOffset.UTC));
        }
    }
}
