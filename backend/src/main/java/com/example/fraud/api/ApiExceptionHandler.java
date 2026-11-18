package com.example.fraud.api;

import com.example.fraud.config.RequestIdFilter;
import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.service.OpsLogService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private final Clock clock;
    private final OpsLogService opsLogService;

    public ApiExceptionHandler(Clock clock, OpsLogService opsLogService) {
        this.clock = clock;
        this.opsLogService = opsLogService;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception, HttpServletRequest request,
                                                    Authentication authentication) {
        if (exception.getStatus().is5xxServerError()) {
            recordHttpFailure(request, authentication, exception.getStatus());
        }
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(),
                request, exception.getDetails());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException exception,
                                                          HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不符合接口约束",
                request, Collections.<ApiException.ErrorDetail>emptyList());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request,
                                                          Authentication authentication) {
        recordHttpFailure(request, authentication, HttpStatus.INTERNAL_SERVER_ERROR);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求",
                request, Collections.<ApiException.ErrorDetail>emptyList());
    }

    private void recordHttpFailure(HttpServletRequest request, Authentication authentication,
                                   HttpStatus status) {
        if (request == null || request.getRequestURI().startsWith("/api/v3/ops/")
                || Boolean.TRUE.equals(request.getAttribute(OpsLogService.TASK_EVENT_ATTRIBUTE))) {
            return;
        }
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        Object start = request.getAttribute(RequestIdFilter.START_NANOS_ATTRIBUTE);
        Integer durationMs = null;
        if (start instanceof Number) {
            long millis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - ((Number) start).longValue());
            durationMs = millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
        }
        opsLogService.record(OpsLogSource.BACKEND,
                status.is5xxServerError() ? OpsLogLevel.ERROR : OpsLogLevel.WARN,
                OpsLogCode.HTTP_REQUEST_FAILED, null, null, null, requestId, null,
                authentication == null ? null : authentication.getName(), null, durationMs);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatus status,
                                                                  WebRequest request) {
        List<ApiException.ErrorDetail> details = new ArrayList<ApiException.ErrorDetail>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.add(new ApiException.ErrorDetail(error.getField(), safeValidationMessage(error)));
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不符合接口约束",
                (HttpServletRequest) request.resolveReference("request"), details);
    }

    private String safeValidationMessage(FieldError error) {
        if ("reviewerId".equals(error.getField())) {
            return "reviewerId 长度必须为 1 到 64 个字符";
        }
        if ("comment".equals(error.getField())) {
            return "comment 最长为 500 个字符";
        }
        if ("expectedVersion".equals(error.getField())) {
            return "expectedVersion 必须是大于或等于 1 的整数";
        }
        if ("reviewResult".equals(error.getField())) {
            return "reviewResult 必须是支持的枚举值";
        }
        return "字段不符合接口约束";
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers, HttpStatus status,
                                                                  WebRequest request) {
        return badRequest(request);
    }

    @Override
    protected ResponseEntity<Object> handleBindException(BindException exception, HttpHeaders headers,
                                                         HttpStatus status, WebRequest request) {
        return badRequest(request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
                                                        HttpStatus status, WebRequest request) {
        return badRequest(request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers,
            HttpStatus status, WebRequest request) {
        return badRequest(request);
    }

    private ResponseEntity<Object> badRequest(WebRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不符合接口约束",
                (HttpServletRequest) request.resolveReference("request"),
                Collections.<ApiException.ErrorDetail>emptyList());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception,
                                                                     HttpHeaders headers, HttpStatus status,
                                                                     WebRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "写接口仅支持 application/json", (HttpServletRequest) request.resolveReference("request"),
                Collections.<ApiException.ErrorDetail>emptyList());
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpHeaders headers,
            HttpStatus status, WebRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不受支持",
                (HttpServletRequest) request.resolveReference("request"),
                Collections.<ApiException.ErrorDetail>emptyList());
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException exception,
                                                                   HttpHeaders headers, HttpStatus status,
                                                                   WebRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "请求资源不存在",
                (HttpServletRequest) request.resolveReference("request"),
                Collections.<ApiException.ErrorDetail>emptyList());
    }

    @SuppressWarnings("unchecked")
    public <T extends ResponseEntity<?>> T response(HttpStatus status, String code, String message,
                                                   HttpServletRequest request,
                                                   List<ApiException.ErrorDetail> details) {
        String path = request == null ? "" : request.getRequestURI();
        String requestId = request == null ? "" : String.valueOf(
                request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        ErrorResponse body = new ErrorResponse(clock.instant(), status.value(), code, message,
                path, requestId, details == null ? Collections.<ApiException.ErrorDetail>emptyList() : details);
        return (T) ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
