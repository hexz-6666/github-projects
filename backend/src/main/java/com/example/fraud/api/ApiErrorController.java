package com.example.fraud.api;

import java.util.Collections;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ApiErrorController implements ErrorController {
    private final ApiExceptionHandler handler;

    public ApiErrorController(ApiExceptionHandler handler) {
        this.handler = handler;
    }

    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusValue = statusAttribute instanceof Integer ? (Integer) statusAttribute : 500;
        HttpStatus status = HttpStatus.resolve(statusValue);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code;
        String message;
        switch (status) {
            case NOT_FOUND:
                code = "NOT_FOUND";
                message = "请求资源不存在";
                break;
            case METHOD_NOT_ALLOWED:
                code = "METHOD_NOT_ALLOWED";
                message = "请求方法不受支持";
                break;
            case UNSUPPORTED_MEDIA_TYPE:
                code = "UNSUPPORTED_MEDIA_TYPE";
                message = "写接口仅支持 application/json";
                break;
            case NOT_ACCEPTABLE:
                code = "NOT_ACCEPTABLE";
                message = "无法生成可接受的响应";
                break;
            default:
                if (status.is4xxClientError()) {
                    code = "INVALID_REQUEST";
                    message = "请求不符合接口约束";
                } else {
                    code = "INTERNAL_ERROR";
                    message = "服务暂时无法处理请求";
                }
        }
        return handler.response(status, code, message, request,
                Collections.<ApiException.ErrorDetail>emptyList());
    }
}
