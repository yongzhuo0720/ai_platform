package com.aiproject.ai.permission.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户密码字段的 JSON 序列化边界测试。
 *
 * <p><b>这个测试是踩坑后补的</b>：曾经给 {@code upwd} 加 {@code @JsonIgnore}
 * 想防止密码泄露到响应里，结果 {@code @JsonIgnore} 连<b>反序列化</b>一起挡掉，
 * 导致 {@code /myuser/create} 与 {@code /myuser/update} 收不到密码，
 * 建用户和改密码直接失效。</p>
 *
 * <p>正确做法是 {@code @JsonProperty(access = WRITE_ONLY)}：</p>
 * <ul>
 *   <li>能<b>写入</b>对象（请求体里的密码要能收进来）</li>
 *   <li>不能<b>输出</b>（任何响应都不该带密码）</li>
 * </ul>
 *
 * <p>两个方向都必须测，只测一个方向就会重蹈覆辙。</p>
 */
class MyUserBaseDbJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 入方向：请求体里的 upwd 必须能反序列化进来，否则建用户/改密码失效 */
    @Test
    void upwdShouldDeserializeFromRequest() throws Exception {
        String json = "{\"uaccess\":\"admin\",\"upwd\":\"P@ssw0rd!\",\"uname\":\"管理员\"}";

        MyUserBaseDb bean = mapper.readValue(json, MyUserBaseDb.class);

        assertNotNull(bean);
        assertEquals("admin", bean.getUaccess());
        assertEquals("P@ssw0rd!", bean.getUpwd(), "请求体里的密码必须能收进来");
    }

    /** 出方向：任何序列化都不能带出密码 */
    @Test
    void upwdShouldNeverSerializeToResponse() throws Exception {
        MyUserBaseDb bean = new MyUserBaseDb();
        bean.setUid("u1");
        bean.setUaccess("admin");
        bean.setUpwd("hashed-secret");
        bean.setUname("管理员");

        String json = mapper.writeValueAsString(bean);

        assertFalse(json.contains("upwd"), "响应里不能出现 upwd 字段：" + json);
        assertFalse(json.contains("hashed-secret"), "响应里不能出现密码值：" + json);
        // 其它字段要正常输出，不能因为防护把整条记录搞空
        assertTrue(json.contains("admin"), "非敏感字段应正常序列化");
    }

    /** 集合响应同样不能泄露密码（分页查询返回 List） */
    @Test
    void upwdShouldNotSerializeInsideCollections() throws Exception {
        MyUserBaseDb bean = new MyUserBaseDb();
        bean.setUaccess("admin");
        bean.setUpwd("hashed-secret");

        String json = mapper.writeValueAsString(java.util.Collections.singletonList(bean));

        assertFalse(json.contains("hashed-secret"));
        assertFalse(json.contains("upwd"));
    }
}
