[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.bingosoft.oss/sso-client/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.bingosoft.oss/sso-client/badge.svg) [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

# 品高单点登录客户端(Java)

品高单点登录遵循[OAuth 2.0](https://tools.ietf.org/html/rfc6749)和[Open ID Connect Core](http://openid.net/specs/openid-connect-core-1_0.html)协议，在继续阅读前请先了解一下相关的概念。

## 安装
**Maven**

```xml
<dependency>
	<groupId>net.bingosoft.oss</groupId>
	<artifactId>sso-client</artifactId>
	<version>[3.0.0,]</version>
</dependency>
```

## 运行环境

|SDK 版本 | Java 版本|
| ------ | -------  |
|3.x.x   |6+        |

## 外部依赖
|名称      | 版本    | 依赖说明|      
| ------- | ------- | ------- |  
|fastjson | 1.2.31+ | JSON解析 |
|slf4j    | 1.7.5+  | 程序日志 |      
|commons-codec | 1.10+ | Base64解码 |

## 前提条件

在使用之前需要到单点登录服务中注册一个应用，申请以下参数：

|参数           | 必须 | 说明 |
| -------      | ---- | -------- | 
|client_id     | 是    | 应用的标识 |
|client_secret | 是    | 应用的密钥 |
|redirect_uri  | 否    | 应用登录后返回的地址，用到登录功能才需要注册 |

> 注：如何注册一个应用不在此文档中描述

## 使用

### 1. 配置`SSOClient`对象

配置`SSOClient`对象需要先构造一个`SSOConfig`对象，示例如下：

```java
// 创建SSOConfig对象
SSOConfig config = new SSOConfig();
// 设置应用标识
config.setClientId("clientId");
// 设置应用密钥
config.setClientSecret("clientSecret");
// 设置回调地址
ssoConfig.setRedirectUri(redirectUri);
// 根据SSO地址自动配置其他地址
config.autoConfigureUrls("http://sso.example.com");

// 创建client对象
SSOClient client = new SSOClient(config);
```

### 2. 身份认证 (Authentication)

在Restful API中，对于遵循[OAuth 2.0](https://tools.ietf.org/html/rfc6749)标准协议的请求，使用如下方式校验用户身份：

```java
public class DemoServlet extends javax.servlet.http.HttpServlet{
    // 省略client初始化过程    
    protected SSOClient client;
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // 获取access token
        String accessToken = SSOUtils.extractAccessToken(req);
        // 验证access token
        Authentication authc = null;
        try{
            authc = client.verifyAccessToken(accessToken);            
        }catch (InvalidTokenException e){
            // 处理access token无效的情况
        }catch (TokenExpiredException e){
            // 处理access token过期的情况
        }
        // 获取用户id
        String userId = authc.getUserId();
        // 获取用户登录名
        String username = authc.getUsername();
        // 获取客户端应用id
        String client = authc.getClientId();
        // 获取access token的授权列表
        String scope = authc.getScope();
        // 获取access token的过期时间，这个过期时间指的是距离标准日期1970-01-01T00:00:00Z UTC的秒数
        long expires = authc.getExpires();
        
        // 根据Authentication获取用户其他信息的业务代码省略...
        
        // 返回处理成功的结果
        resp.getWriter().write("ok");
    }
}
```

### 3. 登录注销 (Login & Logout)

对于普通的web应用，使用sdk按照如下方式接入品高SSO实现单点登录和单点注销。

#### 3.1 登录

1. 实现一个`AbstractLoginServlet`的HttpServlet

```java
public class LoginServlet extends net.bingosoft.oss.ssoclient.servlet.AbstractLoginServlet {
    @Override
    protected SSOClient getClient(ServletConfig config) {
        // 返回一个配置好的SSOClient对象
        return new SSOClient();
    }
    @Override
    protected void localLogin(HttpServletRequest req, HttpServletResponse resp, Authentication authc,
                              AccessToken token) {
        // 根据校验结果完成本地登录
        req.getSession().setAttribute("user",new LoginUser(authc,token));
    }
}
```

2. 在`web.xml`中配置这个实现类的访问路径

```xml
<servlet>
    <servlet-name>ssoclient</servlet-name>
    <servlet-class>demo.LoginServlet</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>ssoclient</servlet-name>
    <!-- 这个servlet的访问地址，非必要的情况建议不要修改 -->
    <url-pattern>/ssoclient/login</url-pattern>
</servlet-mapping>
```

3. 设置登录跳转地址和登录校验忽略地址

* 在web应用中，对所有需要登录的请求重定向到`/ssoclient/login`这个地址上进行单点登录
* 忽略`/ssoclient/login`这个地址的登录校验防止重定向无限循环

这里以一个Filter示例：

```java
public class LoginFilter implements javax.servlet.Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest)request;
        HttpServletResponse resp = (HttpServletResponse)response;        
        // 判断是否登录，如果已经登录就不需要处理了
        Object o = req.getSession().getAttribute("user");
        if(o != null && o instanceof LoginUser){
            chain.doFilter(req,resp);
            return;
        }
        
        // 忽略到LoginServlet的请求
        String url = req.getRequestURI();
        if(url.startsWith(req.getContextPath()+"/ssoclient/")){
            chain.doFilter(req,resp);
            return;
        }
        // 重定向到LoginServlet
        // 可以通过设置return_url参数决定登录完成后跳转的地址,参数值是经过url编码的地址
        // 如return_url=http%3A%2F%2Flocalhost%3A8080%2Fdemo
        String redirectUri = req.getContextPath()+"/ssoclient/login";
        resp.sendRedirect(redirectUri);
    }
    
    // 省略其他方法实现...
}
```

`web.xml`配置如下：

```xml
<filter>
    <filter-name>loginFilter</filter-name>
    <filter-class>demo.LoginFilter</filter-class>
</filter>
<filter-mapping>
    <filter-name>loginFilter</filter-name>
    <url-pattern>/*</url-pattern>
</filter-mapping>
```

到这里接入配置完成。

#### 3.2 注销

1. 实现一个`AbstractLogoutServlet`的Servlet:

```java
public class LoginServlet extends AbstractLoginServlet {
    @Override
    protected SSOClient getClient(ServletConfig config) {
        // 返回一个已经配置好的SSOClient
        return new Client();
    }
    @Override
    protected void localLogin(HttpServletRequest req, HttpServletResponse resp, 
                              Authentication authc, AccessToken token) {
        // 注销本地用户
        req.getSession().setAttribute("user",new LoginUser(authc,token));
    }
}
```

2. 配置注销地址

```xml
<servlet>
    <servlet-name>logout</servlet-name>
    <servlet-class>demo.LogoutServlet</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>logout</servlet-name>
    <url-pattern>/logout</url-pattern><!-- 注销本地访问地址，这个访问地址要和应用注册的注销地址一致 -->
    <url-pattern>/oauth2_logout</url-pattern><!-- 注销SSO访问地址 -->
</servlet-mapping>
```

到这里单点注销配置完成。

web应用登陆后，只要访问`${contextPath}/oauth2_logout`即可完成单点注销。

### 4. 获取访问令牌 (Obtain Access Token)

todo : 简要描述获取访问令牌的适用场景

#### 4.1 通过授权码获取新的访问令牌

todo

#### 4.2 通过已有的访问令牌获取新的访问令牌

todo 

#### 4.3 通过id_token获取新的访问令牌

todo

## 扩展

### 自定义缓存

sdk中提供了简单的access token校验缓存实现`net.bingosoft.oss.ssoclient.spi.CacheProviderImpl`，在实际应用中可以根据需求定制CacheProvider。

定制CacheProvider需要实现`CacheProvider`接口，并用实现类的对象覆盖默认的CacheProvider，示例如下：

```java
// 创建新的CacheProvider实现
class CustomCacheProvider implements net.bingosoft.oss.ssoclient.spi.CacheProvider{
    @Override
    public <T> T get(String key) {
        // 根据传入的key获取已缓存的对象，在校验access token的过程中，这里传入的key是access token
    }

    @Override
    public void put(String key, Object item, long expires) {
        // 根据传入的key和item缓存对象item，这里expires是缓存过期时间，在缓存过期后需要清理缓存
    }

    @Override
    public void remove(String key) {
        // 根据key将缓存的对象清除，在校验access token的过程中，这里传入的key是access token
    }
}
```

```java
// 使用定制的CacheProvider对象
SSOClient client = new SSOClient(config);
// 设置client对象的CacheProvider
client.setCacheProvider(new CustomCacheProvider());
```

## 常见问题

1. **配置好web应用接入SSO，在跳转到SSO时浏览器收到`invalid_request:invalid redirect_uri`错误。**

答：这是由于注册应用时设置的回调地址(redirect_uri)不能匹配sdk生成的回调地址导致的，SDK生成的回调地址一般是如下格式：

```
http(s)://${domain}:${port}/${contextPath}/ssoclient/login?${queryString}
如：http://www.example.com:80/demo/ssoclient/login?name=admin
```

请自行查阅SSO应用注册相关文档，确认应用的回调地址能匹配SDK生成的回调地址，如：

```
http://www.example.com:80/demo/ssoclient/**
```

----

2. **配置好单点注销之后，为什么没有跳转到SSO注销？**

答：
* 检查配置的SSO注销地址，默认是以`/oauth2_logout`结尾的地址
* 检查配置的SSO注销地址是否被其他拦截器拦截

----

3. **配置好单点注销之后，跳转到SSO注销完成，为什么没有本地登录没有被注销？**

答：SSO注销完成后，会根据应用在SSO注册的注销地址(logout_uri)向应用发注销请求，如果本地注销不了，需要检查：
* 应用注册的注销地址是不是应用配置的本地注销地址
* 应用本地注销的地址是否被其他拦截器拦截

----

4. **配置好单点登录后，登录时抛出`HTTP Status 500 - parse json error`**

答：
* 检查`SSOConfig.getTokenEndpointUrl()`返回的地址是否正确，如果返回的结果是html代码，很有可能是这个地址配错了。
* 检查`SSOConfig.getTokenEndpointUrl()`这个地址的返回结果是否正确。

----

5. **配置好单点登录后，登录时抛出`Connection refused: connect[xxx]`**

答：
* 检查`SSOConfig.getTokenEndpointUrl()`返回的地址，在web应用部署的服务器是否可以访问。

----

6. **为什么每次注销完成后，跳转回应用首页会有`__state__`这个参数？**

答：注销完成后，跳转回项目首页为了防止浏览器缓存导致没有自动跳转到登录页，SDK默认自动增加了这个参数，参数值是随机数。

如果不希望增加这个参数，可以重写`AbstractLogoutServlet.getStateQueryParam(req,resp)`这个方法，返回空值。