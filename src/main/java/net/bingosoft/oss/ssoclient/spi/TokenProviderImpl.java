/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.bingosoft.oss.ssoclient.spi;

import net.bingosoft.oss.ssoclient.SSOConfig;
import net.bingosoft.oss.ssoclient.SSOUtils;
import net.bingosoft.oss.ssoclient.exception.HttpException;
import net.bingosoft.oss.ssoclient.exception.InvalidCodeException;
import net.bingosoft.oss.ssoclient.exception.InvalidTokenException;
import net.bingosoft.oss.ssoclient.exception.TokenExpiredException;
import net.bingosoft.oss.ssoclient.internal.Base64;
import net.bingosoft.oss.ssoclient.internal.HttpClient;
import net.bingosoft.oss.ssoclient.internal.JSON;
import net.bingosoft.oss.ssoclient.internal.JWT;
import net.bingosoft.oss.ssoclient.internal.Strings;
import net.bingosoft.oss.ssoclient.model.AccessToken;
import net.bingosoft.oss.ssoclient.model.Authentication;

import java.net.HttpURLConnection;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class TokenProviderImpl implements TokenProvider {

    private final SSOConfig config;

    private RSAPublicKey publicKey;

    public TokenProviderImpl(SSOConfig config) {
        this.config = config;

        this.refreshPublicKey();
    }

    @Override
    public Authentication verifyJwtAccessToken(String accessToken) throws InvalidTokenException {
        Map<String, Object> map = JWT.verify(accessToken, publicKey);
        if(null == map) {
            map = retryVerify(accessToken);
            if(null == map) {
                throw new InvalidTokenException("Incorrect token : " + accessToken);
            }
        }

        //验证通过
        Authentication authentication = createAuthcFromMap(map);
        
        if(authentication.isExpired()){
            throw new TokenExpiredException(accessToken);
        }
        
        return authentication;
    }

    @Override
    public Authentication verifyIdToken(String idToken) throws InvalidTokenException, TokenExpiredException {
        Map<String, Object> map = JWT.verify(idToken, config.getClientSecret());
        if(null == map){
            throw new InvalidTokenException("Incorrect token : " + idToken);
        }
        //验证通过
        Authentication authentication = createAuthcFromMap(map);
        if(authentication.isExpired()){
            throw new TokenExpiredException(idToken);
        }
        return authentication;
    }

    @Override
    public AccessToken obtainAccessTokenByAuthzCode(String authzCode) throws InvalidCodeException, TokenExpiredException{
        
        Map<String, String> params = new HashMap<String, String>();
        params.put("grant_type","authorization_code");
        params.put("code",authzCode);
        params.put("redirect_uri",Base64.urlEncode(config.getRedirectUri()));

        Map<String, String> header = new HashMap<String, String>();
        String h = SSOUtils.encodeBasicAuthorizationHeader(config.getClientId(),config.getClientSecret());
        header.put(SSOUtils.AUTHORIZATION_HEADER,h);
        
        String json;
        try {
            json = HttpClient.post(config.getTokenEndpointUrl(),params,header);
        } catch (HttpException e) {
            if(e.getCode() < HttpURLConnection.HTTP_INTERNAL_ERROR){
                throw new InvalidCodeException(e.getMessage());
            }else{
                throw e;
            }
        }
        
        Map<String, Object> map = JSON.decodeToMap(json);
        
        AccessToken token = createAccessTokenFromMap(map);
        
        if(null == token.getAccessToken() || token.getAccessToken().isEmpty()){
            throw new InvalidCodeException("invalid authorization code: "+authzCode);
        }
        
        if(token.isExpired()){
            throw new TokenExpiredException("access token obtain by authorization code " +authzCode+ " is expired!");
        }
        
        return token;
    }

    @Override
    public Authentication verifyBearerAccessToken(String accessToken) {
        throw new UnsupportedOperationException("Not implemented");
    }

    protected Map<String,Object> retryVerify(String accessToken) {
        //先刷新public key
        refreshPublicKey();

        //再verify一次
        return JWT.verify(accessToken, publicKey);
    }

    protected void refreshPublicKey() {
        String publicKeyBase64 = HttpClient.get(config.getPublicKeyEndpointUrl());

        this.publicKey = decodePublicKey(publicKeyBase64);
    }

    private static RSAPublicKey decodePublicKey(String base64) {
        try{
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.mimeDecode(base64));
            KeyFactory f = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) f.generatePublic(spec);
        }catch (Exception e) {
            throw new RuntimeException("Decode public key error", e);
        }
    }
    
    protected Authentication createAuthcFromMap(Map<String, Object> map){
        Authentication authentication = new Authentication();
        authentication.setUserId((String)map.remove("user_id"));
        authentication.setUsername((String)map.remove("username"));
        authentication.setClientId((String)map.remove("client_id"));
        authentication.setScope((String)map.remove("scope"));

        String expires = Strings.nullOrToString(map.remove("exp"));
        authentication.setExpires(expires == null ? 0 : Long.parseLong(expires));
        for (Entry<String, Object> entry : map.entrySet()){
            authentication.setAttribute(entry.getKey(),entry.getValue());
        }
        return authentication;
    }
    
    protected AccessToken createAccessTokenFromMap(Map<String, Object> map){
        AccessToken token = new AccessToken();
        token.setAccessToken((String)map.remove("access_token"));
        token.setRefreshToken((String)map.remove("refresh_token"));
        token.setTokenType((String)map.remove("token_type"));
        String expiresIn = Strings.nullOrToString(map.remove("expires_in"));
        token.setExpiresInFromNow(expiresIn==null?0:Integer.parseInt(expiresIn));
        return token;
    }
}
