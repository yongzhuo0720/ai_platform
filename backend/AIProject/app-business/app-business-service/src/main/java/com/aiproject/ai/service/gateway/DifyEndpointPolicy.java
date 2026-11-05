package com.aiproject.ai.service.gateway;

import java.util.Locale;

/**
 * Dify 上游路径白名单。
 *
 * <p>网关的职责是「把浏览器对 Dify 的调用收口」，而不是「把 Dify 的整个 API
 * 都暴露给任意登录用户」。修复前只挡了 {@code ..} 与 {@code %2e}，其余路径原样转发，
 * 于是任何登录用户都能借服务端的 Dify 密钥访问前端从未使用的接口——
 * 包括工作流、模型供应商凭据、租户设置等本平台并不打算开放的能力。</p>
 *
 * <p>白名单按「模块 + 方法 + 路径形状」精确匹配，只放行前端实际调用的接口。
 * 路径段只允许字母、数字、下划线与连字符，从根上排除编码绕过与二次解析。
 * 新增前端接口时必须同步在这里登记，否则会被 403 拒绝。</p>
 */
final class DifyEndpointPolicy {

    private DifyEndpointPolicy() {
    }

    /** 该方法与路径是否允许通过网关访问 */
    static boolean isAllowed(String module, String method, String endpoint) {
        if (module == null || method == null || endpoint == null || endpoint.isEmpty()) {
            return false;
        }
        String[] parts = splitAndValidate(endpoint);
        if (parts == null) {
            return false;
        }
        String upperMethod = method.toUpperCase(Locale.ROOT);
        if (AiGatewayConfigResolver.MODULE_CHAT.equals(module)
                || AiGatewayConfigResolver.MODULE_KNOWLEDGE_CHAT.equals(module)) {
            return allowsChat(upperMethod, parts);
        }
        if (AiGatewayConfigResolver.MODULE_FILE.equals(module)) {
            return allowsFile(upperMethod, parts);
        }
        return false;
    }

    /**
     * 只接受单斜杠分隔的普通路径段。百分号、反斜杠、问号、井号一律拒绝——
     * getRequestURI() 是未解码的原始值，拒绝它们比尝试解码更不容易出错。
     */
    private static String[] splitAndValidate(String endpoint) {
        if (endpoint.indexOf('\\') >= 0 || endpoint.indexOf('%') >= 0
                || endpoint.indexOf('?') >= 0 || endpoint.indexOf('#') >= 0) {
            return null;
        }
        String[] parts = endpoint.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.length() > 128) {
                return null;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!allowed) {
                    return null;
                }
            }
        }
        return parts;
    }

    private static boolean allowsChat(String method, String[] p) {
        int n = p.length;
        if ("GET".equals(method)) {
            if (n == 1 && ("parameters".equals(p[0]) || "conversations".equals(p[0])
                    || "messages".equals(p[0]))) {
                return true;
            }
            if (n == 3 && "messages".equals(p[0])
                    && ("feedbacks".equals(p[2]) || "suggested".equals(p[2]))) {
                return true;
            }
            if (n == 5 && "workspaces".equals(p[0]) && "current".equals(p[1])
                    && "models".equals(p[2]) && "model-types".equals(p[3])
                    && ("text-embedding".equals(p[4]) || "llm".equals(p[4]))) {
                return true;
            }
        }
        if ("POST".equals(method)) {
            if (n == 1 && "chat-messages".equals(p[0])) {
                return true;
            }
            if (n == 2 && ("conversations".equals(p[0])
                    || ("files".equals(p[0]) && "upload".equals(p[1])))) {
                return true;
            }
            if (n == 3 && "chat-messages".equals(p[0]) && "stop".equals(p[2])) {
                return true;
            }
            if (n == 3 && "messages".equals(p[0]) && "feedbacks".equals(p[2])) {
                return true;
            }
        }
        return "DELETE".equals(method) && n == 2 && "conversations".equals(p[0]);
    }

    private static boolean allowsFile(String method, String[] p) {
        int n = p.length;
        if (n == 5 && "workspaces".equals(p[0]) && "current".equals(p[1])
                && "models".equals(p[2]) && "model-types".equals(p[3])
                && ("text-embedding".equals(p[4]) || "llm".equals(p[4]))) {
            return "GET".equals(method);
        }
        if (n == 2 && "files".equals(p[0]) && "upload".equals(p[1])) {
            return "POST".equals(method);
        }
        if (!"datasets".equals(p[0])) {
            return false;
        }
        if ("GET".equals(method)) {
            if (n == 1 || n == 2) {
                return true;
            }
            if (n == 3 && "documents".equals(p[2])) {
                return true;
            }
            if (n == 4 && "documents".equals(p[2])) {
                return true;
            }
            if (n == 5 && "documents".equals(p[2])
                    && ("download".equals(p[4]) || "indexing-status".equals(p[4])
                    || "segments".equals(p[4]))) {
                return true;
            }
            if (n == 6 && "documents".equals(p[2]) && "segments".equals(p[4])) {
                return true;
            }
            return n == 7 && "documents".equals(p[2]) && "segments".equals(p[4])
                    && "child_chunks".equals(p[6]);
        }
        if ("POST".equals(method)) {
            if (n == 1) {
                return true;
            }
            if (n == 3 && "retrieve".equals(p[2])) {
                return true;
            }
            if (n == 4 && "document".equals(p[2])
                    && ("create-by-text".equals(p[3]) || "create-by-file".equals(p[3]))) {
                return true;
            }
            if (n == 5 && "documents".equals(p[2])
                    && ("update-by-text".equals(p[4]) || "update-by-file".equals(p[4])
                    || "segments".equals(p[4]))) {
                return true;
            }
            if (n == 6 && "documents".equals(p[2]) && "segments".equals(p[4])) {
                return true;
            }
            return n == 7 && "documents".equals(p[2]) && "segments".equals(p[4])
                    && "child_chunks".equals(p[6]);
        }
        if ("PATCH".equals(method)) {
            if (n == 2) {
                return true;
            }
            if (n == 5 && "documents".equals(p[2]) && "status".equals(p[3])) {
                return true;
            }
            return n == 8 && "documents".equals(p[2]) && "segments".equals(p[4])
                    && "child_chunks".equals(p[6]);
        }
        if ("DELETE".equals(method)) {
            if (n == 2) {
                return true;
            }
            if (n == 4 && "documents".equals(p[2])) {
                return true;
            }
            if (n == 6 && "documents".equals(p[2]) && "segments".equals(p[4])) {
                return true;
            }
            return n == 8 && "documents".equals(p[2]) && "segments".equals(p[4])
                    && "child_chunks".equals(p[6]);
        }
        return false;
    }
}
