package org.credit.biz;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.credit.biz.model.User;
import org.credit.biz.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.credit.biz.mapper.UserMapper;

import jakarta.servlet.http.HttpSession;

@SpringBootTest
@AutoConfigureMockMvc
class CreditSwitchApplicationTests {

    private static final String CAPTCHA_SESSION_KEY = "captcha";
    private static final String EMAIL_CODE_SESSION_KEY = "EMAIL_CODE_KEY";
    private static final String REGISTER_EMAIL_SESSION_KEY = "REGISTER_EMAIL";
    private static final String LOGIN_USER_SESSION_KEY = "LOGIN_USER";
    private static final String USER_PROFILE_CACHE_PREFIX = "user:profile:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JavaMailSender javaMailSender;

    @MockBean
    private UserMapper userMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        initializeMybatisPlusMetadata();
    }

    @Test
    void testRegisterAndLogin_SuccessfulHappyPath_WithSessionLifecycle() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = uniqueEmail();
        String password = "123456";

        User storedUser = buildUser(1L, email, password, email);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, storedUser);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        });

        MvcResult captchaResult = mockMvc.perform(get("/apply/image").session(session))
            .andExpect(status().isOk())
            .andReturn();

        HttpSession captchaSession = captchaResult.getRequest().getSession(false);
        String imageCaptcha = (String) captchaSession.getAttribute(CAPTCHA_SESSION_KEY);

        // 验证图片验证码已写入 Session，供后续发送邮箱验证码使用。
        org.junit.jupiter.api.Assertions.assertNotNull(imageCaptcha);
        org.junit.jupiter.api.Assertions.assertTrue(captchaResult.getResponse().getContentAsByteArray().length > 0);

        mockMvc.perform(post("/apply/send-email-code")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "captcha": "%s"
                    }
                    """.formatted(email, imageCaptcha)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("邮箱验证码发送成功"));

        String emailCode = (String) session.getAttribute(EMAIL_CODE_SESSION_KEY);

        // 发送成功后应写入邮箱验证码和注册邮箱，并移除图片验证码避免复用。
        org.junit.jupiter.api.Assertions.assertNotNull(emailCode);
        org.junit.jupiter.api.Assertions.assertEquals(email, session.getAttribute(REGISTER_EMAIL_SESSION_KEY));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(CAPTCHA_SESSION_KEY));
        verify(javaMailSender, times(1)).send(any(org.springframework.mail.SimpleMailMessage.class));

        mockMvc.perform(post("/apply/register")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "code": "%s",
                      "password": "%s"
                    }
                    """.formatted(email, emailCode, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("注册成功"));

        // 注册成功后，邮箱验证码相关 Session Key 应被清理。
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(EMAIL_CODE_SESSION_KEY));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(REGISTER_EMAIL_SESSION_KEY));

        mockMvc.perform(get("/apply/image").session(session))
            .andExpect(status().isOk());
        String loginCaptcha = (String) session.getAttribute(CAPTCHA_SESSION_KEY);
        org.junit.jupiter.api.Assertions.assertNotNull(loginCaptcha);

        mockMvc.perform(post("/apply/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "%s",
                      "captcha": "%s"
                    }
                    """.formatted(email, password, loginCaptcha)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("登录成功"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value(email));

        // 登录成功后应把用户信息放入 Session，图片验证码应被消费删除。
        org.junit.jupiter.api.Assertions.assertNotNull(session.getAttribute(LOGIN_USER_SESSION_KEY));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(CAPTCHA_SESSION_KEY));
    }

    @Test
    void testSendEmailCode_ShouldRejectWhenImageCaptchaIsWrong() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CAPTCHA_SESSION_KEY, "ABCD");

        mockMvc.perform(post("/apply/send-email-code")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "wrong-captcha@qq.com",
                      "captcha": "WXYZ"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.msg").value("图片验证码错误或者过期"));

        // 验证被拦截后不应发邮件，也不应写入邮箱验证码相关 Session 状态。
        verify(javaMailSender, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(EMAIL_CODE_SESSION_KEY));
        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(REGISTER_EMAIL_SESSION_KEY));
    }

    @Test
    void testRegister_ShouldRejectWhenRegisterEmailDoesNotMatchSessionEmail() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(EMAIL_CODE_SESSION_KEY, "123456");
        session.setAttribute(REGISTER_EMAIL_SESSION_KEY, "sent@qq.com");

        mockMvc.perform(post("/apply/register")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "other@qq.com",
                      "code": "123456",
                      "password": "123456"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.msg").value("注册邮箱与发送验证码邮箱不一致"));

        // 被邮箱一致性校验拦截后，不应落库。
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void testLogin_ShouldFailWhenPasswordIsWrong() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = uniqueEmail();
        session.setAttribute(CAPTCHA_SESSION_KEY, "QWER");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(buildUser(2L, email, "correct-password", email));

        mockMvc.perform(post("/apply/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "wrong-password",
                      "captcha": "QWER"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.msg").value("密码错误"));

        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(LOGIN_USER_SESSION_KEY));
    }

    @Test
    void testLogin_ShouldFailWhenUserDoesNotExist() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = uniqueEmail();
        session.setAttribute(CAPTCHA_SESSION_KEY, "QWER");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(post("/apply/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "123456",
                      "captcha": "QWER"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.msg").value("该邮箱未注册"));

        org.junit.jupiter.api.Assertions.assertNull(session.getAttribute(LOGIN_USER_SESSION_KEY));
    }

    @Test
    void testGetUserProfile_ShouldQueryDatabaseAndPopulateRedisWhenCacheMisses() throws Exception {
        String email = uniqueEmail();
        User storedUser = buildUser(3L, email, "123456", "cache-user");

        when(valueOperations.get(USER_PROFILE_CACHE_PREFIX + email)).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(storedUser);

        mockMvc.perform(get("/apply/users/profile").param("email", email))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("查询用户信息成功（已写入缓存）"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value("cache-user"));

        // 缓存未命中时应回源数据库，并把查询结果写回 Redis。
        verify(userMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
        verify(valueOperations, times(1)).set(
            eq(USER_PROFILE_CACHE_PREFIX + email),
            argThat(json -> json != null && json.contains("\"email\":\"" + email + "\"")),
            eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void testGetUserProfile_ShouldReturnFromRedisWhenCacheHits() throws Exception {
        String email = uniqueEmail();
        UserProfile profile = new UserProfile(4L, "cached-name", email);

        when(valueOperations.get(USER_PROFILE_CACHE_PREFIX + email))
            .thenReturn(objectMapper.writeValueAsString(profile));

        mockMvc.perform(get("/apply/users/profile").param("email", email))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("查询用户信息成功（命中缓存）"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value("cached-name"));

        // 命中缓存时不应访问数据库。
        verify(userMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void testQueryUserProfile_PostShouldReadFromRedisAndMysql() throws Exception {
        String email = uniqueEmail();
        when(valueOperations.get(USER_PROFILE_CACHE_PREFIX + email)).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(buildUser(6L, email, "123456", "post-query-user"));

        mockMvc.perform(post("/apply/users/profile/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("查询用户信息成功（已写入缓存）"))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value("post-query-user"));

        verify(userMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
        verify(valueOperations, times(1)).set(
            eq(USER_PROFILE_CACHE_PREFIX + email),
            argThat(json -> json != null && json.contains("\"username\":\"post-query-user\"")),
            eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void testCreateUserProfile_PostShouldInsertIntoMysqlAndWriteRedis() throws Exception {
        String email = uniqueEmail();

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return 1;
        });

        mockMvc.perform(post("/apply/users/profile/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "123456",
                      "username": "created-user"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("新增用户成功，已写入数据库和缓存"))
            .andExpect(jsonPath("$.data.id").value(7))
            .andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.username").value("created-user"));

        verify(userMapper, times(1)).insert(any(User.class));
        verify(valueOperations, times(1)).set(
            eq(USER_PROFILE_CACHE_PREFIX + email),
            argThat(json -> json != null && json.contains("\"username\":\"created-user\"")),
            eq(Duration.ofMinutes(10))
        );
    }

    @Test
    void testUpdateUsername_ShouldEvictRedisCacheAfterDatabaseUpdate() throws Exception {
        String email = uniqueEmail();

        when(valueOperations.get(USER_PROFILE_CACHE_PREFIX + email)).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(buildUser(5L, email, "123456", "before-update"));
        when(userMapper.update(ArgumentMatchers.<User>isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(stringRedisTemplate.delete(USER_PROFILE_CACHE_PREFIX + email)).thenReturn(Boolean.TRUE);

        mockMvc.perform(get("/apply/users/profile").param("email", email))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/apply/users/profile/username")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "username": "after-update"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("用户名更新成功，缓存已删除"));

        // 更新资料后应删除对应的 Redis 缓存 Key。
        verify(stringRedisTemplate, times(1)).delete(USER_PROFILE_CACHE_PREFIX + email);
    }

    @Test
    void testDeleteUserProfile_PostShouldDeleteMysqlAndEvictRedis() throws Exception {
        String email = uniqueEmail();

        when(userMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(stringRedisTemplate.delete(USER_PROFILE_CACHE_PREFIX + email)).thenReturn(Boolean.TRUE);

        mockMvc.perform(post("/apply/users/profile/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.msg").value("用户删除成功，缓存已删除"));

        verify(userMapper, times(1)).delete(any(LambdaQueryWrapper.class));
        verify(stringRedisTemplate, times(1)).delete(USER_PROFILE_CACHE_PREFIX + email);
    }

    private String uniqueEmail() {
        return "test-" + UUID.randomUUID() + "@qq.com";
    }

    private User buildUser(Long id, String email, String password, String username) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPassword(password);
        user.setUsername(username);
        return user;
    }

    private void initializeMybatisPlusMetadata() {
        if (TableInfoHelper.getTableInfo(User.class) != null) {
            return;
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, User.class);
    }
}
