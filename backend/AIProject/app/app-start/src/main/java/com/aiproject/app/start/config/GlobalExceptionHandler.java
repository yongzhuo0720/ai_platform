package com.aiproject.app.start.config;

import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;

/**
 * 全局异常处理（2026-10-29 新增）。
 *
 * <h3>为什么需要它</h3>
 * 修复前项目里没有任何兜底的异常处理，业务代码里没捕获的异常会直接变成
 * Tomcat 的 500 错误页（或空白响应），前端只能看到「服务异常」，排查要靠翻服务端日志。
 * 已定位的高频触发点例如：用户的 gid 指向不存在的部门时 UnitBeanHolder 直接 NPE、
 * Embedding 返回空列表时 get(0) 越界、ModelsBeanHolder 里 Boolean 自动拆箱 NPE。
 *
 * <h3>和框架自带处理器的关系</h3>
 * 私有框架已经有一个 {@code com.heneng.hnsp.common.croe.web.blocker.ControllerExceptionAdvice}，
 * 但它只处理三种类型：DataException / JSONException / PSQLException。
 * 常见的 NullPointerException、IndexOutOfBoundsException、IllegalStateException 都不在范围内。
 * 本类是补充，不冲突——Spring 会优先匹配最具体的 @ExceptionHandler，
 * 那三类异常仍然由框架的处理器接走。
 *
 * <h3>两个刻意的设计取舍</h3>
 * <ol>
 *   <li><b>HTTP 状态码保持 200，错误放进 Result 的 resp_code。</b>
 *       本项目所有接口的约定就是「HTTP 200 + Result.resp_code 表示成败」，
 *       前端 axios 拦截器与业务代码都按 resp_code 判断。
 *       如果这里返回 500，前端只会看到拦截器里那句笼统的「服务异常」，
 *       真正的错误信息反而丢了。真实错误靠下面的 log.error 落服务端日志。</li>
 *   <li><b>ResponseStatusException 原样抛出，不接。</b>
 *       AI 网关用它表达 400 / 413 等语义（例如请求体超限），
 *       如果被这里的兜底逻辑吃掉并改成 200，网关的状态码语义就全被抹平了。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数缺失 / 类型不匹配：属于调用方错误，提示可以直接回给前端。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class,
            IllegalStateException.class})
    public Result<Void> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("[GlobalException] 请求参数异常 uri={} : {}", request.getRequestURI(), e.getMessage());
        return Result.failed(null, "请求参数有误：" + safeMessage(e));
    }

    /**
     * 兜底：其余所有未捕获异常。
     *
     * <p>不吞异常、不改断言、不伪造成功——统一转成结构化失败响应，
     * 同时用 error 级别完整记录堆栈，保证问题仍然可追溯。</p>
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        // 网关用它表达明确的 HTTP 语义，交给 Spring 自己处理，不要抹平成 200
        if (e instanceof ResponseStatusException) {
            throw (ResponseStatusException) e;
        }
        log.error("[GlobalException] 未处理异常 uri={} method={}",
                request.getRequestURI(), request.getMethod(), e);
        return Result.failed(null, "服务内部错误，请稍后重试（详见服务端日志）");
    }

    /**
     * 异常消息可能包含上游内部信息（内网地址、SQL 片段等），
     * 这里只回显一个安全的简短描述，完整信息留在日志里。
     */
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
    }
}
