package com.bluemalic.repair.sso;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 调身份提供方的最小 HTTP 客户端（阶段 7 · ADR-022）。
 *
 * <p>**不引任何新依赖**：Spring 自带的 {@code RestClient} + JDK 的 {@code HttpClient}。
 * 这里收着三件所有协议实现都要做的事：
 * <ol>
 *   <li>**超时**：外部系统（学校的 IdP）不能把登录拖死——连接与读取各 5 秒，可用
 *       {@code repair.sso.http-timeout-seconds} 调整；</li>
 *   <li>**错误归类**：连不上/超时/非 2xx 一律转成 {@code 30001 统一认证失败}（对用户是同一个意思），
 *       原始状态码与响应体只进日志；</li>
 *   <li>**不把 IdP 的响应回显给用户**：换取 token 的响应里有凭据。</li>
 * </ol>
 */
@Slf4j
@Component
public class SsoHttpClient {

    /** 日志里响应体最多记这么多字符：足够看出"哪里不对"，又不会把整页 HTML 灌进日志。 */
    private static final int LOG_BODY_LIMIT = 300;

    private final RestClient restClient;

    public SsoHttpClient(SsoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getHttpTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.getHttpTimeoutSeconds()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** GET 一段文本（取用户信息 / 校验 ticket）；{@code bearerToken} 为空就不带 Authorization。 */
    public String get(String url, String bearerToken) {
        try {
            // **传 URI 而不是字符串**：RestClient 把字符串当 URI 模板，默认会再编码一次——
            // 地址里已经编码过的 `%3D` 会变成 `%253D`（CAS 的 service 因此对不上，演示站实测踩到）。
            // 预构建的 URI 不会再被模板化。
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(URI.create(url));
            if (bearerToken != null && !bearerToken.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + bearerToken);
            }
            return spec.retrieve().body(String.class);
        } catch (ResourceAccessException e) {
            log.warn("统一认证：连不上身份提供方 url={} err={}", url, e.getMessage());
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：连不上学校的身份认证服务");
        } catch (RestClientResponseException e) {
            log.warn("统一认证：身份提供方返回异常 url={} status={} body={}",
                    url, e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：身份认证服务返回了异常状态");
        }
    }

    /** 以表单方式 POST（换 token）；返回响应文本。 */
    public String postForm(String url, MultiValueMap<String, String> form) {
        try {
            return restClient.post().uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            log.warn("统一认证：连不上身份提供方 url={} err={}", url, e.getMessage());
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：连不上学校的身份认证服务");
        } catch (RestClientResponseException e) {
            log.warn("统一认证：身份提供方返回异常 url={} status={} body={}",
                    url, e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            throw new BizException(ErrorCode.LOGIN_FAILED, "统一认证失败：身份认证服务返回了异常状态");
        }
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOG_BODY_LIMIT ? body : body.substring(0, LOG_BODY_LIMIT) + "…";
    }
}
